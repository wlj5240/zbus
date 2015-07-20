package org.zstacks.zbus.store;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.AbstractQueue;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MFQueuePool { 
    private static final Logger log = LoggerFactory.getLogger(MFQueuePool.class);
    private static final BlockingQueue<String> deletingQueue = new LinkedBlockingQueue<String>();
    
    private static MFQueuePool instance = null;
    private String fileBackupPath;
    private Map<String, MFQueue> fQueueMap;
    private ScheduledExecutorService syncService;
 
    private MFQueuePool(String fileBackupPath) {
        this.fileBackupPath = fileBackupPath;
        File fileBackupDir = new File(fileBackupPath);
        if (!fileBackupDir.exists() && !fileBackupDir.mkdir()) {
            throw new IllegalArgumentException("can not create directory");
        }
        this.fQueueMap = scanDir(fileBackupDir);
        this.syncService = Executors.newSingleThreadScheduledExecutor();
        this.syncService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                for (MFQueue MFQueue : fQueueMap.values()) {
                    MFQueue.sync();
                }
                deleteBlockFile();
            }
        }, 10L, 10L, TimeUnit.SECONDS);
    }
 
    private void deleteBlockFile() {
        String blockFilePath = deletingQueue.poll();
        if (blockFilePath==null || blockFilePath.trim().equals("")) {
            File delFile = new File(blockFilePath);
            try {
                if (!delFile.delete()) {
                    log.warn("block file:{} delete failed", blockFilePath);
                }
            } catch (SecurityException e) {
                log.error("security manager exists, delete denied");
            }
        }
    }
 
    private static void toClear(String filePath) {
        deletingQueue.add(filePath);
    }
 
    private Map<String, MFQueue> scanDir(File fileBackupDir) {
        if (!fileBackupDir.isDirectory()) {
            throw new IllegalArgumentException("it is not a directory");
        }
        Map<String, MFQueue> exitsFQueues = new HashMap<String, MFQueue>();
        File[] indexFiles = fileBackupDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return MFQueueIndex.isIndexFile(name);
            }
        });
        if (indexFiles != null && indexFiles.length> 0) {
            for (File indexFile : indexFiles) {
                String queueName = MFQueueIndex.parseQueueName(indexFile.getName());
                exitsFQueues.put(queueName, new MFQueue(queueName, fileBackupPath));
            }
        }
        return exitsFQueues;
    }
 
    public synchronized static void init(String deployPath) {
        if (instance == null) {
            instance = new MFQueuePool(deployPath);
        }
    }
 
    private void disposal() {
        this.syncService.shutdown();
        for (MFQueue MFQueue : fQueueMap.values()) {
            MFQueue.close();
        }
        while (!deletingQueue.isEmpty()) {
            deleteBlockFile();
        }
    }
 
    public synchronized static void destory() {
        if (instance != null) {
            instance.disposal();
            instance = null;
        }
    }
 
    private MFQueue getQueueFromPool(String queueName) {
        if (fQueueMap.containsKey(queueName)) {
            return fQueueMap.get(queueName);
        }
        MFQueue MFQueue = new MFQueue(queueName, fileBackupPath);
        fQueueMap.put(queueName, MFQueue);
        return MFQueue;
    }
 
    public synchronized static MFQueue getFQueue(String queueName) {
        if (queueName==null || queueName.trim().equals("")) {
            throw new IllegalArgumentException("empty queue name");
        }
        return instance.getQueueFromPool(queueName);
    }
 
    public static class MFQueue extends AbstractQueue<byte[]> {
 
        private String queueName;
        private String fileBackupDir;
        private MFQueueIndex index;
        private MFQueueBlock readBlock;
        private MFQueueBlock writeBlock;
        private ReentrantLock readLock;
        private ReentrantLock writeLock;
        private AtomicInteger size;
 
        public MFQueue(String queueName, String fileBackupDir) {
            this.queueName = queueName;
            this.fileBackupDir = fileBackupDir;
            this.readLock = new ReentrantLock();
            this.writeLock = new ReentrantLock();
            this.index = new MFQueueIndex(MFQueueIndex.formatIndexFilePath(queueName, fileBackupDir));
            this.size = new AtomicInteger(index.getWriteCounter() - index.getReadCounter());
            this.writeBlock = new MFQueueBlock(index, MFQueueBlock.formatBlockFilePath(queueName,
                    index.getWriteNum(), fileBackupDir));
            if (index.getReadNum() == index.getWriteNum()) {
                this.readBlock = this.writeBlock.duplicate();
            } else {
                this.readBlock = new MFQueueBlock(index, MFQueueBlock.formatBlockFilePath(queueName,
                        index.getReadNum(), fileBackupDir));
            }
        }
 
        @Override
        public Iterator<byte[]> iterator() {
            throw new UnsupportedOperationException();
        }
 
        @Override
        public int size() {
            return this.size.get();
        }
 
        private void rotateNextWriteBlock() {
            int nextWriteBlockNum = index.getWriteNum() + 1;
            nextWriteBlockNum = (nextWriteBlockNum < 0) ? 0 : nextWriteBlockNum;
            writeBlock.putEOF();
            if (index.getReadNum() == index.getWriteNum()) {
                writeBlock.sync();
            } else {
                writeBlock.close();
            }
            writeBlock = new MFQueueBlock(index, MFQueueBlock.formatBlockFilePath(queueName,
                    nextWriteBlockNum, fileBackupDir));
            index.putWriteNum(nextWriteBlockNum);
            index.putWritePosition(0);
        }
 
        @Override
        public boolean offer(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return true;
            }
            writeLock.lock();
            try {
                if (!writeBlock.isSpaceAvailable(bytes.length)) {
                    rotateNextWriteBlock();
                }
                writeBlock.write(bytes);
                size.incrementAndGet();
                return true;
            } finally {
                writeLock.unlock();
            }
        }
 
        private void rotateNextReadBlock() {
            if (index.getReadNum() == index.getWriteNum()) {
                // 读缓存块的滑动必须发生在写缓存块滑动之后
                return;
            }
            int nextReadBlockNum = index.getReadNum() + 1;
            nextReadBlockNum = (nextReadBlockNum < 0) ? 0 : nextReadBlockNum;
            readBlock.close();
            String blockPath = readBlock.getBlockFilePath();
            if (nextReadBlockNum == index.getWriteNum()) {
                readBlock = writeBlock.duplicate();
            } else {
                readBlock = new MFQueueBlock(index, MFQueueBlock.formatBlockFilePath(queueName,
                        nextReadBlockNum, fileBackupDir));
            }
            index.putReadNum(nextReadBlockNum);
            index.putReadPosition(0);
            MFQueuePool.toClear(blockPath);
        }
 
        @Override
        public byte[] poll() {
            readLock.lock();
            try {
                if (readBlock.eof()) {
                    rotateNextReadBlock();
                }
                byte[] bytes = readBlock.read();
                if (bytes != null) {
                    size.decrementAndGet();
                }
                return bytes;
            } finally {
                readLock.unlock();
            }
        }
 
        @Override
        public byte[] peek() {
            throw new UnsupportedOperationException();
        }
 
        public void sync() {
            index.sync();
            // read block只读，不用同步
            writeBlock.sync();
        }
 
        public void close() {
            writeBlock.close();
            if (index.getReadNum() != index.getWriteNum()) {
                readBlock.close();
            }
            index.reset();
            index.close();
        }
    }
  
    private static class MFQueueIndex {
 
        private static final String MAGIC = "v.1.0000";
        private static final String INDEX_FILE_SUFFIX = ".idx";
        private static final int INDEX_SIZE = 32;
 
        private static final int READ_NUM_OFFSET = 8;
        private static final int READ_POS_OFFSET = 12;
        private static final int READ_CNT_OFFSET = 16;
        private static final int WRITE_NUM_OFFSET = 20;
        private static final int WRITE_POS_OFFSET = 24;
        private static final int WRITE_CNT_OFFSET = 28;
 
        private int p11, p12, p13, p14, p15, p16, p17, p18; // 缓存行填充 32B
        private volatile int readPosition;   // 12   读索引位置
        private volatile int readNum;        // 8   读索引文件号
        private volatile int readCounter;    // 16   总读取数量
        private int p21, p22, p23, p24, p25, p26, p27, p28; // 缓存行填充 32B
        private volatile int writePosition;  // 24  写索引位置
        private volatile int writeNum;       // 20  写索引文件号
        private volatile int writeCounter;   // 28 总写入数量
        private int p31, p32, p33, p34, p35, p36, p37, p38; // 缓存行填充 32B
 
        private RandomAccessFile indexFile;
        private FileChannel fileChannel;
        // 读写分离
        private MappedByteBuffer writeIndex;
        private MappedByteBuffer readIndex;
 
        public MFQueueIndex(String indexFilePath) {
            File file = new File(indexFilePath);
            try {
                if (file.exists()) {
                    this.indexFile = new RandomAccessFile(file, "rw");
                    byte[] bytes = new byte[8];
                    this.indexFile.read(bytes, 0, 8);
                    if (!MAGIC.equals(new String(bytes))) {
                        throw new IllegalArgumentException("version mismatch");
                    }
                    this.readNum = indexFile.readInt();
                    this.readPosition = indexFile.readInt();
                    this.readCounter = indexFile.readInt();
                    this.writeNum = indexFile.readInt();
                    this.writePosition = indexFile.readInt();
                    this.writeCounter = indexFile.readInt();
                    this.fileChannel = indexFile.getChannel();
                    this.writeIndex = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, INDEX_SIZE);
                    this.writeIndex = writeIndex.load();
                    this.readIndex = (MappedByteBuffer) writeIndex.duplicate();
                } else {
                    this.indexFile = new RandomAccessFile(file, "rw");
                    this.fileChannel = indexFile.getChannel();
                    this.writeIndex = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, INDEX_SIZE);
                    this.readIndex = (MappedByteBuffer) writeIndex.duplicate();
                    putMagic();
                    putReadNum(0);
                    putReadPosition(0);
                    putReadCounter(0);
                    putWriteNum(0);
                    putWritePosition(0);
                    putWriteCounter(0);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
 
        public static boolean isIndexFile(String fileName) {
            return fileName.endsWith(INDEX_FILE_SUFFIX);
        }
 
        public static String parseQueueName(String indexFileName) {
            String fileName = indexFileName.substring(0, indexFileName.lastIndexOf('.'));
            return fileName.split("_")[1];
        }
 
        public static String formatIndexFilePath(String queueName, String fileBackupDir) {
            return fileBackupDir + File.separator + String.format("findex_%s%s", queueName, INDEX_FILE_SUFFIX);
        }
 
        public int getReadNum() {
            return this.readNum;
        }
 
        public int getReadPosition() {
            return this.readPosition;
        }
 
        public int getReadCounter() {
            return this.readCounter;
        }
 
        public int getWriteNum() {
            return this.writeNum;
        }
 
        public int getWritePosition() {
            return this.writePosition;
        }
 
        public int getWriteCounter() {
            return this.writeCounter;
        }
 
        public void putMagic() {
            this.writeIndex.position(0);
            this.writeIndex.put(MAGIC.getBytes());
        }
 
        public void putWritePosition(int writePosition) {
            this.writeIndex.position(WRITE_POS_OFFSET);
            this.writeIndex.putInt(writePosition);
            this.writePosition = writePosition;
        }
 
        public void putWriteNum(int writeNum) {
            this.writeIndex.position(WRITE_NUM_OFFSET);
            this.writeIndex.putInt(writeNum);
            this.writeNum = writeNum;
        }
 
        public void putWriteCounter(int writeCounter) {
            this.writeIndex.position(WRITE_CNT_OFFSET);
            this.writeIndex.putInt(writeCounter);
            this.writeCounter = writeCounter;
        }
 
        public void putReadNum(int readNum) {
            this.readIndex.position(READ_NUM_OFFSET);
            this.readIndex.putInt(readNum);
            this.readNum = readNum;
        }
 
        public void putReadPosition(int readPosition) {
            this.readIndex.position(READ_POS_OFFSET);
            this.readIndex.putInt(readPosition);
            this.readPosition = readPosition;
        }
 
        public void putReadCounter(int readCounter) {
            this.readIndex.position(READ_CNT_OFFSET);
            this.readIndex.putInt(readCounter);
            this.readCounter = readCounter;
        }
 
        public void reset() {
            int size = writeCounter - readCounter;
            putReadCounter(0);
            putWriteCounter(size);
            if (size == 0 && readNum == writeNum) {
                putReadPosition(0);
                putWritePosition(0);
            }
        }
 
        public void sync() {
            if (writeIndex != null) {
                writeIndex.force();
            }
        }
 
        public void close() {
            try {
                if (writeIndex == null) {
                    return;
                }
                sync();
                AccessController.doPrivileged(new PrivilegedAction<Object>() {
                    public Object run() {
                        try {
                            Method getCleanerMethod = writeIndex.getClass().getMethod("cleaner");
                            getCleanerMethod.setAccessible(true);
                            sun.misc.Cleaner cleaner = (sun.misc.Cleaner) getCleanerMethod.invoke(writeIndex);
                            cleaner.clean();
                        } catch (Exception e) {
                            log.error("close fqueue index file failed", e);
                        }
                        return null;
                    }
                });
                writeIndex = null;
                readIndex = null;
                fileChannel.close();
                indexFile.close();
            } catch (IOException e) {
                log.error("close fqueue index file failed", e);
            }
        }
    }
 
    private static class MFQueueBlock {
 
        private static final String BLOCK_FILE_SUFFIX = ".blk"; // 数据文件
        private static final int BLOCK_SIZE = 32 * 1024 * 1024; // 32MB
 
        private final int EOF = -1;
 
        private String blockFilePath;
        private MFQueueIndex index;
        private RandomAccessFile blockFile;
        private FileChannel fileChannel;
        private ByteBuffer byteBuffer;
        private MappedByteBuffer mappedBlock;
 
        public MFQueueBlock(String blockFilePath, MFQueueIndex index, RandomAccessFile blockFile, FileChannel fileChannel,
                            ByteBuffer byteBuffer, MappedByteBuffer mappedBlock) {
            this.blockFilePath = blockFilePath;
            this.index = index;
            this.blockFile = blockFile;
            this.fileChannel = fileChannel;
            this.byteBuffer = byteBuffer;
            this.mappedBlock = mappedBlock;
        }
 
        public MFQueueBlock(MFQueueIndex index, String blockFilePath) {
            this.index = index;
            this.blockFilePath = blockFilePath;
            try {
                File file = new File(blockFilePath);
                this.blockFile = new RandomAccessFile(file, "rw");
                this.fileChannel = blockFile.getChannel();
                this.mappedBlock = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, BLOCK_SIZE);
                this.byteBuffer = mappedBlock.load();
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
 
        public MFQueueBlock duplicate() {
            return new MFQueueBlock(this.blockFilePath, this.index, this.blockFile, this.fileChannel,
                    this.byteBuffer.duplicate(), this.mappedBlock);
        }
 
        public static String formatBlockFilePath(String queueName, int fileNum, String fileBackupDir) {
            return fileBackupDir + File.separator + String.format("fblock_%s_%d%s", queueName, fileNum, BLOCK_FILE_SUFFIX);
        }
 
        public String getBlockFilePath() {
            return blockFilePath;
        }
 
        public void putEOF() {
            this.byteBuffer.position(index.getWritePosition());
            this.byteBuffer.putInt(EOF);
        }
 
        public boolean isSpaceAvailable(int len) {
            int increment = len + 4;
            int writePosition = index.getWritePosition();
            return BLOCK_SIZE >= increment + writePosition + 4; // 保证最后有4字节的空间可以写入EOF
        }
 
        public boolean eof() {
            int readPosition = index.getReadPosition();
            return readPosition > 0 && byteBuffer.getInt(readPosition) == EOF;
        }
 
        public int write(byte[] bytes) {
            int len = bytes.length;
            int increment = len + 4;
            int writePosition = index.getWritePosition();
            byteBuffer.position(writePosition);
            byteBuffer.putInt(len);
            byteBuffer.put(bytes);
            index.putWritePosition(increment + writePosition);
            index.putWriteCounter(index.getWriteCounter() + 1);
            return increment;
        }
 
        public byte[] read() {
            byte[] bytes;
            int readNum = index.getReadNum();
            int readPosition = index.getReadPosition();
            int writeNum = index.getWriteNum();
            int writePosition = index.getWritePosition();
            if (readNum == writeNum && readPosition >= writePosition) {
                return null;
            }
            byteBuffer.position(readPosition);
            int dataLength = byteBuffer.getInt();
            if (dataLength <= 0) {
                return null;
            }
            bytes = new byte[dataLength];
            byteBuffer.get(bytes);
            index.putReadPosition(readPosition + bytes.length + 4);
            index.putReadCounter(index.getReadCounter() + 1);
            return bytes;
        }
 
        public void sync() {
            if (mappedBlock != null) {
                mappedBlock.force();
            }
        }
 
        public void close() {
            try {
                if (mappedBlock == null) {
                    return;
                }
                sync();
                AccessController.doPrivileged(new PrivilegedAction<Object>() {
                    public Object run() {
                        try {
                            Method getCleanerMethod = mappedBlock.getClass().getMethod("cleaner");
                            getCleanerMethod.setAccessible(true);
                            sun.misc.Cleaner cleaner = (sun.misc.Cleaner) getCleanerMethod.invoke(mappedBlock);
                            cleaner.clean();
                        } catch (Exception e) {
                            log.error("close fqueue block file failed", e);
                        }
                        return null;
                    }
                });
                mappedBlock = null;
                byteBuffer = null;
                fileChannel.close();
                blockFile.close();
            } catch (IOException e) {
                log.error("close fqueue block file failed", e);
            }
        }
    }
 
}
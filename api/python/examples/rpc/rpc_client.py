#encoding=utf8
import sys 
sys.path.append('../../')

from zbus import RemotingClient, Rpc, Message
client = RemotingClient(broker='127.0.0.1:15555')

rpc = Rpc(client=client, mq='MyRpc') 

msg = Message()
msg.set_body('hello?')
print rpc.invoke(msg) 

client.close()




package org.zbus.rpc.simple;

import org.remoting.Message;
import org.remoting.RemotingClient;
import org.remoting.ticket.ResultCallback;
import org.zbus.client.rpc.Rpc;

public class RpcAsync {

	public static void main(String[] args) throws Exception {  
		final RemotingClient client = new RemotingClient("127.0.0.1", 15555);
		
		Rpc rpc = new Rpc(client, "MyRpc");  
		
		for(int i=0;i<10;i++){
			Message req = new Message(); 
			req.setBody("hello from client "+i); 
			
			rpc.invokeAsync(req, new ResultCallback() {
				@Override
				public void onCompleted(Message result) { 
					System.out.println(result);
				}
			}); 
		}
		
		System.out.println("--done--");
	}
}
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.lang3.time.StopWatch;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class WaitSubRelay2 implements Runnable{

	String id;
	String host;
	int port;
	DataOutputStream clientOutput;
	DataInputStream clientInput;
	DataOutputStream out;
	DataInputStream in;
	JSONObject subscribeRequest;
	int relayHitCounter;
	boolean isUnsubscribe = false;
	boolean isSecurePort;
	
	public WaitSubRelay2(JSONObject subscribeRequest, String host, int port, 
			DataOutputStream clientOut,String id, int relayHitCounter, DataInputStream clientInput, boolean isSecurePort) {
		this.subscribeRequest = subscribeRequest;
		this.host = host;
		this.id = id;
		this.clientOutput = clientOut;
		this.clientInput = clientInput;
		this.relayHitCounter = relayHitCounter;
		this.isSecurePort = isSecurePort;
	}
	
	@Override
	public void run() {
		Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
		
		//这里也使用IsSubscribe来监听unsubscribe
		ExecutorService executorService = Executors.newFixedThreadPool(1);
		Future<Boolean> unsubscribe = executorService.submit(new IsSubscribe(clientInput, id));
		
		int hitCount = 0;
		

			try {
				//set server socket to connect to secure server
				if(isSecurePort){
					System.setProperty("java.net.ssl.trustStore", "clientKeystore/aGreatName");
					SSLSocketFactory sslSocketFactory = (SSLSocketFactory)SSLSocketFactory.getDefault();
					SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(host, port);
					out = new DataOutputStream(sslSocket.getOutputStream());
					in = new DataInputStream(sslSocket.getInputStream());
					
				}else{
					//unsecure connection
					Socket socket = new Socket(host,port);
					out = new DataOutputStream(socket.getOutputStream());
					in = new DataInputStream(socket.getInputStream());
				}
				
				
				
				
				out.writeUTF(subscribeRequest.toJSONString());
				out.flush();
				
				StopWatch swatch = new StopWatch();
				
				
				/*给其他服务器发unsubscribe命令后， 取出resultSize， 
				 * 否则只是正常的将从其他服务器接收到的资源传送给client*/
				while(true){

					//监听unsubscribe命令
					isUnsubscribe = unsubscribe.get();
					if (isUnsubscribe==true) {
						JSONObject UnsubJSONObject = new JSONObject();
						UnsubJSONObject.put("command", "UNSUBSCRIBE");
						UnsubJSONObject.put("id", id);
						out.writeUTF(UnsubJSONObject.toJSONString());
					}
					
					//input > 0
					if (in.available()>0) {
						JSONParser parser = new JSONParser();
						JSONObject message = (JSONObject) parser.parse(in.readUTF());
						
						if(!message.containsKey("resultSize")){
							clientOutput.writeUTF(message.toJSONString());
							clientOutput.flush();
						}else{
							hitCount = Integer.parseInt(message.get("resultSize").toString());
							relayHitCounter = relayHitCounter+hitCount;
							break;
						}
						
						
					}
					
					//input = 0 go back to the while(ture) loop
				}
				
				
				Thread.yield();
			
				
			
			} catch (Exception e) {
				e.printStackTrace();
			}
	

		
	}
}
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class Subscirble {
	private HashMap<String, Resource> lastState;
	private HashMap<String, Resource> resources;
	private boolean updated = false;
	DataInputStream in;
	DataOutputStream out;
	boolean hasDebugOption;
	ArrayList<JSONObject> matchList;
	int hitCounter;
	
	public Subscirble(HashMap<String, Resource> resources,DataInputStream in,DataOutputStream out,boolean hasDebugOption){
		this.resources = resources;
		this.lastState = new HashMap<>(resources);
		this.in = in;
		this.out = out;
		this.hasDebugOption = hasDebugOption;
		this.matchList = new ArrayList<>();
		int hitCounter = 0;
	}
	
	
	public synchronized static void handlingSubscribleTest(JSONObject input,DataInputStream in,DataOutputStream out,
			ServerSocket socket,HashMap<String, Resource> resources,String hostName, boolean hasDebugOption){
		JSONObject template_resource_sub = (JSONObject)input.get("resourceTemplate");
		JSONArray debugMsg_sub = new JSONArray();
		boolean relay;
		boolean isUnsubscribe =false;

		
		String [] tags = ServerThread.handleTags(template_resource_sub.get(ConstantEnum.CommandArgument.tags.name()).toString());
		String name = template_resource_sub.get(ConstantEnum.CommandArgument.name.name()).toString();
		String description = template_resource_sub.get(ConstantEnum.CommandArgument.description.name()).toString();
		String uri = template_resource_sub.get(ConstantEnum.CommandArgument.uri.name()).toString();
		String channel = template_resource_sub.get(ConstantEnum.CommandArgument.channel.name()).toString();
		String owner = template_resource_sub.get(ConstantEnum.CommandArgument.owner.name()).toString();
		String relay_sub= input.get(ConstantEnum.CommandArgument.relay.name()).toString();
		String id = input.get(ConstantEnum.CommandArgument.id.name()).toString();
		if(relay_sub.equals("")){
			relay = true;
		}else{
			relay = Boolean.parseBoolean(relay_sub);
		}
		
		//create new callabe thread to monitor unsubscribe status
		ExecutorService executorService = Executors.newFixedThreadPool(1);
		Future<Boolean> unsubscribe = executorService.submit(new IsSubscribe(in, id));
		
		//loop until receive unsubscribe message
		while(isUnsubscribe==false){
			
			if (relay ==false) {
				QueryReturn queryReturn= ServerHandler.handlingSubscribe(id, name, tags, description, uri, channel, owner, relay, resources, socket, hostName);
				Subscirble subscirble = new Subscirble(resources,in,out,hasDebugOption);
				
				//invalid template or valid template but no current match, pending.
				if (queryReturn.hasMatch==false) {
					
					//invalid template
					if (queryReturn.reponseMessage.get("response").toString().equals("error")) {
						subscirble.sendMessage(queryReturn.reponseMessage);
						break;
					}else{
						//valid template, but no match currently, pending
						if (queryReturn.reponseMessage.get("response").toString().equals("pending")) {
							subscirble.checkUpdates(id, name, tags, description, uri, channel, owner, relay,socket, hostName);
							System.out.println("pending!!");
						}
					}
				}else{
					//valid template, has match, monitor resources update.
	
						for(JSONObject jsonObject: queryReturn.returnList){
							try {
								out.writeUTF(jsonObject.toJSONString());
								
								//put it in the match list to avoid duplicate resource has been sent
								subscirble.matchList.add(jsonObject);
							} catch (IOException e) {
								e.printStackTrace();
							}
							
						}
						subscirble.checkUpdates(id, name, tags, description, uri, channel, owner, relay,socket, hostName);
					
				}
				
				
				//if unsubscribe, break the loop, return result size
				try {
					isUnsubscribe = unsubscribe.get();
					if (isUnsubscribe) {
						//remove the {"id":xxx} or {"resposne":"success"}
						subscirble.matchList.remove(0);
						
						JSONObject jsonObject = new JSONObject();
						System.out.println(subscirble.matchList.size());
						jsonObject.put("resultSize", subscirble.matchList.size());
						out.writeUTF(jsonObject.toJSONString());		
						out.flush();
						Thread.currentThread().yield();
						break;
					}
				} catch (Exception e) {
					
				}
				
			}else{
				
				
			}
			
			
			
			
			
		}
		
		
		
		
		
	}
	
	
	/**
	 * monitor the status of the resources hashmap, if has changed and match resource template. write output.
	 * */
	public void checkUpdates(String id, String name,String[] tags,String description,String uri,String channel,String owner,
			boolean relay,ServerSocket socket,String hostName){
		new Timer().schedule(new TimerTask() {
			
			@Override
			public void run() {
			checkUpdated(id, name, tags, description, uri, channel, owner, relay, socket, hostName);				
			}
		}, 1000, 1000);
	}
	
	private boolean checkUpdated(String id, String name,String[] tags,String description,String uri,String channel,String owner,
			boolean relay,ServerSocket socket,String hostName) {
	   
		if(!this.resources.equals(this.lastState)){
			System.out.println("updated");
			QueryReturn temp = ServerHandler.handlingSubscribe(id, name, tags, description, uri, channel, owner, relay, this.resources, socket, hostName);
			if (temp.hasMatch==true) {
				for(JSONObject jsonObject : temp.returnList){
					
					if (matchList.isEmpty()) {
						try {
							this.out.writeUTF(jsonObject.toJSONString());
							this.hitCounter++;
						} catch (IOException e) {
							e.printStackTrace();
						}
						matchList.add(jsonObject);
					}else{
						if(!matchList.contains(jsonObject)){
							try {
								this.out.writeUTF(jsonObject.toJSONString());
								this.hitCounter++;
							} catch (IOException e) {
								e.printStackTrace();
							}
							matchList.add(jsonObject);
						}
					}					
				}
			}
		}
		
	    this.lastState.clear();
	    this.lastState = new HashMap<>(this.resources);
	    return this.updated;
	}
	
	/**
	 * This method sends response message from the server to client
	 * @param message
	 */
	public synchronized void sendMessage(JSONObject message){
		try {
			
			out.writeUTF(message.toJSONString());
			out.flush();
			if(hasDebugOption){
			       System.out.println("SENT: "+message.toJSONString());
				}
			System.out.println(Thread.currentThread().getName()+":sending response message!");
			
		} catch (IOException e) {
			System.err.println(Thread.currentThread().getName() + ":Error while sending");
		}
	}
	
	
}
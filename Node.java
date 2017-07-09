/**
 *  Asynchronous Broadcast Message Service
 */
package Project;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.ArrayList;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.nio.sctp.*;

/*
 * Main Class Node 
 */
public class Node {

	Object buf_lock,phase_lock,broad_cast_converge_cast_lock;
	int port,num_nodes,node_id,num_ack = 0,num_nack = 0,parent = -1,converge_cast_ack_counter = 0;
	static int num_broadcast,delay;
	boolean spanningTreePhase = true, isLeaf = false;
	boolean connection_established = false;
	boolean explore_message_received = false,converge_cast_ack_sent = false,converge_cast_received = false;
	
	HashMap<String,String> hostMap;
	HashMap<Integer,Neighbor> neighborMap;
	HashSet<Integer> spanning_tree_children ;
	ArrayList<Neighbor> neighbors ;
	
	/*
	 * Broadcast phase variables
	 */
	ConcurrentHashMap<Integer,Integer> parentMap,sumMap,convergeCastMap;
	ConcurrentHashMap<Integer,HashSet<Integer>> childrenMap;
	ConcurrentHashMap<Integer,Boolean> converge_cast_recieved_MAP ;
	
	int broad_cast_convergecast_counter = 0;
	boolean broad_cast_convergecast_received = false;
	boolean broad_cast_cc = true;
	boolean broad_cast_cc_initiator_received = false;
	HashSet<Integer> broadcast_neighbors;
	//int num_broadcasts = 0;
	
	
	Logger logger,sum_logger;
	FileHandler fh,fs;
	
	/*
	 * Initialize all variables 
	 * Extract neighbor details from script
	 * start all client servers 
	 * start the server
	 */
	public Node(int num_nodes, int node_id, int port,String host_names,String neighbor_hosts,
			String neighbor_ports,String neighbor_ids) throws SecurityException, IOException, InterruptedException {
		
		neighbors = new ArrayList<>();
		spanning_tree_children = new HashSet<>();
		broadcast_neighbors = new HashSet<>();
		sumMap = new ConcurrentHashMap<>();
		childrenMap = new ConcurrentHashMap<>();
		parentMap = new ConcurrentHashMap<>();
		convergeCastMap = new ConcurrentHashMap<>();
		converge_cast_recieved_MAP = new ConcurrentHashMap<>();
		
		this.num_nodes = num_nodes;
		this.node_id = node_id;
		this.port = port;
		buf_lock = new Object();
		phase_lock = new Object();
		broad_cast_converge_cast_lock = new Object();
		
		fh = new FileHandler("/home/012/s/sx/sxn164530/Project/Logs/logs"+node_id+".log");
		fs = new FileHandler("/home/012/s/sx/sxn164530/Project/Logs/Sums/logs"+node_id+".log");
		
		SimpleFormatter formatter = new SimpleFormatter();
		fh.setFormatter(formatter);
		fs.setFormatter(formatter);
		sum_logger = Logger.getLogger("SumLogger");
		sum_logger.setLevel(Level.INFO);
		sum_logger.addHandler(fs);
		
		logger = Logger.getLogger("MyLog");
		logger.setLevel(Level.INFO);
		logger.addHandler(fh);
		extractNeighbors(neighbor_hosts,neighbor_ports,neighbor_ids);
	
		logger.info("My node is"+node_id);
		logger.info("My port number is"+port);
					
		//SctpChannel channel = SctpChannel.open(addr,0,0);
		
		// Create server object and run the thread to start processing incoming requests
		Server server = new Server();
		Thread startServer = new Thread(server);
		startServer.start();
		Thread.sleep(2000);
		openAllConnections();
		//closeHandler();
		
	}
	
	public void openAllConnections() throws InterruptedException{
		
		
		for(Neighbor neighbor:neighbors){
			boolean connection_established = false;
			while(!connection_established){
				try {
					neighbor.clientChannel = SctpChannel.open();
					neighbor.clientChannel.connect(neighbor.serverAddress);
					connection_established = true;
				} catch (IOException e) {
					Thread.sleep(2000);
				}
				
			} 
			
			//logger.info("Connected to server" + neighbor.node_id);
			
		}
		
		connection_established = true;
		//logger.info("Connection to all my neighbors have been established");
	}
	
	/*
	 * close all log files after a specific time
	 */
	public void closeHandler(){
		
		logger.info("closing handles");
		TimerTask task = new TimerTask(){

			@Override
			public void run() {
				// TODO Auto-generated method stub
				logger.removeHandler(fh);
				
			}
			
		};
		
		Timer timer = new Timer("Timer");
		long delay = 5000L;
		timer.schedule(task, delay);
		
	}
	
	public void setSpanningTreePhase(boolean val){
		spanningTreePhase = val;
	}
	
	/*
	 * Extract neighbors from machine and port string and store them in a hashmap
	 */
	public void extractNeighbors(String neighbor_hosts, String neighbor_ports,
			String neighbor_ids) throws IOException{
		
		String hostSplit [] = neighbor_hosts.split(",");
		String portSplit [] = neighbor_ports.split(",");
		String idSplit [] = neighbor_ids.split(",");
		hostMap = new HashMap<>();
		neighborMap = new HashMap<>();
		
	//	logger.info("neighbor ids "+neighbor_ids);
		int counter = 0;
		for(String host : hostSplit){
			hostMap.put(host, portSplit[counter]);
			SctpServerChannel server = null;
			
			InetSocketAddress serverAddress = new InetSocketAddress(Integer.parseInt(portSplit[counter]));
			
			Neighbor neighbor = new Neighbor(server,host,
					Integer.parseInt(idSplit[counter]),Integer.parseInt(portSplit[counter]));
			neighbors.add(neighbor);
			neighborMap.put(Integer.parseInt(idSplit[counter]), neighbor);
			counter++;
			
		}
		//logger.info("Neighbor size" + neighbors.size());
		
	}
		
	/*
	 * Broadcast Phase updates
	 */
	private void update_Neighbors(){
		
		if(spanning_tree_children.size()>0)
			for(Integer child: spanning_tree_children){
				broadcast_neighbors.add(child);
			}
		if(parent != -1)
			broadcast_neighbors.add(parent);
	}
	private void updateSum(int rand_num,int initiator_id){
		
		if(sumMap.get(initiator_id) == null){
			sumMap.put(initiator_id, rand_num);
		}else{
			int sum = sumMap.get(initiator_id);
			sum+=rand_num;
			sumMap.put(initiator_id, sum);
		}
	}
	private void updateParent(int initiator_id,int sender_id){
		parentMap.put(initiator_id, sender_id);
		
	}
	private void updateChildren(int initiator_id,int sender_id){
		
		HashSet<Integer> childrenSet = new HashSet<>();
		for(int i : broadcast_neighbors){
			if( i != sender_id){
				childrenSet.add(i);
			}
		}
		childrenMap.put(initiator_id, childrenSet);
	}
	
	private void updateConvergeCastMap(int initiator_id) {
		
		if(convergeCastMap.get(initiator_id) == null){
			convergeCastMap.put(initiator_id, 1);
		}else{
			int counter = convergeCastMap.get(initiator_id);
			counter = counter + 1;
			convergeCastMap.put(initiator_id, counter);
		}
		
		converge_cast_recieved_MAP.put(initiator_id, true);
			
	}
	
	private void clearConvergeCastMap(int initiator_id){
		convergeCastMap.put(initiator_id, 0);
		converge_cast_recieved_MAP.put(initiator_id, false);
	}
	/*
	 * Server class to constantly listen for incoming sockets
	 */
	class Server implements Runnable{

		@Override
		public void run(){

			try {
				startServer();
			} catch (IOException e) {
				e.printStackTrace();
			}	
		}
		
		public void startServer() throws IOException {
				
			SctpServerChannel server = SctpServerChannel.open();
			InetSocketAddress serverAddress = new InetSocketAddress(port);
			server.bind(serverAddress);
		
				while(true){	
				SctpChannel serverChannel = server.accept();
				logger.info("Connection established");
				IncomingChannel incoming = new IncomingChannel(serverChannel);
				Thread runIncoming = new Thread(incoming);
				runIncoming.start();
				
			}	
		}
		
	}
	/*
	 * Class to process all incoming messages
	 */
	class IncomingChannel implements Runnable{

		SctpChannel serverChannel;
		//SctpServerChannel serverChannel;
		String incomingMessage;
		int sender_id,sum_value,initiator_id,round_number;
		String message_type;
		
		public void receiveMessages() throws IOException, InterruptedException{

			while(true){				
				ByteBuffer receiveBuf = ByteBuffer.allocate(60);
				MessageInfo messageInfo = serverChannel.receive(receiveBuf,null,null);
				if(receiveBuf != null){
					synchronized (buf_lock){	
						receiveBuf.flip();
						incomingMessage = BytetoString(receiveBuf);
						String messageSplit [] = incomingMessage.split(":");
						sender_id = Integer.parseInt(messageSplit[0]);
						message_type = messageSplit[1].trim();
						//Check if message contains sum_value
						
						//logger.info("message type is " + message_type);
						//logger.info("Incoming message is "+incomingMessage);
						processMessages();
						buf_lock.notifyAll();
					}
				}
			}
		}
		
		public IncomingChannel(SctpChannel serverChannel) throws IOException{
			this.serverChannel = serverChannel;
		}
		
		
		@Override
		public void run() {
		
			try {
				receiveMessages();
			} catch (IOException | InterruptedException e) {
				
				e.printStackTrace();
			}
			
		}
		
		 public void processMessages() throws IOException, InterruptedException{
		
			//logger.info("I am now in incoming run");

			if(spanningTreePhase){
				if(message_type.equals("EXPLORE")){
			
					if(!explore_message_received){
						parent = sender_id;
						explore_message_received = true;
						logger.info("Explore message received from "+ sender_id);
						try {
							sendSpanningTreeMessages("ALL");
							sendSpanningTreeMessages(null,parent,"ACK");
						} catch (InterruptedException e ) {
					
							e.printStackTrace();
					
						}
					}else{
					//send NACK
						//logger.info("Explore message already received so sending nack");
						try {
							sendSpanningTreeMessages(null,sender_id,"NACK");
							} catch (InterruptedException e) {
							// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}		
					}
			
		
				if(message_type.equals("ACK")){
			
					//logger.info("!!! Ack message received from " + sender_id);
					spanning_tree_children.add(sender_id);
					for(Integer i: spanning_tree_children){
						logger.info("My children"+Integer.toString(i));
					}
					num_ack++;
				}
	
				if(message_type.equals("NACK")){
					//logger.info("NACK message receieved from" + sender_id);
					num_nack++;
				}
			
				if(message_type.equalsIgnoreCase("ConvergeCastACK")){
					converge_cast_ack_counter++;
				}
			
				if(message_type.contains("SpanningTreeDONE")){

				
					//logger.info("Spanning Tree Completion message received");
					
					if(spanning_tree_children.size() == 0)
						isLeaf = true;
					if(!isLeaf)
						sendBroadCastMessages("SpanningTree",node_id,-1,"SpanningTreeDONE",round_number);
					Thread start_broadcast = new Thread(new Runnable() {
				
						@Override
						public void run() {
							try {
								initiateBroadcast(num_broadcast, delay);
							} catch (IOException e) {
								e.printStackTrace();
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
					
						}
					});
					update_Neighbors();
					Thread.sleep(6000);
					
					synchronized (phase_lock){
						spanningTreePhase = false;
						phase_lock.notify();
					}
					//start_broadcast.start();
				}
		
			
			//Converge cast for spanningTree
				if((spanningTreePhase) && (converge_cast_ack_sent == false))
					check_for_convergecast("SpanningTree");

			}
			
			/*
			 * BroadCast messages
			 */
			else if(!spanningTreePhase){
				
				String message_split[] = message_type.split("-");
				initiator_id = Integer.parseInt(message_split[1]);
				String split [] = incomingMessage.trim().split(":");
				sum_value = Integer.parseInt(split[2]);
				round_number = Integer.parseInt(split[3]);
				
				if(!message_type.contains("BroadCastCC")){
					
					logger.info("Incoming message for broadcast is " + incomingMessage.trim());
					
					//sum_value = Integer.parseInt(sum_find[2]);
					updateParent(initiator_id,sender_id);
					updateChildren(initiator_id,sender_id);
					updateSum(sum_value,initiator_id);
					sum_logger.info("Sum value for initiator: "+Integer.toString(initiator_id)+" is: "+sumMap.get(initiator_id)+
							" for round: "+round_number+"\n");
				
					if(message_type.contains("Initiator")){
						//logger.info("Message type is initiator going into sendBroadcast function");
						sendBroadCastMessages("Normal-Broadcast",sum_value,initiator_id,"NormalBroadcast",round_number);
					}
					
					if(message_type.contains("NormalBroadcast")){
						//logger.info("Message type is Normal Broadcast going into sendBroadcast function");
						sendBroadCastMessages("Normal-Broadcast",sum_value,initiator_id,"NormalBroadcast",round_number);
					}
					
				}
				// If message type is broad cast converge cast 		
				if(message_type.contains("BroadCastCC")){
					logger.info("message type is"+ message_type + " initiator id is "+ initiator_id);
					updateConvergeCastMap(initiator_id);
				}
				
				// Keep checking for converge cast once a message is received
				check_for_convergecast("BroadCast");
			}
		}
		 
	

		 public void check_for_convergecast(String phase) throws IOException, InterruptedException{
				
			if(phase.equalsIgnoreCase("SpanningTree")){
	
					if((num_nack == neighbors.size()-1)&&(node_id != 0)){
						logger.info("Sending convergeCast from leaf node");
						sendSpanningTreeMessages(null,parent,"ConvergeCastACK");
						converge_cast_ack_sent = true;
						
					}
					else if(((num_nack + converge_cast_ack_counter) == neighbors.size()-1)&&(node_id != 0)){
					
						logger.info("intermediate nodes sending converge cast");
						sendSpanningTreeMessages(null,parent,"ConvergeCastACK");
						
					}
					else if(((num_nack + converge_cast_ack_counter) == neighbors.size())&&(node_id == 0)){
					
					
						logger.info("Spanning Tree phase done");
						sendBroadCastMessages("SpanningTree",node_id,-1, "SpanningTreeDONE",-1);
						update_Neighbors();
						Thread.sleep(13000);
						synchronized (phase_lock){
							spanningTreePhase = false;
							phase_lock.notifyAll();
						}
						Thread start_broadcast = new Thread(new Runnable() {
	
							@Override
							public void run() {
								try {
									initiateBroadcast(num_broadcast, delay);
								} catch (IOException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								} catch (InterruptedException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
									
							}
						});		
						//start_broadcast.start();
					}
			}else{	//Check broadcast converge cast conditions
				
				//logger.info("Entered converge cast broadcast section");
					if((broadcast_neighbors.size() == 1 && broadcast_neighbors.contains(sender_id)) && (node_id != initiator_id)){
						logger.info("I am the leaf for" + Integer.toString(initiator_id));
						clearConvergeCastMap(initiator_id);
						sendBroadCastMessages("Converge-Cast", -1, initiator_id, "BroadCastCC",round_number);
						
					}
					else if(converge_cast_recieved_MAP.get(initiator_id) != null){
						logger.info("Entering convergecast section message type is " + message_type);
						if((broadcast_neighbors.size()-1 == convergeCastMap.get(initiator_id))&& (node_id != initiator_id)){
							clearConvergeCastMap(initiator_id);
							logger.info("intermediate node sending broadcast converge cast to parent"+ Integer.toString(sender_id)+"\n");
							sendBroadCastMessages("Converge-Cast", -1, initiator_id, "BroadCastCC",round_number);
							
						}
					else if((broadcast_neighbors.size() == convergeCastMap.get(initiator_id))&&(node_id == initiator_id)){
									
						logger.info("Converge cast for broadcast round:"+round_number+" is done\n");
						clearConvergeCastMap(initiator_id);
						synchronized (broad_cast_converge_cast_lock){
							broad_cast_cc_initiator_received = true;
							broad_cast_converge_cast_lock.notify();
						}
					}	
				}
			}
		}
	}

	/*
	 * Neighbor class to store all neighbor node information
	 */
	class Neighbor{
		
		SctpServerChannel serverChannel;
		SctpChannel clientChannel;
		String host_name;
		int port_number;
		int node_id;
		InetSocketAddress serverAddress;
		String IPAddress;
		
		public Neighbor(SctpServerChannel serverChannel,String host_name,int node_id,int port) throws UnknownHostException{
			this.serverChannel = serverChannel;
			this.host_name = host_name;
			this.port_number = port;
			this.node_id = node_id;
			serverAddress = new InetSocketAddress(InetAddress.getByName(host_name),port_number);
			IPAddress = InetAddress.getByName(host_name).toString();
			
		}
		public Neighbor(int node_id){
			this.node_id = node_id;
		}
		
	}
	
	/*
	 * prepare spanning tree and broadcast messages
	 */
	public String prepareSpanningTreeMessage(int sender_id,String message_type){
		String s_id = Integer.toString(sender_id);
		
		return s_id+":"+message_type;
	}
	
	public String prepareBroadcastMessage(String sender_id,String message_type,String sum_value,
			String initiator_id,String round_number){
		
		//logger.info("Random value is " + sum_value);
		//logger.info("sender id is "+ sender_id);
		String message = "";
		message+=sender_id+":"+message_type+"-"+initiator_id+":"+sum_value+":"+round_number;
		//logger.info("Message is " + message);
		return message;
	}
	
	/*
	 * Convert byte buffer to string
	 */
	public String BytetoString(ByteBuffer buffer){
		
		String s = new String(buffer.array());
		return s;
	}
	
	/*
	 * Send all messages to neighbors through this function
	 */
	public void sendSpanningTreeMessages(Object...args) throws  InterruptedException{
		
		if(args[0] != null){
			
			String message = "";
			if(args[0].toString().equalsIgnoreCase("ALL")){
				message = prepareSpanningTreeMessage(node_id,"EXPLORE");
				
			}
		
			for(Neighbor neighbor : neighbors){
				if(neighbor.node_id!=parent){
					ByteBuffer sbuf = ByteBuffer.allocate(60);
					//logger.info("Sending explore messages to "+neighbor.node_id);
					sbuf.put(message.getBytes());
					sbuf.flip();
					MessageInfo messageInfo = MessageInfo.createOutgoing(null,0);	
					try {
						neighbor.clientChannel.send(sbuf, messageInfo);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
		
		if(args[0] == null){
			//args[1] = parent id/one who sent EXPLORE //args[2] = message to parent
			
			ByteBuffer sbuf = ByteBuffer.allocate(60);
			String message = prepareSpanningTreeMessage(node_id,args[2].toString());
			SctpChannel sc = neighborMap.get((int)args[1]).clientChannel;
			sbuf.put(message.getBytes());
			sbuf.flip();
			MessageInfo messageInfo = MessageInfo.createOutgoing(null, 0);
			
			try {
				sc.send(sbuf, messageInfo);
			} catch (IOException e) {
				logger.info("Failed to send acknowledge/reject for" + node_id);
				e.printStackTrace();
			}
			
		}
		
	}
	
	/*
	 * send all broadcast messages to children through this function
	 */
	
	synchronized public void sendBroadCastMessages(String broad_cast_message,int sum_value,int initiator_id,
			String message_type,int round_number){
		
		String message = "";
		
		if(broad_cast_message.equalsIgnoreCase("Initiator-Broadcast")||broad_cast_message.equalsIgnoreCase("Normal-Broadcast")){
				
			if(broad_cast_message.equalsIgnoreCase("Normal-Broadcast"))	
				logger.info("Normal Broadcast message received");
			message = prepareBroadcastMessage(Integer.toString(node_id),message_type,Integer.toString(sum_value),Integer.toString(initiator_id),
					Integer.toString(round_number));
			for(Integer neighbor : broadcast_neighbors){
				
				ByteBuffer sbuf = ByteBuffer.allocate(60);
				sbuf.put(message.getBytes());
				sbuf.flip();
				MessageInfo messageInfo = MessageInfo.createOutgoing(null,0);	
				try {
					if(message_type.equals("NormalBroadcast")){
						int parent = parentMap.get(initiator_id);
						logger.info("Parent is " +Integer.toString(parent));
						if(neighbor != parent ){
							logger.info("Sending normal broadcast message to"+ neighbor);
							neighborMap.get(neighbor).clientChannel.send(sbuf, messageInfo);
						}
					}else{
						logger.info("I am the initiator sending broadcast messages to " + neighbor);
						neighborMap.get(neighbor).clientChannel.send(sbuf, messageInfo);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			
			}
		}
			
			if(broad_cast_message.equalsIgnoreCase("Converge-Cast")){
				logger.info("Sending Converge Cast to parent"+ " Intitiator phase is "+ Integer.toString(initiator_id));
				message = prepareBroadcastMessage(Integer.toString(node_id),message_type,Integer.toString(sum_value),
						Integer.toString(initiator_id),Integer.toString(round_number));
				
				int parent = parentMap.get(initiator_id);
				ByteBuffer sbuf = ByteBuffer.allocate(60);
				sbuf.put(message.getBytes());
				sbuf.flip();
				MessageInfo messageInfo = MessageInfo.createOutgoing(null,0);
				try {
					neighborMap.get(parent).clientChannel.send(sbuf, messageInfo);
				} catch (IOException e) {
					e.printStackTrace();
				}
				
			}
			if(broad_cast_message.equalsIgnoreCase("SpanningTree")){
				logger.info("Sending SpanningTree Completion to children");
				message = prepareBroadcastMessage(Integer.toString(node_id),message_type,"-1",
						Integer.toString(initiator_id),Integer.toString(round_number));
				for(Integer child : spanning_tree_children){
					ByteBuffer sbuf = ByteBuffer.allocate(60);
					sbuf.put(message.getBytes());
					sbuf.flip();
					MessageInfo messageInfo = MessageInfo.createOutgoing(null,0);	
					try {
						neighborMap.get(child).clientChannel.send(sbuf, messageInfo);
					} catch (IOException e) {
						e.printStackTrace();
					}
			
				}
			}	
		}
	
	
	
	/*
	 * Root node initiates spanning tree and broadcast phases
	 */
	public void initiateSpanningTree() throws IOException, InterruptedException{
		
		/*Client initiateClient = new Client();
		Thread initiateMessageThread = new Thread(initiateClient);
		initiateMessageThread.start();
		*/
		logger.info("Sending messages from root");
		sendSpanningTreeMessages("ALL");
		
	}
	/*
	 * Method to initiate broadcast phase
	 */
	public void initiateBroadcast(int num_broadcast,int delay) throws IOException, InterruptedException{
		
		int counter = 1;
		Random random = new Random();
		logger.info("broadcast neighbors");
		for(int i : broadcast_neighbors){
			logger.info(Integer.toString(i));
		}
	
		while(counter <= num_broadcast){
			int sum_value = 0;
			if(counter == 1){
				sum_value = random.nextInt(100);
				logger.info("Initiating broad cast round"+ counter);
				sendBroadCastMessages("Initiator-Broadcast",sum_value,node_id,"Initiator",counter);
				updateSum(sum_value, node_id);
				sum_logger.info("Sum value for initiator "+node_id+" is:"+sumMap.get(node_id)+" for round: "+counter);
				counter++;
				
			}else{
				synchronized (broad_cast_converge_cast_lock){
					try{
						broad_cast_converge_cast_lock.wait();
					
						//Thread.sleep(delay);
						if(broad_cast_cc_initiator_received == true){
							logger.info("Inside synchronized part of the intiator function");
							sum_value = random.nextInt(100);
							sendBroadCastMessages("Initiator-Broadcast",sum_value,node_id,"Initiator",counter);
							updateSum(sum_value, node_id);
							sum_logger.info("Sum value for initiator "+node_id+" is:"+sumMap.get(node_id)+" for round: "+counter);
							counter++;
							
						}
						broad_cast_cc_initiator_received = false;
					}catch(InterruptedException e){
						Thread.sleep(2000);
					}
					broad_cast_converge_cast_lock.notify();
				}
			}
		}
			
		logger.info("Finished broadcasts");
		
	}
	
	public void check_for_spanningTree_completion() throws IOException, InterruptedException{
		
		boolean flag = false;
		//logger.info("On the verge of starting the broadcast operation");
		
		/*
		 * Wait till notification is received before starting broadcast phase
		 */
		synchronized (phase_lock){
			try{
				phase_lock.wait();
				if(spanningTreePhase == false){
					logger.info("Spanning tree finished ");
					initiateBroadcast(num_broadcast, delay);
				}
			}catch(InterruptedException e){
				Thread.sleep(4000);
			}
		}
	}
	
	@SuppressWarnings("restriction")
	public static void main(String[] args) throws IOException, SecurityException, InterruptedException {
		
		int num_nodes = Integer.parseInt(args[0]);
		int node_id = Integer.parseInt(args[1]);
		int port = Integer.parseInt(args[2]);
		String host_machine = args[3];
		String neighbor_hosts = args[4];
		String neighbor_ports = args[5];
		String neighbor_ids = args[6];
		num_broadcast = Integer.parseInt(args[7]);
		delay = Integer.parseInt(args[8]);
		Node node = new Node(num_nodes,node_id,port,host_machine,neighbor_hosts,neighbor_ports,neighbor_ids);
		
		if(node_id == 0){
			Thread.sleep(4000);
			node.initiateSpanningTree(); // root initiates message transfer to neighbors
		}
		
		/*
		 * Thread that waits until spanning tree completion notification is sent before initiating broad cast phase
		 */
		Thread thread = new Thread(new Runnable() {
			
			@Override
			public void run() {
				
				try {
					node.check_for_spanningTree_completion();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
			}
		});
		thread.start();
	}
}

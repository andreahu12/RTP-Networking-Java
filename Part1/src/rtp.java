import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Uses UDP sockets to behave like TCP
 * @author andreahu, jeffersonwang
 *
 */
public class rtp {
	// string key is IP + port
	private static ConcurrentHashMap<String, Connection> connections
		= new ConcurrentHashMap<String, Connection>(); // for demultiplexing
	private static int RECEIVE_PACKET_BUFFER_SIZE = 2048; // arbitrary value
	private static long TIMEOUT = 1000; // arbitrary milliseconds; will time out all packets if TIMEOUT = 1
	private static final int MAX_SEGMENT_SIZE = 968; //MAX_SEGMENT_SIZE = 972; 
	private static DatagramSocket socket;
    private static boolean multiplexRunning = false;

    private static LinkedBlockingQueue<DatagramPacket> synQ = new LinkedBlockingQueue<DatagramPacket>();


	/*
	 * CLASS METHODS BELOW
	 */
	
	/**
	 * Makes a server socket for the application that the clients will connect to, binds this socket to the port
	 * Only call this in the server upon startup.
     *
	 * @param port the port to listen on
	 * @return the bound socket
	 * @throws SocketException
	 */
	public static DatagramSocket listen(int port) throws SocketException {
		socket = new DatagramSocket(port);
		return socket;
	}
	

	/**
	 * Establishes an RTP connection using TCP's 3-way Handshake.<br>
	 * Makes a connection object on client side. <br>
	 * Assigns window size to the connection.
     *
     * @param serverIP address of the server to connect to
     * @param serverPort port of the server to connect to
     * @param windowSize initial receive window size in packets of client
     * @return The created connection with the server
	 * @throws Exception 
	 */
	public static Connection connect(InetAddress serverIP, int serverPort, int windowSize) throws Exception {
		System.out.println("\n----------------- Connect --------------------");
		socket = new DatagramSocket();

        //get client addresses as string
		String clientAddressStr = socket.getLocalAddress().getHostAddress();
		String clientPortStr = String.valueOf(socket.getLocalPort());

        //check if connection established
        System.out.println("rtp.connect: checking if connection has already been established");
		if (getConnection(serverIP.getHostAddress(), String.valueOf(serverPort)) != null) {
			System.out.println("rtp.connect: Connection has already been established");
			return getConnection(clientAddressStr, clientPortStr);
		}

		try {
            (new MultiplexData()).start();

			Connection c = createConnection(socket.getLocalAddress(), socket.getLocalPort(), serverIP, serverPort);
            c.setMaxLocalWindowSize(windowSize);
			System.out.println("rtp.connect: 1. Created a connection object in hashmap");
			
			/*
			 * implementation of 3 way handshake
			 */
			
			/*
			 * HANDSHAKE 1: CLIENT --> SERVER
			 */
			
			DatagramPacket SynPacketDP = makeSynPacket(serverIP, serverPort);
			System.out.println("rtp.connect: 2. Made SYN packet");
			socket.send(SynPacketDP);
			// TODO: TIMEOUT THINGS
			c.addTimeout(calculateTimeout(), SynPacketDP, getExpectedAckNum(SynPacketDP));
			
			System.out.println("rtp.connect: 3. Sent SYN packet");

            System.out.println("rtp.connect: 4. Waiting to receive SYN ACK...");
//			DatagramPacket receivePacket = c.getAckBuffer().take(); //blocks
            // TODO: TIMEOUT THINGS
            while (c.getAckBuffer().isEmpty()) {
            	DatagramPacket timedOutPacket = c.getTimedOutPacket();
            	if (timedOutPacket != null) {
            		System.out.println("rtp.connect: CONNECTION FAILED! SYN ACK packet timed out");
            		deleteConnection(c.getRemoteAddress().getHostAddress(), String.valueOf(c.getRemotePort()));
            		Thread.currentThread().interrupt();
            		return null;
            	}
            }
            DatagramPacket receivePacket = c.getAckBuffer().take();
			c.removeTimeout(rtpBytesToPacket(receivePacket.getData()).getAckNumber());
			
			System.out.println("rtp.connect: 4.1 Received a datagram packet: " + receivePacket);
			
			boolean validPacketReceived = false;
			
			while (!validPacketReceived) { //check if syn packet
				Packet receivePacketRTP = rtpBytesToPacket(receivePacket.getData());
				if (receivePacketRTP.getSYN()) {
					System.out.println("rtp.connect: 5. Received a SYN ACK packet");
					/*
					 * HANDSHAKE 3: CLIENT --> SERVER
					 */
                    c.remoteReceiveWindowRemaining = receivePacketRTP.getRemainingBufferSize(); //flow control
                    c.ssthresh = c.remoteReceiveWindowRemaining*3/4; //congestion control
                    if(c.ssthresh < 2){
                        c.ssthresh = 2;
                    }
					DatagramPacket ack = makeHandshakeAckPacket(serverIP, serverPort, windowSize);
					System.out.println("rtp.connect: 6. Made ACK");
					// TODO: uncomment this!
					socket.send(ack);
					System.out.println("rtp.connect: 7. Sent ACK");
					validPacketReceived = true;
				} else {
					System.out.println("rtp.connect: 4.2 waiting to receive another packet");
                    receivePacket = c.getAckBuffer().take();
					System.out.println("rtp.connect: 4.3  Received: " + receivePacket);
				}
			}
			c.resetTimeouts();

			System.out.println("rtp.connect: returning " + c);
			System.out.println("rtp.connect: ----------------- end Connect --------------------\n");
			return c;
			
		} catch (Exception e) {
			System.out.println("rtp.connect: <-----------rtp.Connect Failed-------------->");
			e.printStackTrace();
			
			// remove the failed connection if necessary
			String address = serverIP.getHostAddress();
			String port = String.valueOf(serverPort);
			if (connections.containsKey(generateKey(address, port))) {
				connections.remove(address, port);
			}
			System.out.println("rtp.connect: returning " + null);
			System.out.println("rtp.connect: ----------------- end Connect --------------------\n");
			return null;
		}
	}
	
	/**
	 * Creates an ACK packet for the 3rd handshake
	 * @param serverAddress ip of server
	 * @param serverPort listening port of server
     * @param windowSize remaining Window Size
	 * @return handshake ack packet
	 */
	private static DatagramPacket makeHandshakeAckPacket(InetAddress serverAddress, int serverPort, int windowSize) {
		Packet packet3 = new Packet(false, true, false, 1, 1, null);
        packet3.setRemainingBufferSize(windowSize);
        // reassign checksum to account for remaining buffer size
        packet3.setChecksum(packet3.calculateChecksum());
		byte[] packet3bytes = packet3.packetize();
        return new DatagramPacket(packet3bytes, packet3bytes.length,
                serverAddress, serverPort);
	}
	
	/**
	 * Creates a SynAck packet for step 2 of the 3-way handshake
	 * @param clientIP ip of client
	 * @param clientPort port of client
     * @param remBufferSize remaining window size in packets
	 * @return Datagram Packet with syn and ack headeres set to 1
	 */
	private static DatagramPacket makeSynAckPacket(InetAddress clientIP, int clientPort, int remBufferSize) {
		Packet packet2 = new Packet(false, true, true, 0, 1, null);
		packet2.setRemainingBufferSize(remBufferSize);
		// reassign checksum to account for remaining buffer size
		packet2.setChecksum(packet2.calculateChecksum());
        byte[] packet2bytes = packet2.packetize();
        return new DatagramPacket(packet2bytes, packet2bytes.length, clientIP, clientPort);
	}
	
	/**
	 * Makes a SYN packet for part 1 of the 3-way handshake
	 * @param serverIP ip of server
	 * @param serverPort port of server
	 * @return Datagram Packet with syn set to 1
	 */
	private static DatagramPacket makeSynPacket(InetAddress serverIP, int serverPort) {
		Packet SynPacket = new Packet(false, false, true, 0, 0, null);
		byte[] SynPacketBytes = SynPacket.packetize();

        return new DatagramPacket(SynPacketBytes, SynPacketBytes.length,
                serverIP, serverPort);
	}
	
	/**
	 * Prints the values of FIN, SYN, and ACK
	 * @param p Packet to print the header fields for
	 */
	private static void printRtpPacketFlags(Packet p) {
		System.out.println("FIN: " + p.getFIN());
		System.out.println("SYN: " + p.getSYN());
		System.out.println("ACK: " + p.getACK());
	}
	
	/**
	 * Accepts connect() requests from a client at the server. <br>
	 * Creates a connection object on server-side. <br>
	 * Only to be called at the server.
     *
     * This is because the server wouldn't be able to identify a connected client otherwise
     *
     * Block until something is in the SYN queue and returns connection or addr
     *
     * @param remainingWindowSize the initial receive window size of the server
     * @return The connection with the client
	 */
	public static Connection accept(int remainingWindowSize) {
        if(!multiplexRunning) {
            (new MultiplexData()).start();
            multiplexRunning = true;
        }
        try { //make connection, send synack, listen for ack, return connection
            // TODO:take blocks forever, think about making it timeout
            DatagramPacket synPacket = synQ.take(); //will block until there is something to pop

            InetAddress clientAddress = synPacket.getAddress();
            int clientPort = synPacket.getPort();

            //printRtpPacketFlags(rtpReceivePacket);

            DatagramPacket synAckPacket = makeSynAckPacket(clientAddress, clientPort, remainingWindowSize);
            Connection c = createConnection(socket.getLocalAddress(), socket.getLocalPort(), clientAddress, clientPort);
            c.setMaxLocalWindowSize(remainingWindowSize);

            socket.send(synAckPacket);
            // TODO: TIMEOUT
            c.addTimeout(calculateTimeout(), synAckPacket, getExpectedAckNum(synAckPacket));
            
            while (c.getAckBuffer().isEmpty()) {
            	DatagramPacket timedOutPacket = c.getTimedOutPacket();
            	if (timedOutPacket != null) {
            		System.out.println("rtp.accept: ACK packet in 3-way handshake has timed out!");
            		return null;
            	}
            }
            DatagramPacket ack = c.getAckBuffer().take(); //blocks until it returns something
            // TODO: timeout
            c.removeTimeout(rtpBytesToPacket(ack.getData()).getAckNumber());
            
            Packet rtpAck = rtpBytesToPacket(ack.getData());

            c.remoteReceiveWindowRemaining = rtpAck.getRemainingBufferSize(); //flow ctr
            c.ssthresh = c.remoteReceiveWindowRemaining*3/4; //congestion control
            if(c.ssthresh < 2){
                c.ssthresh = 2;
            }
            System.out.println("rtp.accept: received an ACK packet, printing list of connections");
            int j = 0;
            for(Connection i: connections.values()){
                System.out.println("Connection "+j+": "+i.getRemoteAddress()+":"+i.getRemotePort());
                j++;
            }
            c.resetTimeouts();
            return c;
        } catch (Exception e1) {
            e1.printStackTrace();
        }
        return null;
	}

	
	/**
	 * Closes the RTP connection using the algorithm TCP uses. <br>
	 * Only closes the client socket. <br>
	 * Leaves the server socket open since server socket needs to stay open. <br>
	 * Removes connection from rtp connection hashmap.
	 * @throws IOException 
	 */
	public static void close(Connection c) throws Exception {
		
		if (c == null) {
			System.out.println("rtp.close: cannot close nonexistent connection");
			return;
		}
		
		InetAddress localAddress = socket.getLocalAddress();
		int localPort = socket.getLocalPort();

		InetAddress clientAddress = c.getLocalAddress();
		int clientPort = c.getLocalPort();
		
		boolean closeClientSocket = (localAddress.equals(clientAddress)) && (localPort == clientPort);
		
		// if we want to close the client socket, we need to send a packet to the server
		InetAddress destinationAddress = closeClientSocket ? c.getRemoteAddress() : c.getLocalAddress();
		int destinationPort = closeClientSocket ? c.getRemotePort() : c.getLocalPort();
			
		// implemented TCP close algorithm
		/*
		 * PART 1 of Closing
		 */
		// SEND PACKET 1: CLIENT --> SERVER
		// FIN = 1, seq = x
		
		DatagramPacket fin = makeFinPacket();
		fin.setAddress(destinationAddress);
		fin.setPort(destinationPort);
		socket.send(fin);
		System.out.println("rtp.close");
	}
	
	/**
	 * Makes a FIN packet to send to either the server or the client in the close protocol. <br>
	 * NOTE TO USER: Make sure to set the destination IP + port before sending.
	 * @return packet with a fun bit set to 1
	 */
	private static DatagramPacket makeFinPacket() {
		Packet rtpFinPacket = new Packet(true, false, false, 1, 100, null);
		byte[] rtpFinPacketBytes = rtpFinPacket.packetize();
        return new DatagramPacket(rtpFinPacketBytes, rtpFinPacketBytes.length);
	}
	
	/**
	 * Makes a FIN acknowledgement packet for closing. <br>
	 * Used by both the client and the server. <br>
	 * NOTE TO USER: make sure to set the packet destination IP + Port<br>
	 * @param packetToAck Packet with fin bit set to 1
	 * @return a FIN acknowledgement packet
	 */
	private static DatagramPacket makeFinAckPacket(Packet packetToAck) {
		int ackNumber = packetToAck.getSequenceNumber() + 1;
		int sequenceNumber = packetToAck.getAckNumber();

		Packet ack = new Packet(true, true, false, sequenceNumber, ackNumber, null);
		byte[] ackBytes = ack.packetize();

        return new DatagramPacket(ackBytes, ackBytes.length);
	}
	
	/**
	 * Converts data to a bytestream and sends packets to the other end.
     * loop:
     * Send as many packets as possible according to flow control
     * Receive entire ack buffer to update number of things that could be sent
     *
     * Sends a message. Finishes when the ack is received.
	 * @param data Message to send
     * @param connection connection to send data over
	 */
	public static void send(byte[] data, Connection connection) {
        System.out.println("rtp.send: Starting send");
		Queue<DatagramPacket> packetsToSend = convertStreamToPacketQueue(data, connection);
        int packetsToAckLeft = packetsToSend.size();
        int packetsSentButNotAcked = 0;
        int remainingPacketsToSend = packetsToSend.size();
        
        System.out.println("rtp.send: initial # of packets to send is " + packetsToSend.size());
        
        // for dups
        System.out.println("rtp.send: new message; clear receivedAckNum and receivedSeqNum in the connection");
        connection.clearReceivedAckNum();
        connection.clearReceivedSeqNum();
        
		while (packetsToAckLeft > 0) {
            //while there are packets left to ack
			
			// check for timed out packets, and resets values if necessary 
			DatagramPacket timedOutPacket = connection.getTimedOutPacket();
			if (timedOutPacket != null) { // a packet has timed out
				System.out.println("\n-----------------------------------------");
				System.out.println("rtp.send: ERROR - a packet has timed out with seq# " + 
						rtpBytesToPacket(timedOutPacket.getData()).getSequenceNumber());
				System.out.println("rtp.send: packetsToAckLeft BEFORE reset is " + packetsToAckLeft);
				//congestion window update
                int tempDebug = connection.congestionWindow;
                connection.congestionWindow /= 2;
                if (connection.congestionWindow <= 0)
                    connection.congestionWindow = 1;
                connection.isSlowStart = false;
                System.out.println("rtp.send: lost packet, Updated congestion control from "+
                        tempDebug+" to "+connection.congestionWindow);

                // go back n
				packetsToSend = getPacketsToResend(data, timedOutPacket, connection);
				connection.clearReceivedAckNum();
				
				// reset these values
				packetsToAckLeft = packetsToSend.size();
				packetsSentButNotAcked = 0;
				remainingPacketsToSend = packetsToSend.size();
				System.out.println("rtp.send: packetsToAckLeft AFTER reset is " + packetsToAckLeft);
				
				System.out.println("rtp.send: first packet in the new queue has seq# " + 
						rtpBytesToPacket(packetsToSend.peek().getData()).getSequenceNumber());
				
				// reset the timeout trackers
				connection.resetTimeouts();
				System.out.println("rtp.send: reset the timeout data structures in the connection");
				System.out.println("-----------------------------------------\n");
			}


            //send as many bytes as you can according to flow control and congestion control
            while(remainingPacketsToSend>0 && packetsSentButNotAcked<connection.remoteReceiveWindowRemaining &&
                    packetsSentButNotAcked<connection.congestionWindow){
                DatagramPacket toSend = packetsToSend.remove();
                toSend.setAddress(connection.getRemoteAddress());
                toSend.setPort(connection.getRemotePort());
                
                // adds a timeout value to the connection
                Long timeout = calculateTimeout();
                System.out.println("rtp.send: adding timeout for seq# " + rtpBytesToPacket(toSend.getData()).getSequenceNumber());
                int expectedAckNum = getExpectedAckNum(toSend);
                connection.addTimeout(timeout, toSend, expectedAckNum);
                System.out.println("rtp.send: sending a new packet, so we added a timeout " + "("+ TIMEOUT +" ms)"+ " to the connection");
                
                packetsSentButNotAcked++;
                remainingPacketsToSend--;
                try {
                    Packet tempDebug = rtpBytesToPacket(toSend.getData());
                    System.out.println("rtp.send: sending packet with seq: "+tempDebug.getSequenceNumber()
                            + " and payload: "+tempDebug.getPayloadSize());
                    socket.send(toSend);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
			while(!connection.getAckBuffer().isEmpty()){
                // ack all of them
                try {
                    DatagramPacket ack = connection.getAckBuffer().take();
                    byte[] bytes = ack.getData();
                    Packet rtpAck = rtpBytesToPacket(bytes);
                    System.out.println("rtp.send: got ack with ackNo : "+rtpAck.getAckNumber());
                    
                    if (rtpAck.getACK()) { // we received a valid ack
                        // duplicate detection is done in MultiplexData
                        packetsToAckLeft--;

                        //flow control updates
                        packetsSentButNotAcked--;
                        connection.remoteReceiveWindowRemaining = rtpAck.getRemainingBufferSize();

                        //congestion control updates
                        int tempDebug = connection.congestionWindow;
                        if(connection.isSlowStart){
                            if(connection.congestionWindow*2 >= connection.ssthresh){
                                connection.congestionWindow = connection.ssthresh;
                                connection.isSlowStart= false;
                            } else {
                                connection.congestionWindow *= 2;
                            }
                        } else {
                            connection.congestionWindow++;
                        }
//                        System.out.println("rtp.send: Got ack, updated congestion control from "+
//                                tempDebug+" to "+connection.congestionWindow);

                        // remove the timeout from the connection
//                        System.out.println("rtp.send: received ack before timeout. need to remove timeout for ack# "
//                        		+ rtpAck.getAckNumber());
                        connection.removeTimeout(rtpAck.getAckNumber());
//                        System.out.println("rtp.send: finished removing timeout for ack# " + rtpAck.getAckNumber());
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
		}
        if (connection.getLastSeqSent()+data.length+4 >= Integer.MAX_VALUE){
            connection.setLastSeqSent((connection.getLastSeqSent() + data.length + 4)-Integer.MAX_VALUE);
        } else {
            connection.setLastSeqSent(connection.getLastSeqSent() + data.length + 4); //+4 for the extra message size bytes
        }
        System.out.println("rtp.send: Ending send");
	}
	
	/**
	 * Takes in a DatagramPacket and calculates the expected ACK number. <br>
	 * For timeout checking in the receive thread. <br>
	 * Will be called in the send method.
	 * @param p packet to calculate ACK number for
	 * @return The ack number that acks the passed in packet
	 */
	private static int getExpectedAckNum(DatagramPacket p) {
		Packet rtp = rtpBytesToPacket(p.getData());
        return rtp.getSequenceNumber() + rtp.getPayloadSize();
	}
	
	/**
	 * According to GBN, we need to resend everything starting at the last failed packet. <br>
	 * This takes the orignal data, makes a queue of datagram packets, iterates through the queue,
	 * pops all the successful packets until we get to the timed out packet. <br>
	 *
	 * @param timedOutPacket the packet that timed out
	 * @return all the packets we need to resend
	 */
	private static Queue<DatagramPacket> getPacketsToResend(byte[] data, DatagramPacket timedOutPacket, Connection c) {
		Queue<DatagramPacket> q = convertStreamToPacketQueue(data, c);
		
		Packet firstPacket = rtpBytesToPacket(q.peek().getData());
		Packet rtpTimedOutPacket = rtpBytesToPacket(timedOutPacket.getData());

		while (firstPacket.getSequenceNumber() < rtpTimedOutPacket.getSequenceNumber()) {
			System.out.println("rtp.getPacketsToResend: TimedOutSeqNum = " + rtpTimedOutPacket.getSequenceNumber());
			System.out.println("rtp.getPacketsToResend: FirstPacketSeqNum = " + firstPacket.getSequenceNumber());
			q.poll(); // remove it from the q
			if (q.isEmpty()) {
				break;
			}
			firstPacket = rtpBytesToPacket(q.peek().getData());
		}
		
		return q;
	}
	
	/**
	 * Calculates when the packet should timeout.
	 * @return timeout
	 */
	private static Long calculateTimeout() {
		return System.currentTimeMillis() + TIMEOUT;
	}
	
	/**
	 * Converts a byte array into a Queue of Datagram Packets.
	 * @param origData byte stream to convert
	 * @return Queue of DatagramPackets
	 */
	private static Queue<DatagramPacket> convertStreamToPacketQueue(byte[] origData, Connection c) {
		Queue<DatagramPacket> result = new LinkedList<DatagramPacket>();
        //4 bits in front to tell the size of the message
        byte[] front = ByteBuffer.allocate(4).putInt(origData.length).array();
        byte[] data = new byte[origData.length+front.length];
        for (int i = 0; i<front.length; i++){
            data[i] = front[i];
        }
        for (int i = front.length; i<data.length; i++){
            data[i] = origData[i-front.length];
        }
        //---andrea---
		int numFullPackets = Math.floorDiv(data.length, MAX_SEGMENT_SIZE);
		int bytesRemaining = data.length % MAX_SEGMENT_SIZE;
		
		int dataIndex = 0;
		for (int packetNum = 0; packetNum < numFullPackets; packetNum++) {
			byte[] payload = new byte[MAX_SEGMENT_SIZE];
			for (int payloadIndex = 0; payloadIndex < MAX_SEGMENT_SIZE; payloadIndex++) {
				payload[payloadIndex] = data[dataIndex];
				dataIndex++;
			}
			int seqNum = packetNum * MAX_SEGMENT_SIZE;
			Packet packet = new Packet(false, false, false, seqNum + c.getLastSeqSent(), 0, payload);
			byte[] packetBytes = packet.packetize();
			
			DatagramPacket dp = new DatagramPacket(packetBytes, packetBytes.length);
			result.add(dp);
		}
		
		byte[] lastPayload = new byte[bytesRemaining];
		int payloadIndex = 0;
		
		while (dataIndex < data.length) {
			lastPayload[payloadIndex] = data[dataIndex];
			dataIndex++;
			payloadIndex++;
		}
		
		int lastSeqNum = (numFullPackets) * MAX_SEGMENT_SIZE;
		Packet packet = new Packet(false, false, false, lastSeqNum + c.getLastSeqSent(), 0, lastPayload);
		byte[] packetBytes = packet.packetize();

		DatagramPacket dp = new DatagramPacket(packetBytes, packetBytes.length);
		result.add(dp);
		return result;
	}
	
	/**
	 * Reads a specified number of bytes from the receieve buffer and return them
     *  If the requested number of bytes isn't the size of a packet, the remainder go
     *      into a remainder buffer for temporary storage. Data is pulled from here before the receive buffer
     *
	 * @param numBytesRequested the limit of the number of bytes recieve can read
     * @param c The connection to receive the data from
	 * @return number of bytes read
	 */
        public static byte[] receive(int numBytesRequested, Connection c) {
		if (c == null) { // connection does not exist yet
			System.out.println("rtp.receive: connection does not exist yet");
			return null;
		}
		if (numBytesRequested <= 0) {
			System.out.println("rtp.receive: no bytes to read");
			return null;
		}
		
        try{
            Queue<Byte> receiveRemainder = c.getReceiveRemainder(); //reference to remainder buffer

            //Step 1: if starting a new message, get the size put the rest of the packet into the remainder buffer
            if(c.remainingMessageSize == 0) { //remaining message size is 0, so we need to update it with the first packet

                //the remainder buffer has to be empty when this happens b/c of send assumptions
                DatagramPacket packet = c.getReceiveBuffer().take();
                Packet rtpPacket = rtpBytesToPacket(packet.getData());
                System.out.println("rtp.receive: got a packet with seq# " + rtpPacket.getSequenceNumber());
                sendAck(rtpBytesToPacket(packet.getData()), c);
                byte[] payload = rtpPacket.getPayload();

                byte[] arr = new byte[4];
                arr[0] = payload[0];
                arr[1] = payload[1];
                arr[2] = payload[2];
                arr[3] = payload[3];
                ByteBuffer bb = ByteBuffer.wrap(arr);
//                    if(use_little_endian)
//                        bb.order(ByteOrder.LITTLE_ENDIAN);
                c.remainingMessageSize = (bb.getInt());
                System.out.println("rtp.receive: new message of size: " + c.remainingMessageSize);

                //fill remainder buffer with rest of this message
                for (int i = 4; i < rtpPacket.getPayloadSize(); i++) { //remainder in the payload
                    receiveRemainder.add(payload[i]);
                }
                
            } else {
                System.out.println("rtp.receive: finishing message of size: " + c.remainingMessageSize);
            }

            //Step 2: check if the limiting factor is the parameter or the message size
            int leastDataReq = 0;
            if (c.remainingMessageSize>numBytesRequested){
                leastDataReq = numBytesRequested;
            } else {
                leastDataReq = c.remainingMessageSize;
            }

            //Step 3: read the the limiting factor number of bytes starting from the remainder buffer
            byte[] writeToBuffer = new byte[leastDataReq]; //output
            int index = 0; //the position of the write buffer we're at
            while(!receiveRemainder.isEmpty() && leastDataReq > 0) { //pulling from remainder buffer
                writeToBuffer[index] = receiveRemainder.remove();
                leastDataReq--;
                c.remainingMessageSize--;
                index++;
            }
            System.out.println("rtp.receive: finished remainder, need "+leastDataReq+" more bytes");
            while(leastDataReq > 0) { //pulling from receive buffer, receive remainder is now empty
                DatagramPacket packet = c.getReceiveBuffer().take();
                
                sendAck(rtpBytesToPacket(packet.getData()), c);
                Packet rtpPacket = rtpBytesToPacket(packet.getData());
                byte[] payload = rtpPacket.getPayload();
                System.out.println("rtp.receive: got a packet with seq# " + rtpPacket.getSequenceNumber());

                for (int i = 0; i < rtpPacket.getPayloadSize() ; i++) {
                    if (leastDataReq > 0) { //still need to read more
                        writeToBuffer[index] = payload[i];
                        leastDataReq --;
                        c.remainingMessageSize--;
                        index++;
                    } else { //put rest into remainder
                        receiveRemainder.add(payload[i]);
                    }
                }
            }
//            if (isEndingMessage) { removed with the seq no fix
//                // dup check: new message has been made so we should clear the seq and ack hashmaps in connection
//                c.clearReceivedAckNum();
//                c.clearReceivedSeqNum();
//                c.updateOrdering(-1); //makes next received packet valid order
//                System.out.println("rtp.receive: cleared receivedAckNum and receivedSeqNum");
//            }
            return writeToBuffer;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
	}

	/**
	 * Creates and sends an ack using data from the packet passed in.
     * Acks are only send for properly received data.
     *
	 * @param p packet with the data
	 * @param c connection
	 * @throws IOException
	 */
	private static void sendAck(Packet p, Connection c) throws IOException {
		int newAckNum = p.getSequenceNumber() + p.getPayloadSize();
		System.out.println("rtp.sendAck: sending ack with ack# " + newAckNum);
		int newSeqNum = p.getAckNumber();
		Packet ack = new Packet(false, true, false, newSeqNum, newAckNum, null);
		ack.setRemainingBufferSize(c.getMaxLocalWindowSize() - c.getReceiveBuffer().size());
		// reassigns checksum to account for the remaining buffer size
		ack.setChecksum(ack.calculateChecksum());
		
		byte[] ackBytes = ack.packetize();
		DatagramPacket dpAck = new DatagramPacket(ackBytes, ackBytes.length, c.getRemoteAddress(), c.getRemotePort());
		socket.send(dpAck);
	}
	
	/**
	 * Converts an array of rtpResultBytes into an rtp packet object
	 * @param rtpResultBytes Bytes from a datagram packet to convert into a rtp packet
	 * @return RTP Packet form of the byte array
	 */
	private static Packet rtpBytesToPacket(byte[] rtpResultBytes) {
		ByteBuffer buffer = ByteBuffer.wrap(rtpResultBytes);
		
		boolean FIN = (buffer.getInt() == 1); // if it equals 1, it is true
		boolean ACK = (buffer.getInt() == 1); // if it equals 1, it is true
		boolean SYN = (buffer.getInt() == 1); // if it equals 1, it is true
		int seqNum = buffer.getInt();
		int ackNum = buffer.getInt();
		int remainingBufferSize = buffer.getInt();
		int payloadSize = buffer.getInt();
		int checksum = buffer.getInt(); // new
		byte[] payload = new byte[payloadSize];
		
		for (int i = 0; i < payloadSize; i++) {
			payload[i] = buffer.get();
		}
		
		Packet result = new Packet(FIN, ACK, SYN, seqNum, ackNum, payload); // auto calculates checksum
		result.setChecksum(checksum); // reassign the old value for error checking
		
		result.setRemainingBufferSize(remainingBufferSize);
		return result;
	}


	
	/*
	 * PRIVATE METHODS
	 */

	/**
	 * Generates a key for the hash map based on the clientAddress and clientPort
	 * @param remoteAddress ip address of other host
	 * @param remotePort port of other host
	 * @return unique client key for mapping
	 */
	private static String generateKey(String remoteAddress, String remotePort) {
		if (remoteAddress == null || remoteAddress.equals("") ) {
			System.out.println("no clientAddress in rtp.generateKey");
			return null;
		} else if (remotePort == null || remotePort.equals("")) {
			System.out.println("no clientPort in rtp.generateKey");
			return null;
		}
		return remoteAddress + remotePort;
	}
	
	/**
	 * Retrieves a connection from the hash map.
	 * @param remoteAddress ip address of other host
	 * @param remotePort of other host
	 * @return Connection or null if it has not been created
	 */
	private static Connection getConnection(String remoteAddress, String remotePort) {
		String key = generateKey(remoteAddress, remotePort);
		if ((key != null) && connections.containsKey(key)) {
			return connections.get(key);
		} else {
			System.out.println("rtp.getConnection: cannot retrieve connection to "+
                    remoteAddress + ": " + remotePort);
			return null;
		}
	}
	
	/**
	 * Returns true if the connection was deleted. 
	 * Returns false if there was no connection to delete.
	 * @param remoteAddress address of other host
	 * @param remotePort port of other host
	 * @return whether or not the desired connection was available to delete
	 */
	private static boolean deleteConnection(String remoteAddress, String remotePort) {
		String key = generateKey(remoteAddress, remotePort);
		return connections.remove(key) != null;
		
	}
	
	/**
	 * Creates a connection object and adds it to the hashmap.
	 * Does not actually establish a TCP connection. This is just for
	 * rtp representation for easy access later on.
	 * @param localIP local address
	 * @param localPort local port
     * @param remoteIP remote address
     * @param remotePort remote port
	 * @return Connection representing the two sockets in hashmap
	 * @throws Exception for unconnected or null sockets
	 */
	private static Connection createConnection(InetAddress localIP, int localPort,
			InetAddress remoteIP, int remotePort) throws Exception {
		if (localIP== null) {
			throw new Exception("rtp.createConncetion: local ip is null");
		} else if (localPort == -1) {
			throw new Exception("rtp.createConnection: local socket is not connected");
		} else if (remoteIP == null) {
			throw new Exception("rtp.createConnection: invalid remote IP");
		} else if (remotePort < 0) {
			throw new Exception("rtp.createConnection: invalid remote port");
		}


		Connection c = new Connection(localIP, localPort, remoteIP, remotePort);
		String key = generateKey(remoteIP.getHostAddress(), String.valueOf(remotePort));
		connections.put(key, c);
        System.out.println("rtp.createconnection: Created connection with "+
                remoteIP.getHostAddress() + ": " + String.valueOf(remotePort));
		return c;
	}

    /**
     * The thread for receiving data. starts when accept accept starts
     * Do not implement until we have one working first
     */
    private static class MultiplexData extends Thread{
        /**
         * Constructor if we need it
         */
//        MultiplexData(){
//        }

        /**
         * called by start()
         * Call this in the first line of both connect and accept
         *
         * calls udp's recieve in a loop and puts it in the correct buffer
         * 1) look at SYN bit, if 1 throw in syn buffer
         * 2) look at port and ip, if connection exists, throw in connection's receive buffer
         * 3) if no connection exists, ignore
         */
        @Override
        public void run(){
        	boolean connectionOpen = true;
            while(connectionOpen){
                DatagramPacket receivePacket = new DatagramPacket(
                        new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);

//                System.out.println("\nMultiplexData.run: Checking for packet...");
                try {
                    socket.receive(receivePacket);
                    if (receivePacket != null) {
                        Packet rtpReceivePacket = rtpBytesToPacket(receivePacket.getData());
                        InetAddress remoteAddress = receivePacket.getAddress();
                        int remotePort = receivePacket.getPort();
                        Connection c = getConnection(remoteAddress.getHostAddress(), String.valueOf(remotePort));

                        if (rtpReceivePacket.getACK() && !rtpReceivePacket.getFIN()) { //if ack, put in corresponding ack buffer
                           
                            if (c != null) {
//                                System.out.println("\nMultiplexData.run: Got an ACK packet");
                                
                                // make sure it's not a dup
                                int ackNum = rtpReceivePacket.getAckNumber();

                                if (c.isValidAck(rtpReceivePacket)) {
                                	System.out.println("MultiplexData.run: Got a new ACK packet. ack# " + ackNum);
	                                c.getAckBuffer().put(receivePacket);
	                                c.addToReceivedAckNum(ackNum);
                                } else {
                                	System.out.println("MultiplexData.run: Got a dup ACK packet. Ignore ack# " + ackNum);
                                }
                            }

                        } else if (rtpReceivePacket.getSYN() && (rtpReceivePacket.getChecksum() == rtpReceivePacket.calculateChecksum())) { //if syn put in syn buffer
//                            System.out.println("\nMultiplexData.run: Got a SYN packet");
                            synQ.add(receivePacket);
                            
                        } else if (rtpReceivePacket.getFIN() && !rtpReceivePacket.getACK()) {

                        	if (c != null) {
//                        		System.out.println("\nMultiplexData.run: Got a FIN packet");
                        		DatagramPacket finack = makeFinAckPacket(rtpReceivePacket);
                        		finack.setAddress(c.getRemoteAddress());
                        		finack.setPort(c.getRemotePort());
                        		socket.send(finack);
                        		deleteConnection(remoteAddress.getHostAddress(), String.valueOf(remotePort));
                        	}
                        } else if (rtpReceivePacket.getFIN() && rtpReceivePacket.getACK()) {
                        	// received a FINACK
                        	

                        	if (c != null) {
//	                        	System.out.println("\nMultiplexData.run: Got a FIN ACK packet");
	                        	deleteConnection(remoteAddress.getHostAddress(), String.valueOf(remotePort));
	                        	Thread.currentThread().interrupt();
	                        	connectionOpen = false;
	                        	return;
                        	}
                        } else { //data to put in corresponding recieve buffer
//                            System.out.println("\nMultiplexData.run: Got a data packet");

                            if (c != null) {
                            	
                            	// make sure it's not a dup
                            	int seqNum = rtpReceivePacket.getSequenceNumber();

                            	if (c.isValidDataPacket(rtpReceivePacket)) {
                                    if(c.isValidOrder(rtpReceivePacket) ) {
//                                        System.out.println("MultiplexData.run: Got a new data packet with seq# " + seqNum);
                                        c.updateOrdering(seqNum + rtpReceivePacket.getPayloadSize()); //out of order detection
                                        c.getReceiveBuffer().put(receivePacket);
                                        c.addToReceivedSeqNum(rtpReceivePacket.getSequenceNumber());
                                    } else {
                                    	System.out.println("MultiplexData.run: Got a out of order " +
                                                "data packet. Ignore seq# "+seqNum);
                                        System.out.println("MultiplexData.run: expectedOrder = " + c.lastSeqReceived);
            
                                    }
                            	} else {
                                    System.out.println("MultiplexData.run: Got a dup data packet. SendingAck with ack# "+(seqNum+rtpReceivePacket.getPayloadSize()));
                                    sendAck(rtpReceivePacket, c);
                            	}
                            	
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

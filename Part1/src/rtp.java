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
	// TODO: implement writing at the server (need to know where to write to)
	// TODO: implement reading at the server
	// TODO: implement sending acknowledgements
	
	// string key is IP + port
	private static ConcurrentHashMap<String, Connection> connections
		= new ConcurrentHashMap<String, Connection>(); // for demultiplexing
	private static int RECEIVE_PACKET_BUFFER_SIZE = 2048; // arbitrary value
//	private static int TIMEOUT = 2000; // arbitrary milliseconds
	private static final int MAX_SEGMENT_SIZE = 972; 
	private static DatagramSocket socket;
    private static boolean multiplexRunning = false;

    private static LinkedBlockingQueue<DatagramPacket> synQ = new LinkedBlockingQueue<DatagramPacket>();


	/*
	 * CLASS METHODS BELOW
	 */
	
	/**
	 * Makes a server socket for the application that the clients will connect to. <br>
	 * Only call this in the server upon startup.
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
	 * @param windowSizeInBytes
	 * @return whether or not the connection attempt succeeded
	 * @throws Exception 
	 */
	@SuppressWarnings("finally")
	public static Connection connect(InetAddress serverIP, int serverPort, int windowSizeInBytes) throws Exception {
		System.out.println("\n----------------- Connect --------------------");
		socket = new DatagramSocket();

        System.out.println(socket.getLocalAddress());
        System.out.println(socket.getLocalPort());

        //get client addresses as string
		String clientAddressStr = socket.getLocalAddress().getHostAddress();
		String clientPortStr = String.valueOf(socket.getLocalPort());

        //get client addresses as ints
//		int clientAddress = ByteBuffer.wrap(socket.getLocalAddress().getAddress()).getInt();
//		int clientPort = socket.getLocalPort();

        //check if connection established
        System.out.println("rtp.connect: checking if connection has already been established");
		if (getConnection(serverIP.getHostAddress(), String.valueOf(serverPort)) != null) {
			System.out.println("rtp.connect: Connection has already been established");
			return getConnection(clientAddressStr, clientPortStr);
		}

		try {
            (new MultiplexData()).start();

			Connection c = createConnection(socket.getLocalAddress(), socket.getLocalPort(), serverIP, serverPort);
			c.setWindowSize(windowSizeInBytes);

			System.out.println("rtp.connect: 1. Created a connection object in hashmap");
			
			/*
			 * implementation of 3 way handshake
			 */
			
			/*
			 * HANDSHAKE 1: CLIENT --> SERVER
			 */
			// Create SYNbit = 1, Seq = x packet
			DatagramPacket SynPacketDP = makeSynPacket(serverIP, serverPort);
			System.out.println("rtp.connect: 2. Made SYN packet");
			socket.send(SynPacketDP);
			System.out.println("rtp.connect: 3. Sent SYN packet");
			
//			DatagramPacket receivePacket = new DatagramPacket(
//					new byte[RECEIVE_PACKET_BUFFER_SIZE],
//					RECEIVE_PACKET_BUFFER_SIZE);
//
            System.out.println("rtp.connect: 4. Waiting to receive SYN ACK...");
			DatagramPacket receivePacket = c.getAckBuffer().take(); //blocks
			System.out.println("rtp.connect: 4.1 Received a datagram packet: " + receivePacket);
			
			boolean validPacketReceived = false;
			
			while (!validPacketReceived) { //check if syn packet
				Packet receivePacketRTP = rtpBytesToPacket(receivePacket.getData());
				if (receivePacketRTP.getSYN()) {
					System.out.println("rtp.connect: 5. Received a SYN ACK packet");
					/*
					 * HANDSHAKE 3: CLIENT --> SERVER
					 */
					// Create ACKbit = 1, ACKnum = y+1 packet
					DatagramPacket ack = makeHandshakeAckPacket(serverIP, serverPort);
					System.out.println("rtp.connect: 6. Made ACK");
					socket.send(ack);
					System.out.println("rtp.connect: 7. Sent ACK");
					validPacketReceived = true;
				} else {
					System.out.println("rtp.connect: 4.2 waiting to receive another packet");
                    receivePacket = c.getAckBuffer().take();
					System.out.println("rtp.connect: 4.3  Received: " + receivePacket);
				}
			}
			
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
	 * @return handshake ack packet
	 */
	private static DatagramPacket makeHandshakeAckPacket(
			InetAddress serverAddress, int serverPort) {
		Packet packet3 = new Packet(false, true, false, 1, 1, null);
		byte[] packet3bytes = packet3.packetize();
        return new DatagramPacket(packet3bytes, packet3bytes.length,
                serverAddress, serverPort);
	}
	
	/**
	 * Creates a SynAck packet for step 2 of the 3-way handshake
	 * @param clientIP
	 * @param clientPort
	 * @return
	 */
	private static DatagramPacket makeSynAckPacket(InetAddress clientIP, int clientPort) {
		Packet packet2 = new Packet(false, true, true, 0, 1, null);
		byte[] packet2bytes = packet2.packetize();

        return new DatagramPacket(packet2bytes, packet2bytes.length, clientIP, clientPort);
	}
	
	/**
	 * Makes a SYN packet for part 1 of the 3-way handshake
	 * @param serverIP
	 * @param serverPort
	 * @return
	 */
	private static DatagramPacket makeSynPacket(InetAddress serverIP, int serverPort) {
		Packet SynPacket = new Packet(false, false, true, 0, 0, null);
		byte[] SynPacketBytes = SynPacket.packetize();
		
		DatagramPacket SynPacketDP = new DatagramPacket(SynPacketBytes, SynPacketBytes.length, 
				serverIP, serverPort);
		
		return SynPacketDP;
	}
	
	/**
	 * Prints the values of FIN, SYN, and ACK
	 * @param p
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
     * @return The connection with the client
	 */
	public static Connection accept() {
        if(!multiplexRunning) {
            (new MultiplexData()).start();
            multiplexRunning = true;
        }
        try { //make connection, send synack, listen for ack, return connection
            //TODO:take blocks forever, think about making it timeout
            DatagramPacket synPacket = synQ.take(); //will block until there is something to pop
    //        DatagramPacket receivePacket = new DatagramPacket(
    //                new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);

//            Packet rtpReceivePacket = rtpBytesToPacket(synPacket.getData());
            InetAddress clientAddress = synPacket.getAddress();
            int clientPort = synPacket.getPort();

            //printRtpPacketFlags(rtpReceivePacket);

            DatagramPacket SynAckPacket = makeSynAckPacket(clientAddress, clientPort);
            Connection c = createConnection(socket.getLocalAddress(), socket.getLocalPort(), clientAddress, clientPort);

            socket.send(SynAckPacket);
            c.getAckBuffer().take(); //blocks until it returns something
            System.out.println("rtp.accept: received an ACK packet, printing list of connections");
            int j = 0;
            for(Connection i: connections.values()){
                System.out.println("Connection "+j+": "+i.getRemoteAddress()+":"+i.getRemotePort());
                j++;
            }
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
		//TODO:I only changed the getClientaddr/port... to getLocal to compile and server to remote -jw

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
		
		System.out.println("\n----------- CLOSE ---------------");
		printRtpPacketFlags(rtpBytesToPacket(fin.getData()));
		System.out.println("---------------------------------\n");
		
		// WE ASSUME HERE THAT NO PACKETS ARE LOST, 
		// SO WE DON'T BOTHER WITH ACTUALLY WAITING 2x IN THE TIMED WAIT
		DatagramPacket receivePacket = new DatagramPacket(
				new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);
		
		// TODO: MAKE THIS THREAD SAFE
		socket.receive(receivePacket);
		
		// wait to receive the fin packet from the server
		byte[] rtpPacket = null;
		boolean receivedValidPacket = false;
		while (!receivedValidPacket) {
			if (receivePacket != null) {
				rtpPacket = receivePacket.getData();
				
				int finSeqNumPlusOne = 2;
				boolean receivedAnAck = getAckFromRtpPacket(rtpPacket);
				boolean hasCorrectSeqNum = (getAckNumFromRtpPacket(rtpPacket) == finSeqNumPlusOne);
				
				if (receivedAnAck && hasCorrectSeqNum) {
					receivedValidPacket = true;
				} else {
					// TODO: MAKE THIS THREAD SAFE
					socket.receive(receivePacket);
				}
			}
		}

	}
	
	/**
	 * Makes a FIN packet to send to either the server or the client in the close protocol. <br>
	 * NOTE TO USER: Make sure to set the destination IP + port before sending.
	 * @return
	 */
	private static DatagramPacket makeFinPacket() {
		Packet rtpFinPacket = new Packet(true, false, false, 1, 100, null);
		byte[] rtpFinPacketBytes = rtpFinPacket.packetize();
		DatagramPacket rtpFinDp = new DatagramPacket(rtpFinPacketBytes, rtpFinPacketBytes.length);
		return rtpFinDp;
	}
	
	/**
	 * Makes a FIN acknowledgement packet for closing. <br>
	 * Used by both the client and the server. <br>
	 * NOTE TO USER: make sure to set the packet destination IP + Port<br>
	 * @param packetToAck
	 * @param toClientFromServer
	 * @return a FIN acknowledgement packet
	 */
	private static DatagramPacket makeFinAckPacket(Packet packetToAck, boolean toClientFromServer) {
		int ackNumber = packetToAck.getSequenceNumber() + 1;
		int sequenceNumber = packetToAck.getAckNumber();

		Packet ack = new Packet(false, false, false, sequenceNumber, ackNumber, null);
		byte[] ackBytes = ack.packetize();
		
		DatagramPacket ackDP = new DatagramPacket(ackBytes, ackBytes.length);
	
		return ackDP;
	}
	
	/**
	 * Converts data to a bytestream and sends packets to the other end.
     * loop:
     * Send as many packets as possible according to flow control
     * Receive entire ack buffer to update number of things that could be sent
     *
     * Sends a message. Finishes when the ack is received.
	 * @param data
	 */
	public static void send(byte[] data, Connection connection) {
        System.out.println("rtp.send: Starting send");
		Queue<DatagramPacket> packetsToSend = convertStreamToPacketQueue(data);
        int packetsToAckLeft = packetsToSend.size();
//        packetsToSend.size() > 0
		while (packetsToAckLeft > 0) {
            //while there are packets left to ack

            //send as many as you can according to flow control
            int remainingWindowSize = packetsToSend.size();//connection.getLastByteAcked()-connection.getLastByteSent();
            //TODO: fix this (its wrong, so i made it all) and init in connect

            for(int i = 0; i<remainingWindowSize; i++){
                DatagramPacket toSend = packetsToSend.remove();
                toSend.setAddress(connection.getRemoteAddress());
                toSend.setPort(connection.getRemotePort());
                try {
                    Packet tempDebug = rtpBytesToPacket(toSend.getData());
                    System.out.println("rtp.send: sending packet: "+tempDebug.getSequenceNumber());
                    socket.send(toSend);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
			while(!connection.getAckBuffer().isEmpty()){
                // ack all of them
                try {
                    System.out.println("rtp.send: looking for acks: "+packetsToAckLeft);
                    DatagramPacket ack = connection.getAckBuffer().take();
                    byte[] bytes = ack.getData();
                    Packet rtpAck = rtpBytesToPacket(bytes);
                    System.out.println("rtp.send: got ack for: "+rtpAck.getAckNumber());
                    if (rtpAck.getACK() && !connection.isDuplicateAckNum(rtpAck.getAckNumber())) {
                        packetsToAckLeft--;
                        // we received a valid ack
                        connection.setLastByteAcked(rtpAck.getRemainingBufferSize()); //TODO:figure out with andrea
//                            DatagramPacket nextPacket = packetsToSend.peek();
//                            int nextPacketSize = rtpBytesToPacket(nextPacket.getData()).getPayloadSize();
//                            canSendPacket = (remainingBufferSize >= nextPacketSize);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
		}
        System.out.println("rtp.send: Ending send");
	}
	
	/**
	 * Converts a byte array into a Queue of Datagram Packets.
	 * @param data byte stream to convert
	 * @return Queue of DatagramPackets
	 */
	private static Queue<DatagramPacket> convertStreamToPacketQueue(byte[] data) {
		Queue<DatagramPacket> result = new LinkedList<DatagramPacket>();
		
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
			Packet packet = new Packet(false, false, false, seqNum, 0, payload);
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
		
		int lastSeqNum = (numFullPackets + 1) * MAX_SEGMENT_SIZE;
		Packet packet = new Packet(false, false, false, lastSeqNum, 0, lastPayload);
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
     * TODO:should also close a connection if receiving a fin bit
     * TODO:what if data is requested but only half is there?
     *
	 * @param numBytesRequested
	 * @return number of bytes read
	 */
	public static Byte[] receive(int numBytesRequested, Connection c) {
		
		if (c == null) {
			// connection does not exist yet
			System.out.println("rtp.receive: connection does not exist yet");
			return null;
		}

		//int numBytesReturned = Math.min(numBytesRequested, c.getReceiveBuffer().size());

		if (numBytesRequested == 0) {
			System.out.println("rtp.receive: no bytes to read");
			return null;
		}

        Byte[] writeToBuffer = new Byte[numBytesRequested];
        Queue<Byte> receiveRemainder = c.getReceiveRemainder();
        int remainingBytes = numBytesRequested - receiveRemainder.size();
        //pull off either num bytes returned or the entire receive remaneder buffer depending on which is full
		for (int i = 0; i < (numBytesRequested<receiveRemainder.size()?numBytesRequested:receiveRemainder.size()); i++) {
			writeToBuffer[i] = receiveRemainder.remove();
		}

        while(remainingBytes>0){
            try {
                DatagramPacket packet = c.getReceiveBuffer().take();
                sendAck(rtpBytesToPacket(packet.getData()), c);


                Packet rtpPacket = rtpBytesToPacket(packet.getData());
                byte[] payload = rtpPacket.getPayload();


                //either the remaining bytes or the entire payload depending on which is smaller
                int bytesToTake = remainingBytes<payload.length?remainingBytes:payload.length;

                for (int i = 0; i<bytesToTake ;i++){
                    writeToBuffer[numBytesRequested-remainingBytes] = payload[i];
                    remainingBytes--;
                }

                if(remainingBytes<=0){ //last packet
                    for(int i = 0; i<payload.length-bytesToTake; i++)//remainder in the payload
                        receiveRemainder.add(payload[i+bytesToTake]);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

		return writeToBuffer;
	}

	/**
	 * Creates and sends an ack using data from the packet passed in.
	 * @param p packet with the data
	 * @param c connection
	 * @throws IOException
	 */
	private static void sendAck(Packet p, Connection c) throws IOException {
		int newAckNum = p.getSequenceNumber() + p.getPayloadSize();
		int newSeqNum = p.getAckNumber();
		Packet ack = new Packet(false, true, false, newSeqNum, newAckNum, null);
		ack.setRemainingBufferSize(c.getRemainingReceiveBufferSize());
		byte[] ackBytes = ack.packetize();
		DatagramPacket dpAck = new DatagramPacket(ackBytes, ackBytes.length, c.getRemoteAddress(), c.getRemotePort());
		socket.send(dpAck);
	}
	
	/**
	 * Converts an array of rtpResultBytes into an rtp packet object
	 * @param rtpResultBytes
	 * @return RTP Packet form of the byte array
	 * @throws Exception 
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
		byte[] payload = new byte[payloadSize];
		
		for (int i = 0; i < payloadSize; i++) {
			payload[i] = buffer.get();
		}
		
		Packet result = new Packet(FIN, ACK, SYN, seqNum, ackNum, payload);
		
		result.setRemainingBufferSize(remainingBufferSize);
		return result;
	}


	
	/*
	 * PRIVATE METHODS
	 */
	
	/**
	 * Take the bytes representing an RTP packet, and return the value of the FIN flag
	 * @param rtpPacket
	 * @return FIN
	 */
	private static boolean getFinFromRtpPacket(byte[] rtpPacket) {
		Packet p = rtpBytesToPacket(rtpPacket);
		return p.getFIN();
	}
	
	/**
	 * Take the bytes representing an RTP packet, and return the value of the ACK flag
	 * @param rtpPacket
	 * @return ACK
	 */
	private static boolean getAckFromRtpPacket(byte[] rtpPacket) {
		Packet p = rtpBytesToPacket(rtpPacket);
		return p.getACK();
	}
	
	/**
	 * Take the bytes representing an RTP packet, and return the value of the SYN flag
	 * @param rtpPacket
	 * @return SYN
	 */
	private static boolean getSynFromRtpPacket(byte[] rtpPacket) {
		Packet p = rtpBytesToPacket(rtpPacket);
		return p.getSYN();
	}
	
	/**
	 * Take the bytes representing an RTP packet, and return the Sequence Number
	 * @param rtpPacket
	 * @return Sequence Number
	 */
	private static int getSeqNumFromRtpPacket(byte[] rtpPacket) {
		Packet p = rtpBytesToPacket(rtpPacket);
		return p.getSequenceNumber();
	}
	
	/**
	 * Take the bytes representing an RTP packet, and return the Ack Number
	 * @param rtpPacket
	 * @return Ack Number
	 */
	private static int getAckNumFromRtpPacket(byte[] rtpPacket) {
		Packet p = rtpBytesToPacket(rtpPacket);
		return p.getAckNumber();
	}
	
	/**
	 * Take the bytes representing an RTP packet, and return the remaining buffer size
	 * @param rtpPacket
	 * @return Remaining Buffer Size
	 */
	private static int getRemainingBufferSizeFromRtpPacket(byte[] rtpPacket) {
		Packet p = rtpBytesToPacket(rtpPacket);
		return p.getRemainingBufferSize();
	}
	
	/**
	 * Take the bytes representing an RTP packet, and return the payload size
	 * @param rtpPacket
	 * @return Payload Size
	 */
	private static int getPayloadSizeFromRtpPacket(byte[] rtpPacket) {
		Packet p = rtpBytesToPacket(rtpPacket);
		return p.getPayloadSize();
	}
	
	/**
	 * Take the bytes representing an RTP packet, and return the payload
	 * @param rtpPacket
	 * @return Payload
	 */
	private static byte[] getPayloadFromRtpPacket(byte[] rtpPacket) {
		Packet p = rtpBytesToPacket(rtpPacket);
		return p.getPayload();
	}
	
	/**
	 * Generates a key for the hash map based on the clientAddress and clientPort
	 * @param clientAddress
	 * @param clientPort
	 * @return unique client key for mapping
	 */
	private static String generateKey(String clientAddress, String clientPort) {
		if (clientAddress == null || clientAddress.equals("") ) {
			System.out.println("no clientAddress in rtp.generateKey");
			return null;
		} else if (clientPort == null || clientPort.equals("")) {
			System.out.println("no clientPort in rtp.generateKey");
			return null;
		}
		return new String(clientAddress + clientPort);
	}
	
	/**
	 * Retrieves a connection from the hash map.
	 * @param remoteAddress
	 * @param remotePort
	 * @return Connection or null if it has not been created
	 */
	private static Connection getConnection(String remoteAddress, String remotePort) {
		String key = generateKey(remoteAddress, remotePort);
		if (connections.containsKey(key)) {
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
	 * @param remoteAddress
	 * @param remotePort
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
	 * @param localIP
	 * @param localPort
     * @param remoteIP
     * @param remotePort
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
            while(true){
                DatagramPacket receivePacket = new DatagramPacket(
                        new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);

                System.out.println("\nMultiplexData.run: Checking for packet...");
                try {
                    socket.receive(receivePacket);
                    System.out.println("MultiplexData.run: socket.receive finished calling, got a packet");

                    if (receivePacket != null) {
                        System.out.println("MultiplexData.run: The packet is not null");
                        Packet rtpReceivePacket = rtpBytesToPacket(receivePacket.getData());
                        InetAddress remoteAddress = receivePacket.getAddress();
                        int remotePort = receivePacket.getPort();
//                        printRtpPacketFlags(rtpReceivePacket);

                        if (rtpReceivePacket.getACK()) { //if ack, put in corresponding ack buffer
                            Connection c = getConnection(remoteAddress.getHostAddress(), String.valueOf(remotePort));
                            if (c != null) {
                                System.out.println("MultiplexData.run: Packet is an ACK packet");
                                c.getAckBuffer().put(receivePacket);
                            }
                        } else if (rtpReceivePacket.getSYN()) { //if syn put in syn buffer
                            System.out.println("MultiplexData.run: Packet is a SYN packet");
                            synQ.add(receivePacket);
                        } else { //data to put in corresponding recieve buffer
                            System.out.println("MultiplexData.run: Packet is a data packet");
                            Connection c = getConnection(remoteAddress.getHostAddress(), String.valueOf(remotePort));
                            if (c != null) {
                               c.getReceiveBuffer().put(receivePacket);
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

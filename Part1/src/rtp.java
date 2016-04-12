import java.net.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Uses UDP sockets to behave like TCP
 * @author andreahu
 *
 */
public class rtp {
	// TODO: implement writing at the server (need to know where to write to)
	// TODO: implement reading at the server
	// TODO: implement sending acknowledgements
	
	// string key is client IP + client port
	private static ConcurrentHashMap<String, Connection> clientPortToConnection
		= new ConcurrentHashMap<String, Connection>(); // for demultiplexing
	private static int RECEIVE_PACKET_BUFFER_SIZE = 2048; // arbitrary value
//	private static int TIMEOUT = 2000; // arbitrary milliseconds
	private static final int MAX_SEGMENT_SIZE = 972; 
	private static DatagramSocket socket;


    private static ConcurrentLinkedQueue<DatagramPacket> synQ = new ConcurrentLinkedQueue<DatagramPacket>();


	/*
	 * CLASS METHODS BELOW
	 */
	
	/**
	 * Makes a server socket for the application that the clients will connect to. <br>
	 * Only call this in the server upon startup.
	 * @param port
	 * @return
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
		int clientAddress = ByteBuffer.wrap(socket.getLocalAddress().getAddress()).getInt();
		int clientPort = socket.getLocalPort();

        //check if connection established
		if (getConnection(clientAddressStr, clientPortStr) != null) {
			System.out.println("Connection has already been established");
			return getConnection(clientAddressStr, clientPortStr);
		}

		try {
			Connection c = createConnection(socket.getLocalAddress(), socket.getLocalPort(), serverIP, serverPort);
			c.setWindowSize(windowSizeInBytes);
			System.out.println("1. Created a connection object in hashmap");
			
			/*
			 * implementation of 3 way handshake
			 */
			
			/*
			 * HANDSHAKE 1: CLIENT --> SERVER
			 */
			// Create SYNbit = 1, Seq = x packet
			DatagramPacket SynPacketDP = makeSynPacket(serverIP, serverPort);
			System.out.println("2. Made SYN packet");
			socket.send(SynPacketDP);
			System.out.println("3. Sent SYN packet");
			
			DatagramPacket receivePacket = new DatagramPacket(
					new byte[RECEIVE_PACKET_BUFFER_SIZE],
					RECEIVE_PACKET_BUFFER_SIZE);
						
			
			System.out.println("4. Waiting to receive SYN ACK...");
			// TODO: LOCK THIS METHOD CALL
			socket.receive(receivePacket);
			System.out.println("4.1 Received a datagram packet: " + receivePacket);
			
			boolean validPacketReceived = false;
			
			while (!validPacketReceived) {
				Packet receivePacketRTP = rtpBytesToPacket(receivePacket.getData());
				if ((receivePacket != null) && (receivePacketRTP.getACK()) && (receivePacketRTP.getSYN())) {
					System.out.println("5. Received a SYN ACK packet");
					/*
					 * HANDSHAKE 3: CLIENT --> SERVER
					 */
					// Create ACKbit = 1, ACKnum = y+1 packet
					DatagramPacket ack = makeHandshakeAckPacket(clientAddress, clientPort, serverIP, serverPort);
					System.out.println("6. Made ACK");
					socket.send(ack);
					System.out.println("7. Sent ACK");
					validPacketReceived = true;
				} else {
					System.out.println("4.2 waiting to receive another packet");
					socket.receive(receivePacket);
					System.out.println("4.3  Received: " + receivePacket);
				}
			}
			
			System.out.println("rtp.connect: returning " + c);
			System.out.println("----------------- end Connect --------------------\n");
			return c;
			
		} catch (Exception e) {
			System.out.println("<-----------rtp.Connect Failed-------------->");
			e.printStackTrace();
			
			// remove the failed connection if necessary
			String address = socket.getLocalAddress().getHostAddress();
			String port = String.valueOf(socket.getLocalPort());
			if (clientPortToConnection.containsKey(generateKey(address, port))) {
				clientPortToConnection.remove(address, port);
			}
			System.out.println("rtp.connect: returning " + null);
			System.out.println("----------------- end Connect --------------------\n");
			return null;
		}
	}
	
	/**
	 * Creates an ACK packet for the 3rd handshake
	 * @param clientAddress
	 * @param clientPort
	 * @param serverAddress
	 * @param serverPort
	 * @return handshake ack packet
	 */
	private static DatagramPacket makeHandshakeAckPacket(int clientAddress, int clientPort,
			InetAddress serverAddress, int serverPort) {
		Packet packet3 = new Packet(false, true, false, 1, 1, null);
		byte[] packet3bytes = packet3.packetize();
		DatagramPacket p3 = new DatagramPacket(packet3bytes, packet3bytes.length, 
				serverAddress, serverPort);
		return p3;
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
		
		DatagramPacket p2 = new DatagramPacket(packet2bytes, packet2bytes.length, clientIP, clientPort);
		return p2;
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
	 */
	public static Connection accept() {
		DatagramPacket receivePacket = new DatagramPacket(
				new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);
		
			System.out.println("\nIn rtp.accept....");
			try {
				// TODO: MAKE THIS THREAD SAFE
				socket.receive(receivePacket);
				System.out.println("rtp.accept: socket.receive finished calling");
				
				if (receivePacket != null) {
					System.out.println("rtp.accept: received a not null packet");
					Packet rtpReceivePacket = rtpBytesToPacket(receivePacket.getData());
					InetAddress clientAddress = receivePacket.getAddress();
					int clientPort = receivePacket.getPort();
					
					printRtpPacketFlags(rtpReceivePacket);
					
					if (rtpReceivePacket.getSYN()) {
						System.out.println("rtp.accept: received a SYN packet");
						// got the syn packet from the first handshake
						// send the syn ack packet for the second handshake
						DatagramPacket SynAckPacket = makeSynAckPacket(clientAddress, clientPort);
						Connection c = createConnection(clientAddress, clientPort,
								socket.getLocalAddress(), socket.getLocalPort());
						socket.send(SynAckPacket);
					} 
					
					if (rtpReceivePacket.getACK()) {
						System.out.println("rtp.accept: received an ACK packet");
						// check for ack packet in 3rd handshake
						// received ack? make a connection
						Connection c = getConnection(clientAddress.getHostAddress(), 
								String.valueOf(clientPort));
						return c;
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
//		}
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
		
		InetAddress clientAddress = c.getClientAddress();
		int clientPort = c.getClientPort();
		
		boolean closeClientSocket = (localAddress.equals(clientAddress)) && (localPort == clientPort);
		
		// if we want to close the client socket, we need to send a packet to the server
		InetAddress destinationAddress = closeClientSocket ? c.getServerAddress() : c.getClientAddress();
		int destinationPort = closeClientSocket ? c.getServerPort() : c.getClientPort();
			
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
	 * @param destIP
	 * @param destPort
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
	 * TODO: THIS ONLY SENDS FROM CLIENT TO SERVER FOR NOW
	 * TODO: FIND A WAY TO TELL THE SERVER WHICH CLIENT TO SEND TO
     *
     * Sends a message. Finishes when the ack is received.
	 * @param data
	 */
	public static void send(byte[] data, Connection connection) {
		// TODO: set the destination of each datagram packet
		Queue<DatagramPacket> packetsToSend = convertStreamToPacketQueue(data);
		Connection c = getConnection(socket.getLocalAddress().getHostAddress(), 
				String.valueOf(socket.getLocalPort()));

		boolean canSendPacket = true;
		while (packetsToSend.size() > 0) {
			if (canSendPacket) {
				DatagramPacket toSend = packetsToSend.peek();
				toSend.setAddress(c.getServerAddress());
				toSend.setPort(c.getServerPort());
				
				try {
					socket.send(toSend);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}			
			
			DatagramPacket ack = new DatagramPacket(
					new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);
			
			boolean receivedValidPacket = false;
			while (!receivedValidPacket) {
				try {
					// TODO: make this thread safe
					socket.receive(ack);
					
					if (ack != null) {
						byte[] bytes = ack.getData();
						Packet rtpAck = rtpBytesToPacket(bytes);
						if (rtpAck.getACK() && !c.isDuplicateAckNum(rtpAck.getAckNumber())) {
							// we received a valid ack!
							int remainingBufferSize = rtpAck.getRemainingBufferSize();
							
							DatagramPacket nextPacket = packetsToSend.peek();
							int nextPacketSize = rtpBytesToPacket(nextPacket.getData()).getPayloadSize();
							
							canSendPacket = (remainingBufferSize >= nextPacketSize);
							receivedValidPacket = true;
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {}
			}
		}
	}
	
	/**
	 * Converts a byte array into a Queue of Datagram Packets.
	 * @param data
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
	 * NOTE: THIS ONLY WORKS FOR THE CLIENT SIDE
	 * TODO: RETRIEVE THE CORRECT CONNECTION AT THE SERVER SIDE TOO
	 * Reads a specified number of bytes (or less depending on the buffer size)
	 * and write them to the byte buffer provided.
     *
     * accepts a connection and adds it to the corresponding buffer(either the syn buffer or the correct connection's)
	 * @param writeToBuffer
	 * @param numBytesRequested
	 * @return number of bytes read
	 */
	public static int receive(byte[] writeToBuffer, int numBytesRequested, Connection c) {
		
		if (c == null) {
			// connection does not exist yet
			System.out.println("rtp.receive: connection does not exist yet");
			return -1;
		}
		
		int numBytesReturned = Math.min(numBytesRequested, c.getReceiveBufferSize());
		
		if (numBytesReturned == 0) {
			System.out.println("rtp.receive: no bytes to read");
			return -1;
		}
		
		writeToBuffer = new byte[numBytesReturned];
		
		for (int i = 0; i < numBytesReturned; i++) {
			writeToBuffer[i] = c.getReceiveBuffer().poll();
		}
		
		return numBytesReturned;
	}

	/**
	 * Creates and sends an ack using data from the packet passed in.
	 * @param p
	 * @param c
	 * @throws IOException
	 */
	private static void sendAck(Packet p, Connection c) throws IOException {
		int newAckNum = p.getSequenceNumber() + p.getPayloadSize();
		int newSeqNum = p.getAckNumber();
		Packet ack = new Packet(false, true, false, newSeqNum, newAckNum, null);
		ack.setRemainingBufferSize(c.getRemainingReceiveBufferSize());
		byte[] ackBytes = ack.packetize();
		DatagramPacket dpAck = new DatagramPacket(ackBytes, ackBytes.length, c.getClientAddress(), c.getClientPort());
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
	 * @param clientAddress
	 * @param clientPort
	 * @return Connection or null if it has not been created
	 */
	private static Connection getConnection(String clientAddress, String clientPort) {
		String key = generateKey(clientAddress, clientPort);
		if (clientPortToConnection.containsKey(key)) {
			return clientPortToConnection.get(key);
		} else {
			System.out.println("rtp.getConnection: cannot retrieve connection");
			return null;
		}
	}
	
	/**
	 * Returns true if the connection was deleted. 
	 * Returns false if there was no connection to delete.
	 * @param clientAddress
	 * @param clientPort
	 * @return whether or not the desired connection was available to delete
	 */
	private static boolean deleteConnection(String clientAddress, String clientPort) {
		String key = generateKey(clientAddress, clientPort);
		return clientPortToConnection.remove(key) != null;
		
	}
	
	/**
	 * Creates a connection object and adds it to the hashmap.
	 * Does not actually establish a TCP connection. This is just for
	 * rtp representation for easy access later on.
	 * @param clientSocket
	 * @param serverSocket
	 * @return Connection representing the two sockets in hashmap
	 * @throws Exception for unconnected or null sockets
	 */
	private static Connection createConnection(InetAddress clientIP, int clientPort, 
			InetAddress destIP, int destPort) throws Exception {
		if (clientIP== null) {
			throw new Exception("rtp.createConncetion: clientIP is null");
		} else if (clientPort == -1) {
			throw new Exception("rtp.createConnection: clientSocket is not connected");
		} else if (destIP == null) {
			throw new Exception("rtp.createConnection: invalid IP");
		} else if (destPort < 0) {
			throw new Exception("rtp.createConnection: invalid port");
		}
		
		
		Connection c = new Connection(clientIP, clientPort, destIP, destPort);
		String key = generateKey(clientIP.getHostAddress(), String.valueOf(clientPort));
		clientPortToConnection.put(key, c);
		return c;	
	}
	
}

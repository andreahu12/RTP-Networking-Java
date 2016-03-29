import java.net.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.io.*;

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
	private static HashMap<String, Connection> clientPortToConnection; // for demultiplexing
	private static int RECEIVE_PACKET_BUFFER_SIZE = 2048; // arbitrary value
//	private static int TIMEOUT = 2000; // arbitrary milliseconds
	private static final int MAX_SEGMENT_SIZE = 972; 
	private static DatagramSocket socket;

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
	 * @param windowSizeInBytes
	 * @param clientSocket
	 * @param serverSocket
	 * @return whether or not the connection attempt succeeded
	 * @throws Exception 
	 */
	@SuppressWarnings("finally")
	public static boolean connect(InetAddress serverIP, int serverPort, int windowSizeInBytes) throws Exception {
		
		if (socket == null) {
			System.out.println("rtp.Connect: Client Socket is null");
			return false;
		}
		
		DatagramSocket clientSocket = new DatagramSocket();
		
		String clientAddressStr = clientSocket.getInetAddress().getHostAddress();
		String clientPortStr = String.valueOf(clientSocket.getPort());
		
		int clientAddress = ByteBuffer.wrap(clientSocket.getInetAddress().getAddress()).getInt();
		int clientPort = clientSocket.getPort();
		
		if (getConnection(clientAddressStr, clientPortStr) != null) {
			System.out.println("Connection has already been established");
			return true;
		}
		
		try {
			Connection c = createConnection(
					clientSocket.getInetAddress(), clientSocket.getPort(), 
					serverIP, serverPort);
			c.setWindowSize(windowSizeInBytes);
			
			/*
			 * implementation of 3 way handshake
			 */
			
			/*
			 * HANDSHAKE 1: CLIENT --> SERVER
			 */
			// Create SYNbit = 1, Seq = x packet
			DatagramPacket SynPacketDP = makeSynPacket(serverIP, serverPort);
			
			clientSocket.send(SynPacketDP);
			
			DatagramPacket receivePacket = new DatagramPacket(
					new byte[RECEIVE_PACKET_BUFFER_SIZE],
					RECEIVE_PACKET_BUFFER_SIZE);
						
			// TODO: LOCK THIS METHOD CALL
			socket.receive(receivePacket);
			
			boolean validPacketReceived = false;
			
			while (!validPacketReceived) {
				Packet receivePacketRTP = rtpBytesToPacket(receivePacket.getData());
				if ((receivePacket != null) && (receivePacketRTP.getACK()) && (receivePacketRTP.getSYN())) {
					/*
					 * HANDSHAKE 3: CLIENT --> SERVER
					 */
					// Create ACKbit = 1, ACKnum = y+1 packet
					DatagramPacket ack = makeHandshakeAckPacket(clientAddress, clientPort, serverIP, serverPort);
					socket.send(ack);
					validPacketReceived = true;
				} else {
					socket.receive(receivePacket);
				}
			}

			return true;
			
		} catch (Exception e) {
			System.out.println("<-----------rtp.Connect Failed-------------->");
			e.printStackTrace();
		} finally {
			// remove the failed connection if necessary
			String address = clientSocket.getInetAddress().getHostAddress();
			String port = String.valueOf(clientSocket.getPort());
			if (clientPortToConnection.containsKey(generateKey(address, port))) {
				clientPortToConnection.remove(address, port);
			}
			return false;
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
		DatagramPacket p2 = new DatagramPacket(packet2bytes, packet2bytes.length, 
				clientIP, clientPort);
		return p2;
	}
	
	/**
	 * Makes a SYN packet for part 1 of the 3-way handshake
	 * @param clientAddress
	 * @param clientPort
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
	 * Accepts connect() requests from a client at the server. <br>
	 * Creates a conncection object on server-side. <br>
	 * Only to be called at the server.
	 */
	public static void accept() {
		DatagramPacket receivePacket = new DatagramPacket(
				new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);
		
		while (true) {
			boolean SynAckSent = false;
			try {
				// TODO: MAKE THIS THREAD SAFE
				socket.receive(receivePacket);
				
				if (receivePacket != null) {
					Packet rtpReceivePacket = rtpBytesToPacket(receivePacket.getData());
					InetAddress clientAddress = receivePacket.getAddress();
					int clientPort = receivePacket.getPort();
					
					if (rtpReceivePacket.getSYN()) {
						// got the syn packet from the first handshake
						// send the syn ack packet for the second handshake
						DatagramPacket SynAckPacket = makeSynAckPacket(clientAddress, clientPort);
						Connection c = createConnection(clientAddress, clientPort,
								socket.getInetAddress(), socket.getPort());
						socket.send(SynAckPacket);
						SynAckSent = true;
					} 
					
					if (SynAckSent && rtpReceivePacket.getACK()) {
						// check for ack packet in 3rd handshake
						// received ack? make a connection
						Connection c = getConnection(clientAddress.getHostAddress(), 
								String.valueOf(clientPort));
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	
	/**
	 * USER NOTE: ONLY CLIENTS SHOULD CALL THIS
	 * Closes the RTP connection using the algorithm TCP uses. <br>
	 * Only closes the client socket. <br>
	 * Leaves the server socket open since server socket needs to stay open. <br>
	 * Removes connection from rtp connection hashmap.
	 * @throws IOException 
	 */
	public static void close() throws Exception {
		String clientAddressStr = socket.getInetAddress().getHostAddress();
		String clientPortStr = String.valueOf(socket.getPort());
		Connection c = getConnection(clientAddressStr, clientPortStr);
		
		if (c != null) {
						
			// implemented TCP close algorithm
			/*
			 * PART 1 of Closing
			 */
			// SEND PACKET 1: CLIENT --> SERVER
			// FIN = 1, seq = x
			
			DatagramPacket fin1 = makeFinPacket();
			fin1.setAddress(c.getServerAddress());
			fin1.setPort(c.getServerPort());
			socket.send(fin1);			
			
			// WE ASSUME HERE THAT NO PACKETS ARE LOST, SO WE DON'T BOTHER WITH ACTUALLY WAITING 2x IN THE TIMED WAIT
			DatagramPacket receivePacket = new DatagramPacket(
					new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);
			
			// TODO: MAKE THIS THREADSAFE
			socket.receive(receivePacket);
			
			// wait to receive the fin packet from the server
			byte[] rtpPacket = null;
			boolean receivedValidPacket = false;
			while (!receivedValidPacket) {
				if (receivePacket != null) {
					rtpPacket = receivePacket.getData();
					
					if (getFinFromRtpPacket(rtpPacket)) {
						receivedValidPacket = true;
					} else {
						socket.receive(receivePacket);
					}
				}
			}
			
			// SEND ACK 2: CLIENT --> SERVER
			// ACK = 1, ACKnum = y+1
			// TODO: fix this
			
			Packet packetToAck = rtpBytesToPacket(rtpPacket);
			DatagramPacket clientAck = makeFinAckPacket(packetToAck, false);
			clientAck.setAddress(c.getServerAddress());
			clientAck.setPort(c.getServerPort());
			socket.send(clientAck);
			
			// Closes client socket
			socket.close();

			deleteConnection(clientAddressStr, clientPortStr);
		} else {
			System.out.println("cannot close nonexistent connection in rtp.close");
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
	
//	public static void closeAtServer() {
//		/*
//		 * PART 1
//		 */
//		DatagramPacket receivePacket = new DatagramPacket(new byte[RECEIVE_PACKET_BUFFER_SIZE], 
//				RECEIVE_PACKET_BUFFER_SIZE);
//		
//		while (true) {
//			try {
//				
//				socket.receive(receivePacket);
//				
//				if (receivePacket != null) {
//					byte[] rtpPacketBytes = receivePacket.getData();
//					Packet rtpPacket = rtpBytesToPacket(rtpPacketBytes);
//					
//					if (rtpPacket.getFIN()) { // got first fin packet at server
//						// send the acknowledgement
//						DatagramPacket serverAck = makeFinAckPacket(rtpPacket, true);
//						serverAck.setAddress(receivePacket.getAddress());
//						serverAck.setPort(receivePacket.getPort());
//						
//						socket.send(serverAck);
//						
//						String clientAddress = receivePacket.getAddress().getHostAddress();
//						int clientPort = receivePacket.getPort();
//						
//						Connection c = getConnection(clientAddress, String.valueOf(clientPort));
//						
//						// send the second fin packet indicating server is closing
//						DatagramPacket fin2 = makeFinPacket();
//						fin2.setAddress(c.getClientAddress());
//						fin2.setPort(c.getClientPort());
//						socket.send(fin2);
//						
//						deleteConnection(c.getClientAddress().getHostAddress(), 
//								String.valueOf(c.getClientPort()));
//						// client will send an ack after this, but it doesn't really matter
//						// since no packets are lost
//					}
//					
//				}
//			} catch (Exception e) {
//				e.printStackTrace();
//			}
//		}
//	}
	
	/**
	 * Makes a FIN acknowledgement packet for closing. <br>
	 * Used by both the client and the server. <br>
	 * NOTE TO USER: make sure to set the packet destination IP + Port<br>
	 * @param destIP
	 * @param destPort
	 * @return
	 */
	private static DatagramPacket makeFinAckPacket(Packet packetToAck, boolean toClientFromServer) {
		int ackNumber = packetToAck.getSequenceNumber() + 1;
		int sequenceNumber = packetToAck.getAckNumber();

		Packet ack = new Packet(false, false, false, sequenceNumber, ackNumber, null);
		byte[] ackBytes = ack.packetize();
		
		DatagramPacket ackDP = new DatagramPacket(ackBytes, ackBytes.length);
		
//		if (toClientFromServer) {
//			// address it to the client
//			ackDP.setAddress(c.getClientAddress());
//			ackDP.setPort(c.getClientPort());
//		} else { // sending from ServerToClient
//			// address it to the server
//			ackDP.setAddress(c.getServerAddress());
//			ackDP.setPort(c.getServerPort());
//		}
		return ackDP;
	}
	
//	/**
//	 * Writes payload data from client buffer into byte buffer
//	 * Clears the writeToBuffer before writing data to it.
//	 * This should be called at the client every time an ACK is received.
//	 * @param bytesRequested = size of a packet.
//	 * @return number of bytes read (0 if we're done)
//	 */
//	public static int readClientResult(byte[] writeToBuffer, String clientAddress, String clientPort) {
//		Connection c = getConnection(clientAddress, clientPort);
//		writeToBuffer = c.readClientResult();
//		
//		if (writeToBuffer != null) {
//			return writeToBuffer.length;
//		} else {
//			return 0;
//		}
//	}
//	
//	/**
//	 * Writes payload data from server buffer into byte buffer
//	 * Clears the writeToBuffer before writing data to it.
//	 * This should be called at the server every time an ACK is sent.
//	 * @return received message size (-1 if we're busy waiting, 0 if we're done)
//	 */
//	public static int readServerResult(byte[] writeToBuffer, String clientAddress, String clientPort) {
//		
//		Connection c = getConnection(clientAddress, clientPort);
//		writeToBuffer = c.readServerResult();
//		
//		if (!c.getCanReadFromServerResultBuffer()) {
//			return -1;
//		}
//		if (writeToBuffer != null) {
//			return writeToBuffer.length;
//		} else {
//			return 0;
//		}
//	}
	
	/**
	 * Given a data stream (byte buffer) at the client, it packetizes the data and sends it to the server. <p>
	 * At the same time, it needs to: <br>
	 *  - receive acknowledgements from the server <br>
	 *  - buffer packets at server <br>
	 *  - buffer packets at client <br>
	 *  - make sure the number of unacknowledged packets does not exceed the window size <br>
	 *  - make sure the number of packets at the server/client buffer does not exceed the max buffer size <br>
	 *  <p>
	 *  By the end of this, the server result and client results should be complete and ready for reading.
	 * @param toServer
	 * @param clientAddress
	 * @param clientPort
	 * @return
	 */
//	public static void writeStreamFromClientToServer(byte[] data) throws Exception {
//		String clientAddress = socket.getInetAddress().getHostAddress();
//		String clientPort = String.valueOf(socket.getPort());
//		
//		Connection c = getConnection(clientAddress, clientPort);
//		if (c == null) {
//			throw new Exception("rtp.writeStreamFromClientToServer: connection does not exist");
//		} else if (data == null) {
//			System.out.println("rtp.writeStreamFromClientToServer: no data to send");
//		}
//		
//		int windowSize = c.getMAX_WINDOW_SIZE();
////		Queue<Packet> unacknowledgedPackets = new LinkedList<Packet>();
//		
//		/**
//		 while packets_sent < window_size: (send initial packets to server all at once)
//			 client.send packet
//			 server.receive packet
//			 if packet is not a dup
//				 unacknowledgedSeqNums.add(packet.getseqnum)
//		 while dataIndex < data.length: (start sending incrementally)
//			 server sends next ack from the queue
//			 move one packet-size of data from the serverQ to the serverResult
//			 client.receive ack
//			 if ack is not a dup:
//				 update clientQ and clientResult
//			 client sends next packet (dataindex to dataindex + packetsize)
//			 dataindex += packetsize
//		 **/
//
//		// acknowledge packets and send more as necessary
////		int startOfNextPayload = sendUpToTheWindowSizeToServer(data, c, windowSize, unacknowledgedPackets);
////		int lastIndexInData = data.length - 1;
////		int dataRemaining = lastIndexInData - startOfNextPayload;
//		
//		
////		if (dataRemaining <= 0) { // entire data array has already been sent
////			acknowledgeAllUnackedPacketsAtClient(unacknowledgedPackets, c);
////			unloadRemainingBuffersToResultQueues(c);
////			return;
////		}
//		
////		// while there is data to send, send one packet and acknowledge it incrementally
////		while (startOfNextPayload < lastIndexInData) {
////			sendAck(unacknowledgedPackets.poll(), c);
////			receiveAckAtClient(c);
////			sendPacketToServerAndReceiveAtServer(startOfNextPayload, dataRemaining, data, c, unacknowledgedPackets);
////			
////			startOfNextPayload = startOfNextPayload + MAX_SEGMENT_SIZE;
////		}
//		
//		
//		
//		// at this point, all data should have been sent
//		// acknowledge the remaining unacknowledged packets
//		// unload the remaining buffers
////		acknowledgeAllUnackedPacketsAtClient(unacknowledgedPackets, c);
////		unloadRemainingBuffersToResultQueues(c);
//		
//		
//	}
	
	/**
	 * Converts data to a bytestream and sends packets to the other end.
	 * TODO: THIS ONLY SENDS FROM CLIENT TO SERVER FOR NOW
	 * TODO: FIND A WAY TO TELL THE SERVER WHICH CLIENT TO SEND TO
	 * @param data
	 */
	public static void write(byte[] data) {
		// TODO: set the destination of each datagram packet
		Queue<DatagramPacket> packetsToSend = convertStreamToPacketQueue(data);
		Connection c = getConnection(socket.getInetAddress().getHostAddress(), 
				String.valueOf(socket.getPort()));

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
	 * @param writeToBuffer
	 * @param numBytesRequested
	 * @return number of bytes read
	 */
	public static int read(byte[] writeToBuffer, int numBytesRequested) {
		Connection c = getConnection(socket.getInetAddress().getHostAddress(), 
				String.valueOf(socket.getPort()));
		
		int numBytesReturned = Math.min(numBytesRequested, c.getReceiveBufferSize());
		
		writeToBuffer = new byte[numBytesReturned];
		
		for (int i = 0; i < numBytesReturned; i++) {
			writeToBuffer[i] = c.getReceiveBuffer().poll();
		}
		
		return numBytesReturned;
	}
	
	
	
//	/**
//	 * Sends byte[] data to the server and acknowledges it.
//	 * Sets the flag in the connection so that we can read from client buffer when we're done.
//	 * @param data
//	 * @param clientAddress
//	 * @param clientPort
//	 * @throws Exception
//	 */
//	public static void writeStreamFromServerToClient(byte[] data) throws Exception {
//		Connection c = getConnection(clientAddress, clientPort);
//		if (c == null) {
//			throw new Exception("rtp.writeStreamFromServerToClient: connection does not exist");
//		}
//		
//		int windowSize = c.getMAX_WINDOW_SIZE();
//		Queue<Packet> unacknowledgedPackets = new LinkedList<Packet>();
//		
//		
//		// acknowledge packets and send more as necessary
//		int startOfNextPayload = sendUpToTheWindowSizeToClient(data, c, windowSize, unacknowledgedPackets);
//		int lastIndexInData = data.length - 1;
//		int dataRemaining = lastIndexInData - startOfNextPayload;
//		
//		
//		
//		if (dataRemaining <= 0) { // entire data array has already been sent
//			acknowledgeAllUnackedPacketsAtClient(unacknowledgedPackets, c);
//			unloadRemainingBuffersToResultQueues(c);
//			return;
//		}
//		
//		// while there is data to send, send one packet and acknowledge it incrementally
//		while (startOfNextPayload < lastIndexInData) {
//			sendAckToServer(unacknowledgedPackets.poll(), c);
//			receiveAckAtServer(c);
//			sendPacketToClientAndReceiveAtClient(startOfNextPayload, dataRemaining, data, c, unacknowledgedPackets);
//			
//			startOfNextPayload = startOfNextPayload + MAX_SEGMENT_SIZE;
//		}
//		
//		// at this point, all data should have been sent
//		// acknowledge the remaining unacknowledged packets
//		// unload the remaining buffers
//		acknowledgeAllUnackedPacketsAtClient(unacknowledgedPackets, c);
//		unloadRemainingBuffersToResultQueues(c);
//		
//	}
	
	
//	/**
//	 * Server sends and Client receives an ack for all the unacknowledged packets remaining. <p>
//	 * @param unacknowledgedPackets
//	 * @param c
//	 * @throws IOException
//	 */
//	private static void acknowledgeAllUnackedPacketsAtClient(Queue<Packet> unacknowledgedPackets, Connection c) throws IOException {
//		// send acknowledgements (Server --> Client) for all the packets
//		for (Packet p : unacknowledgedPackets) {
//			sendAckToServer(p, c);
//			receiveAckAtServer(c);
//			// no data to send from client-->server so we skip that part			
//		}
//	}
//	
//	/**
//	 * Receives an acknowledgement at the server. <p>
//	 * Moves 1 packet's worth of data from buffer to result at client and server.
//	 * 
//	 * @param c
//	 * @throws IOException
//	 */
//	private static void receiveAckAtServer(Connection c) throws IOException {
//		
//		// receive the ack at server
//		DatagramPacket receivePacket = new DatagramPacket(new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);
//		c.getServerSocket().receive(receivePacket);
//		byte[] receiveBytes = receivePacket.getData();
//		// move ack payload to the Server buffer
//		c.addToServerBuffer(getAckNumFromRtpPacket(receiveBytes), getPayloadFromRtpPacket(receiveBytes));
//		int serverBytesToMove = Math.min(c.getCurrentClientBufferSize(), Packet.getMaxSegmentSize());
//		// move 1 packet's worth of data or less to the Server result
//		c.moveBytesFromBufferToServerResult(serverBytesToMove);
//		
//		// move 1 packet's worth of data or less to the Client result
//		int clientBytesToMove = Math.min(c.getCurrentClientBufferSize(), Packet.getMaxSegmentSize());
//		c.moveBytesFromBufferToServerResult(clientBytesToMove);
//	}

//	/**
//	 * Sends an ack to the Server using data from the packet passed in.
//	 * @param p
//	 * @param c
//	 * @throws IOException
//	 */
//	
//	private static void sendAckToServer(Packet p, Connection c) throws IOException {
//		
//		int clientAddressInt = ByteBuffer.wrap(c.getClientAddress().getAddress()).getInt();
//		int clientPortInt = c.getClientPort();
//		
//		int newAckNum = p.getSequenceNumber() + 1;
//		int newSeqNum = p.getAckNumber();
//		Packet ack = new Packet(false, true, false, newSeqNum, newAckNum, clientAddressInt, clientPortInt, null);
//		ack.setRemainingBufferSize(c.getRemainingServerBufferSize());
//		byte[] ackBytes = ack.packetize();
//		DatagramPacket dpAck = new DatagramPacket(ackBytes, ackBytes.length, c.getServerAddress(), c.getServerPort());
//		c.getClientSocket().send(dpAck);
//	}

//	/**
//	 * Receives an acknowledgement at the client. <p>
//	 * Moves 1 packet's worth of data from buffer to result at client and server.
//	 * 
//	 * @param c
//	 * @throws IOException
//	 */
//	private static void receiveAckAtClient(Connection c) throws IOException {
//		// receive the ack
//		DatagramPacket receivePacket = new DatagramPacket(new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);
//		c.getClientSocket().receive(receivePacket);
//		byte[] receiveBytes = receivePacket.getData();
//		// move ack payload to the client buffer
//		c.addToClientBuffer(getAckNumFromRtpPacket(receiveBytes), getPayloadFromRtpPacket(receiveBytes));
//		int clientBytesToMove = Math.min(c.getCurrentClientBufferSize(), Packet.getMaxSegmentSize());
//		// move 1 packet's worth of data or less to the client result
//		c.moveBytesFromBufferToClientResult(clientBytesToMove);
//		
//		// move 1 packet's worth of data or less to the server result
//		int serverBytesToMove = Math.min(c.getCurrentServerBufferSize(), Packet.getMaxSegmentSize());
//		c.moveBytesFromBufferToServerResult(serverBytesToMove);
//	}
	

	/**
	 * Sends an ack using data from the packet passed in.
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
	
	
//	/**
//	 * Sends up to the window size # of packets to Server but does not acknowledge them
//	 * @param data
//	 * @param c
//	 * @param windowSize
//	 * @param unacknowledgedPackets
//	 * @return the number of bytes that haven't been sent by the client
//	 * @throws IOException 
//	 */
//	private static int sendUpToTheWindowSizeToServer(byte[] data, Connection c, int windowSize, Queue<Packet> unacknowledgedPackets) throws IOException {
//		int dataRemaining = 0;
//		int startOfNextPayload = 0;
//		int lastIndexOfData = data.length - 1;
//		for (int packetsSent = 0; packetsSent < windowSize; packetsSent++) {
//			startOfNextPayload = packetsSent * MAX_SEGMENT_SIZE;
//			dataRemaining = lastIndexOfData - startOfNextPayload; // in bytes
//			
//			sendPacketToServerAndReceiveAtServer(startOfNextPayload, dataRemaining, data, c, unacknowledgedPackets);
//		}
//		return startOfNextPayload;
//	}
	
//	/**
//	 * Sends up to the window size # of packets to Client but does not acknowledge them
//	 * @param data
//	 * @param c
//	 * @param windowSize
//	 * @param unacknowledgedPackets
//	 * @return the number of bytes that haven't been sent by the server
//	 * @throws IOException 
//	 */
//	private static int sendUpToTheWindowSizeToClient(byte[] data, Connection c, int windowSize, Queue<Packet> unacknowledgedPackets) throws IOException {
//		int dataRemaining = 0;
//		int startOfNextPayload = 0;
//		int lastIndexOfData = data.length - 1;
//		for (int packetsSent = 0; packetsSent < windowSize; packetsSent++) {
//			startOfNextPayload = packetsSent * MAX_SEGMENT_SIZE;
//			dataRemaining = lastIndexOfData - startOfNextPayload; // in bytes
//			
//			sendPacketToClientAndReceiveAtClient(startOfNextPayload, dataRemaining, data, c, unacknowledgedPackets);
//		}
//		return startOfNextPayload;
//	}
	
//	/**
//	 * Sends a packet from the byte[] data to Client
//	 * @param startOfNextPayload
//	 * @param dataRemaining
//	 * @param data
//	 * @param c
//	 * @param unacknowledgedPackets
//	 * @throws IOException 
//	 */
//	private static void sendPacketToClientAndReceiveAtClient(
//			int startOfNextPayload, int dataRemaining, byte[] data,
//			Connection c, Queue<Packet> unacknowledgedPackets) throws IOException {
//		
//		int clientAddressInt = ByteBuffer.wrap(c.getClientAddress().getAddress()).getInt();
//		byte[] payload;
//		
//		if (dataRemaining > MAX_SEGMENT_SIZE) {
//			// haven't reached the end
//			payload = new byte[MAX_SEGMENT_SIZE];
//			// copy over the payload
//			for (int i = 0; i < MAX_SEGMENT_SIZE; i++) {
//				payload[i] = data[i + startOfNextPayload];
//			}
//		} else if ((dataRemaining < MAX_SEGMENT_SIZE) && (dataRemaining > 0)){
//			// the last packet is within the max window size
//			payload = new byte[dataRemaining];
//			// copy over the last packet
//			for (int i = 0; i < dataRemaining; i++) {
//				payload[i] = data[i + startOfNextPayload];
//			}
//		} else { 
//			// we've sent all the packets before the max window size
//			return;
//		}
//		// send packet to Client
//		Packet rtpToClient = new Packet(false, false, false, startOfNextPayload, 0, clientAddressInt, c.getClientPort(), payload);
//		rtpToClient.setRemainingBufferSize(c.getRemainingClientBufferSize());
//		DatagramPacket packetToClient = new DatagramPacket(payload, payload.length, c.getClientAddress(), c.getClientPort());
//		c.getServerSocket().send(packetToClient);
//		// receive packet at Client
//		DatagramPacket receivePacket = new DatagramPacket(new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);
//		c.getServerSocket().receive(receivePacket);
//		// convert to rtp packet
//		byte[] rtpResultBytes = receivePacket.getData(); 
//		Packet rtpResultPacket = rtpBytesToPacket(rtpResultBytes);
//		
//		// makes sure it isn't a duplicate before adding to the server buffer; does not acknowledge; adds to hashset
//		c.addToClientBuffer(rtpResultPacket.getAckNumber(), rtpResultPacket.getPayload());
//		unacknowledgedPackets.add(rtpResultPacket);
//		
//	}

//	/**
//	 * Sends a packet from the byte[] data to server
//	 * @param startOfNextPayload
//	 * @param dataRemaining
//	 * @param data
//	 * @param c
//	 * @param unacknowledgedPackets
//	 * @throws IOException
//	 */
//	private static void sendPacketToServerAndReceiveAtServer(int startOfNextPayload, int dataRemaining, byte[] data, Connection c,
//			Queue<Packet> unacknowledgedPackets) throws IOException {
//		int clientAddressInt = ByteBuffer.wrap(c.getClientAddress().getAddress()).getInt();
//		byte[] payload;
//		
//		if (dataRemaining > MAX_SEGMENT_SIZE) {
//			// haven't reached the end
//			payload = new byte[MAX_SEGMENT_SIZE];
//			// copy over the payload
//			for (int i = 0; i < MAX_SEGMENT_SIZE; i++) {
//				payload[i] = data[i + startOfNextPayload];
//			}
//		} else if ((dataRemaining < MAX_SEGMENT_SIZE) && (dataRemaining > 0)){
//			// the last packet is within the max window size
//			payload = new byte[dataRemaining];
//			// copy over the last packet
//			for (int i = 0; i < dataRemaining; i++) {
//				payload[i] = data[i + startOfNextPayload];
//			}
//		} else { 
//			// we've sent all the packets before the max window size
//			return;
//		}
//		// send packet
//		Packet rtpToServer = new Packet(false, false, false, startOfNextPayload, 0, clientAddressInt, c.getClientPort(), payload);
//		rtpToServer.setRemainingBufferSize(c.getRemainingClientBufferSize());
//		DatagramPacket packetToServer = new DatagramPacket(payload, payload.length, c.getServerAddress(), c.getServerPort());
//		c.getClientSocket().send(packetToServer);
//		// receive packet
//		DatagramPacket receivePacket = new DatagramPacket(new byte[RECEIVE_PACKET_BUFFER_SIZE], RECEIVE_PACKET_BUFFER_SIZE);
//		c.getServerSocket().receive(receivePacket);
//		// convert to rtp packet
//		byte[] rtpResultBytes = receivePacket.getData(); 
//		Packet rtpResultPacket = rtpBytesToPacket(rtpResultBytes);
//		
//		// makes sure it isn't a duplicate before adding to the server buffer; does not acknowledge; adds to hashset
//		c.addToServerBuffer(rtpResultPacket.getAckNumber(), rtpResultPacket.getPayload());
//		unacknowledgedPackets.add(rtpResultPacket);
//	}
	
	/**
	 * Converts an array of rtpResultBytes into an rtp packet object
	 * @param rtpResultBytes
	 * @return
	 * @throws Exception 
	 */
	private static Packet rtpBytesToPacket(byte[] rtpResultBytes) throws Exception {
		if (rtpResultBytes == null) {
			throw new Exception("rtp.rtpBytesToPacket: rtpResultBytes is null");
		}
		boolean FIN = getFinFromRtpPacket(rtpResultBytes);
		boolean ACK = getAckFromRtpPacket(rtpResultBytes);
		boolean SYN = getSynFromRtpPacket(rtpResultBytes);
		int seqNum = getSeqNumFromRtpPacket(rtpResultBytes);
		int ackNum = getAckNumFromRtpPacket(rtpResultBytes);
		int remainingBufferSize = getRemainingBufferSizeFromRtpPacket(rtpResultBytes);
		byte[] payload = getPayloadFromRtpPacket(rtpResultBytes);
		
		Packet result = new Packet(FIN, ACK, SYN, seqNum, ackNum, payload);
		result.setRemainingBufferSize(remainingBufferSize);
		
		return result;
	}


	
	/*
	 * PRIVATE METHODS
	 */
	
	private static boolean getFinFromRtpPacket(byte[] rtpPacket) {
		ByteBuffer b = ByteBuffer.wrap(rtpPacket);
		int resultAsInt = b.getInt(0);
		if (resultAsInt == 1) {
			return true;
		}
		return false;
	}
	
	private static boolean getAckFromRtpPacket(byte[] rtpPacket) {
		ByteBuffer b = ByteBuffer.wrap(rtpPacket);
		int resultAsInt = b.getInt(1);
		if (resultAsInt == 1) {
			return true;
		}
		return false;
	}
	
	private static boolean getSynFromRtpPacket(byte[] rtpPacket) {
		ByteBuffer b = ByteBuffer.wrap(rtpPacket);
		int resultAsInt = b.getInt(2);
		if (resultAsInt == 1) {
			return true;
		}
		return false;
	}
	
	private static int getSeqNumFromRtpPacket(byte[] rtpPacket) {
		ByteBuffer b = ByteBuffer.wrap(rtpPacket);
		int resultAsInt = b.getInt(3);
		return resultAsInt;
	}
	
	private static int getAckNumFromRtpPacket(byte[] rtpPacket) {
		ByteBuffer b = ByteBuffer.wrap(rtpPacket);
		int resultAsInt = b.getInt(4);
		return resultAsInt;
	}
	
	private static int getRemainingBufferSizeFromRtpPacket(byte[] rtpPacket) {
		ByteBuffer b = ByteBuffer.wrap(rtpPacket);
		int resultAsInt = b.getInt(5);
		return resultAsInt;
	}
	
	private static int getPayloadSizeFromRtpPacket(byte[] rtpPacket) {
		ByteBuffer b = ByteBuffer.wrap(rtpPacket);
		int resultAsInt = b.getInt(6);
		return resultAsInt;
	}
	
	private static byte[] getPayloadFromRtpPacket(byte[] rtpPacket) {
		int payloadSize = getPayloadSizeFromRtpPacket(rtpPacket);
		int payloadStartIndex = 28; // first 28 (indices 0-27) bytes are header values
		byte[] result = new byte[payloadSize];
		
		// copy the payload over
		for (int i = 0; i < payloadSize; i++) {
			result[i] = rtpPacket[payloadStartIndex + i];
		}
		
		return result;
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
			System.out.println("cannot retrieve connection via rtp.getConnection");
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

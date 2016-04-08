
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Connections represent RTP connections for easy variable access
 * within the RTP class.
 * @author andreahu
 *
 */
public class Connection {

	private static final int MAX_RTP_PACKET_SIZE = 1000;
	
	private HashSet<Integer> receivedSequenceNumbers; // for checking for duplicates via seq num
	private HashSet<Integer> receivedAckNumbers; // for checking for duplicates via ack num
	
	private InetAddress serverAddress;
	private int serverPort;
	private InetAddress clientAddress; 
	private int clientPort;
	
	//flow control variables
	private int MAX_WINDOW_SIZE; // of our recieve buffer
	private Queue<Byte> sendBuffer;
	// remainingBufferSize = recieveBuffer max size - size(receiveBuffer)
	private Queue<Byte> receiveBuffer; 
	private int lastByteSent; // LastByteSent - LastByteAcked <= rwnd
	private int lastByteAcked;
	// rwnd is sent in the header and calculated in send
	
	/*
	 * CONSTRUCTOR
	 */
	
	/**
	 * Creates a new Connection object. Does not instantiate the window size
	 */
	public Connection(InetAddress clientIP, int clientPort, InetAddress serverIP, int serverPort) {
		
		receivedSequenceNumbers = new HashSet<Integer>();
		receivedAckNumbers = new HashSet<Integer>();
		
		serverAddress = serverIP;
		clientAddress = clientIP;
		this.serverPort = serverPort;
		this.clientPort = clientPort;

		sendBuffer = new LinkedList<Byte>();
		receiveBuffer = new LinkedList<Byte>();		
	}
	
	/*
	 * METHODS BELOW
	 */
	
	public int getServerPort() {
		return serverPort;
	}
	
	public int getClientPort() {
		return clientPort;
	}
	
	public int getMAX_WINDOW_SIZE() {
		return MAX_WINDOW_SIZE;
	}

	public void setWINDOW_SIZE(int size) {
		MAX_WINDOW_SIZE = size * MAX_RTP_PACKET_SIZE;
	}
	
	public Queue<Byte> getReceiveBuffer() {
		return receiveBuffer;
	}
	
	/**
	 * Only adds non-duplicate ack payloads to the client buffer.
	 * Does not acknowledge the packet.
	 * Adds sequence number to hash map.
	 * @param ackNum
	 * @param payload
	 */
	public void addToReceiveBuffer(int ackNum, byte[] payload) {
		if (!isDuplicateAckNum(ackNum)) {
			receivedAckNumbers.add(ackNum);
			int payloadSize = payload.length;
			for (int i = 0; i < payloadSize; i++) {
				receiveBuffer.add(payload[i]);
			}
		}
	}
	
	/**
	 * Only adds non-duplicate sequence number payloads to the server buffer. 
	 * Does not acknowledge the packet.
	 * Adds to the sequence number to hash map.
	 * @param ackNum
	 * @param payload
	 */
	public void addToSendBuffer(byte[] rtpPacket) {
		int packetsize = rtpPacket.length;
		for (int i = 0; i < packetsize; i++) {
			sendBuffer.add(rtpPacket[i]);
		}
	}

	/**
	 * 
	 * @param sequenceNumber
	 * @return whether we've already received this sequence number
	 */
	public boolean isDuplicateSeqNum(int sequenceNumber) {
		return receivedSequenceNumbers.contains(sequenceNumber);
	}
	
	/**
	 * 
	 * @param ackNum
	 * @return whether we've already received this acknoweldgement
	 */
	public boolean isDuplicateAckNum(int ackNum) {
		return receivedAckNumbers.contains(ackNum);
	}
	
	
	/**
	 * Sets the window size. Do this during rtp.connect().
	 * @param size
	 */
	public void setWindowSize(int size) {
		this.setWINDOW_SIZE(size);
	}
	
	/**
	 * Use to get the value for an ACK header
	 * @return number of bytes remaining in client buffer
	 */
	public int getRemainingReceiveBufferSize() {
		return MAX_WINDOW_SIZE * MAX_RTP_PACKET_SIZE - receiveBuffer.size();
	}
	
	/**
	 * For demultiplexing
	 * @return server address containing IP # and Host #
	 */
	public InetAddress getServerAddress() {
		return serverAddress;
	}
	
	/**
	 * For demultiplexing
	 * @return client address containing IP # and Host #
	 */
	public InetAddress getClientAddress() {
		return clientAddress;
	}
	
	public int getReceiveBufferSize() {
		return receiveBuffer.size();
	}
	
	public int getSendBufferSize() {
		return sendBuffer.size();
	}
	
	

	
	/**
	 * Returns the min(numBytes, length of the queue) bytes of the 
	 * receiveBuffer in a byte array. 
	 * 
	 * This is done on a new array each time so there should not be any legacy data.
	 * @param numBytes
	 * @return byte array of the entire result from server, or null if no result
	 */

	public byte[] readReceiveResult(int bytes) {
		return readResult(bytes, receiveBuffer);
	}
	
	/**
	 * Takes a byte queue and pops the appropriate number of bytes
	 * into a byte array and returns it. This is done on a new array 
	 * each time so there should not be any legacy data.
	 * @param numBytes
	 * @param buffer
	 * @return byte array of the entire result, or null if there is no result
	 */
	private byte[] readResult(int numBytes, Queue<Byte> buffer) {
		int numBytesToRead = Math.min(numBytes, buffer.size());
		
		if (numBytesToRead == 0) {
			return null;
		} else {
			byte[] result = new byte[numBytesToRead];
			
			for (int i = 0; i < numBytesToRead; i++) {
				result[i] = buffer.poll();
			}
			
			return result;
		}
	}
	
}


import org.omg.CORBA.DATA_CONVERSION;

import javax.xml.crypto.Data;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Connections represent RTP connections for easy variable access
 * within the RTP class.
 * Holds connection information.
 * @author andreahu, jeffersonwang
 *
 */
public class Connection {

	private static final int MAX_RTP_PACKET_SIZE = 1000;

	private HashSet<Integer> receivedSequenceNumbers; // for checking for duplicates via seq num
	private HashSet<Integer> receivedAckNumbers; // for checking for duplicates via ack num
	
	private InetAddress remoteAddress;
	private int remotePort;
	private InetAddress localAddress;
	private int localPort;
	
	//flow control variables
	private int maxLocalWindowSize; //of local receive buffer. initialized in connect/accept
	private LinkedBlockingQueue<Byte> sendBuffer;
	// remainingBufferSize = recieveBuffer max size - size(receiveBuffer)
	private LinkedBlockingQueue<DatagramPacket> receiveBuffer;
    private Queue<Byte> receiveRemainder; //used in receive
    // remainingBufferSize = recieveBuffer max size - size(receiveBuffer)
    private LinkedBlockingQueue<DatagramPacket> ackBuffer;
	private int lastByteSent; // LastByteSent - LastByteAcked <= rwnd
	private int lastByteAcked;
	// rwnd is sent in the header and calculated in send
    public int remainingMessageSize; //>0 if in the middle of a message, used in receive
    public int remoteReceiveWindowRemaining; //initialized in conect

	/*
	 * CONSTRUCTOR
	 */
	
	/**
	 * Creates a new Connection object. Does not instantiate the window size
	 */
	public Connection(InetAddress localIP, int localPort, InetAddress remoteIP, int remotePort) {
		
		receivedSequenceNumbers = new HashSet<Integer>();
		receivedAckNumbers = new HashSet<Integer>();
		
		remoteAddress = remoteIP;
		localAddress = localIP;
		this.remotePort = remotePort;
		this.localPort= localPort;

		sendBuffer = new LinkedBlockingQueue<Byte>();
		receiveBuffer = new LinkedBlockingQueue<DatagramPacket>();
        receiveRemainder = new LinkedList<Byte>();
        ackBuffer = new LinkedBlockingQueue<DatagramPacket>();

        remainingMessageSize = 0;
        remoteReceiveWindowRemaining = 0;
	}
	
	/*
	 * METHODS BELOW
	 */
	
	public int getRemotePort() {
		return remotePort;
	}
	
	public int getLocalPort() {
		return localPort;
	}
	
	public int getMaxLocalWindowSize() {
		return maxLocalWindowSize;
	}

	public void setMaxLocalWindowSize(int size) {
		maxLocalWindowSize = size;
	}
	
	public LinkedBlockingQueue<DatagramPacket> getReceiveBuffer() {
		return receiveBuffer;
	}

    public Queue<Byte> getReceiveRemainder() {
        return receiveRemainder;
    }

    public LinkedBlockingQueue<DatagramPacket> getAckBuffer() {
        return ackBuffer;
    }

    /**
	 * Only adds non-duplicate ack payloads to the client buffer.
	 * Does not acknowledge the packet.
	 * Adds sequence number to hash map.
	 * @param ackNum
	 * @param payload
	 */
//	public void addToReceiveBuffer(int ackNum, byte[] payload) {
//		if (!isDuplicateAckNum(ackNum)) {
//			receivedAckNumbers.add(ackNum);
//			int payloadSize = payload.length;
//			for (int i = 0; i < payloadSize; i++) {
//				receiveBuffer.add(payload[i]);
//			}
//		}
//	}
	
	/**
	 * Only adds non-duplicate sequence number payloads to the send buffer.
	 * Does not acknowledge the packet.
	 * Adds to the sequence number to hash map.
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
	 * Use to get the value for an ACK header
	 * @return number of bytes remaining in receive buffer
	 */
	public int getRemainingReceiveBufferSize() {
		return maxLocalWindowSize * MAX_RTP_PACKET_SIZE - receiveBuffer.size();
	}
	
	/**
	 * For demultiplexing
	 * @return remote address containing IP # and Host #
	 */
	public InetAddress getRemoteAddress() {
		return remoteAddress;
	}
	
	/**
	 * For demultiplexing
	 * @return local address containing IP # and Host #
	 */
	public InetAddress getLocalAddress() {
		return localAddress;
	}

	public int getSendBufferSize() {
		return sendBuffer.size();
	}
	
	

	
	/**
	 * Returns the min(numBytes, length of the queue) bytes of the 
	 * receiveBuffer in a byte array. 
	 * 
	 * This is done on a new array each time so there should not be any legacy data.
	 * @param bytes
	 * @return byte array of the entire result from server, or null if no result
	 */

//	public byte[] readReceiveResult(int bytes) {
//		return readResult(bytes, receiveBuffer);
//	}
	
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

    public int getLastByteSent() {
        return lastByteSent;
    }

    public void setLastByteSent(int lastByteSent) {
        this.lastByteSent = lastByteSent;
    }

    public int getLastByteAcked() {
        return lastByteAcked;
    }

    public void setLastByteAcked(int lastByteAcked) {
        this.lastByteAcked = lastByteAcked;
    }
}


import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.Queue;
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

	private ConcurrentHashMap<Integer, String> receivedSequenceNumbers; // for checking for duplicates via seq num
	private ConcurrentHashMap<Integer, String> receivedAckNumbers; // for checking for duplicates via ack num
	
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
//	private int lastByteSent; // LastByteSent - LastByteAcked <= rwnd
//	private int lastByteAcked; //not really used with abotve
	// rwnd is sent in the header and calculated in send
    public int remainingMessageSize; //>0 if in the middle of a message, used in receive
    public int remoteReceiveWindowRemaining; //initialized in conect

    public int lastSeqReceived; //for out of order detection
    private int lastSeqSent; //for creating seq nums

    //congestion control variables
    public boolean isSlowStart;
    public int congestionWindow;
    public int ssthresh;

    /*
     * For timeout checking. 
     * ASSUMPTION: timeouts are unique (no two packets are made at the same time)
     */
    
    // set up in rtp.send(); lets us know which timeouts have been satisfied in MultiplexData.run
    ConcurrentHashMap<Integer, Long> ackNumToTimeout = new ConcurrentHashMap<Integer, Long>();
    
    // set up rtp.send(); timeouts should be in chronological order, so index 0 is going to expire next
    ConcurrentLinkedQueue<Long> timeoutList = new ConcurrentLinkedQueue<Long>();
    
    // set up in rtp.send(); maps timeouts to DatagramPackets so that we can easily reset the packetsToSend (GBN)
    ConcurrentHashMap<Long, DatagramPacket> timeoutToDatagramPacket = new ConcurrentHashMap<Long, DatagramPacket>();
    
    
 
    
	/*
	 * CONSTRUCTOR
	 */
	
	/**
	 * Creates a new Connection object. Does not instantiate the window size
	 */
	public Connection(InetAddress localIP, int localPort, InetAddress remoteIP, int remotePort) {
		
		receivedSequenceNumbers = new ConcurrentHashMap<Integer, String>();
		receivedAckNumbers = new ConcurrentHashMap<Integer, String>();
		
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

        lastSeqReceived = -1;
        lastSeqSent = 0;

        isSlowStart = true;
        congestionWindow = 1;
        ssthresh = 1;
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
		return receivedSequenceNumbers.containsKey(sequenceNumber);
	}
	
	/**
	 * Adds the sequence number to the hash set for checking for duplicates later.
	 * @param seqNum
	 */
	public void addToReceivedSeqNum(int seqNum) {
		receivedSequenceNumbers.put(seqNum, new String(""));
	}
	
	/**
	 * Clears the sequence numbers that have been received. <br>
	 * This should be called for every message in rtp.send().
	 */
	public void clearReceivedSeqNum() {
		receivedSequenceNumbers = new ConcurrentHashMap<Integer, String>();
	}
	
	/**
	 * 
	 * @param ackNum
	 * @return whether we've already received this acknowledgement
	 */
	public boolean isDuplicateAckNum(int ackNum) {
		return receivedAckNumbers.containsKey(ackNum);
	}
	
	/**
	 * Adds the acknowledgement number to the hash set for checking for duplicates later.
	 * @param ackNum
	 */
	public void addToReceivedAckNum(int ackNum) {
		receivedAckNumbers.put(ackNum, new String(""));
	}
	
	public void clearReceivedAckNum() {
		receivedAckNumbers = new ConcurrentHashMap<Integer, String>();
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
	 * TODO: IMPLEMENT checksum
	 * Takes in an ACK packet and makes sure it's not a duplicate
	 * and passes checksum.
	 * @param p an ack packet
	 * @return whether it is a valid ack packet
	 * @throws Exception 
	 */
	public boolean isValidAck(Packet p) throws Exception {
		boolean passedChecksum = checksum(p);
		boolean notDup = !isDuplicateAckNum(p.getAckNumber());
		return passedChecksum && notDup;
	}
	
	/**
	 * Takes an rtp data packet and makes sure it's not a duplicate
	 * and passes checksum.
	 * @param p a data packet
	 * @return whether it is a valid data packet
	 * @throws Exception 
	 */
	public boolean isValidDataPacket(Packet p) throws Exception {
		boolean passedChecksum = checksum(p);
		boolean notDup = !isDuplicateSeqNum(p.getSequenceNumber());
		return passedChecksum && notDup;
	}
	
	
	/**
	 * TODO: IMPLEMENT CHECKSUM
	 * @param p
	 * @return
	 * @throws Exception 
	 */
	private boolean checksum(Packet p) throws Exception {
		int calculatedChecksum = p.calculateChecksum();
		int receivedChecksum = p.getChecksum();
		boolean result =  (calculatedChecksum == receivedChecksum);

//		System.out.println("connection.checksum: expected: " + receivedChecksum + " | actual: "+ calculatedChecksum);
		
		return result;
	}
	
	/**
	 * Resets timeoutList, timeoutToDatagramPacket, ackNumToTimeout
	 */
	public void resetTimeouts() {
		timeoutList = new ConcurrentLinkedQueue<Long>();
		timeoutToDatagramPacket = new ConcurrentHashMap<Long, DatagramPacket>();
		ackNumToTimeout = new ConcurrentHashMap<Integer, Long>();
	}
	
	/**
	 * Updates timeoutList, timeoutToDatagramPacket, ackNumToTimeout
	 * @param timeout
	 * @param dp
	 * @param expectedAckNum
	 */
	public void addTimeout(Long timeout, DatagramPacket dp, int expectedAckNum) {
//		System.out.println("\nconnection.addTimeout: added timeout with expectedAckNum = " + expectedAckNum);
		timeoutList.add(timeout);
		
//		System.out.println("connection.addTimeout: adding timeout " + timeout + " to timeoutList");
		
		timeoutToDatagramPacket.put(timeout, dp);
		
//		System.out.println("connection.addTimeout: adding timeout " + timeout + " and dp: " + dp + " to timeoutList");
		
		ackNumToTimeout.put(expectedAckNum, timeout);
		
//		System.out.println("connection.addTimeout: adding expectedAckNum " + timeout + " and timeout: " + timeout + " to ackNumToTimeout");
	}
	
	/**
	 * Checks if a packet has timed out. <br>
	 * Use in rtp.send()
	 * @return
	 */
	private boolean hasTimedOutPacket() {
		if (timeoutList.isEmpty()) {
			return false;
		} else {
			Long quickestTimeout = timeoutList.peek();
			Long currentTime = System.currentTimeMillis();
			return quickestTimeout < currentTime;
		}
	}
	
	/**
	 * Gets timeout from timeoutList to retrieve corresponding DatagramPacket from timeoutToDatagramPacket. <br>
	 * Does NOT remove the timeout or packet from any of the timeout data structures. <br>
	 * and returns it.
	 * @return timed out packet
	 */
	public DatagramPacket getTimedOutPacket() {
		if (hasTimedOutPacket()) {
			Long timeout = timeoutList.peek();
			DatagramPacket dp = timeoutToDatagramPacket.get(timeout);
			return dp;
		}
		return null;
	}
	
	/**
	 * Call this in rtp multiplex thread <br>
	 * Removes the timeout from timeoutList, timeoutToDatagramPacket, and ackNumToTimeout
	 * @param ackNum
	 * @return
	 */
	public void removeTimeout(int ackNum) {
		if (ackNumToTimeout.contains(ackNum)) {
//			System.out.println("\nconnection.removeTimeout: ackNumToTimeout contains ack# " + ackNum + "? " + ackNumToTimeout.contains(ackNum));
			Long timeout = ackNumToTimeout.get(ackNum);
//			System.out.println("connection.removeTimeout: removing timeout with ackNum = " + ackNum);
//			System.out.println("connection.removeTimeout: contains timeout? " + timeoutList.contains(timeout) + " " + timeoutToDatagramPacket.contains(timeout) + " " + ackNumToTimeout.contains(timeout));
			
			// remove timeout from timeout list
			timeoutList.remove(timeout);
			// remove timeout-DP from timeoutToDatagramPacket
			timeoutToDatagramPacket.remove(timeout);
			// remove ACK-timeout from ackNumToTimeout
			ackNumToTimeout.remove(ackNum);
		}
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

    /**
     * called in MultiplexData.run
     * @param rtpReceivePacket The packet to check for valid ordering
     * @return whether the order is valid
     */
    public boolean isValidOrder(Packet rtpReceivePacket) {
        return (lastSeqReceived == -1 || rtpReceivePacket.getSequenceNumber() == lastSeqReceived);
    }

    /**
     * Updates the last sequence number
     * called in rtp.receive and MultiplexData.run
     * @param ackNo the ackNo of the most recent acked packet
     */
    public void updateOrdering(int ackNo) {
        lastSeqReceived = ackNo;
    }

    public int getLastSeqSent() {
        return lastSeqSent;
    }

    public void setLastSeqSent(int lastSeqSent) {
        this.lastSeqSent = lastSeqSent;
    }
}

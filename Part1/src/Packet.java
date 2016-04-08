import java.nio.ByteBuffer;

/**
 * Packet class represents all the data in an RTP packet.
 * Can convert packet into a byte array.
 * RTP packets are designed to be inside the payload of a UDP packet.
 * Payloads can be null.
 * @author andreahu
 *
 */
public class Packet {

	private static final int MAX_RTP_PACKET_SIZE = 1000; // bytes
	
	/*
	 * 7 fields x 4 bytes = 28 bytes in the RTP header
	 * --> 1000 bytes - 28 bytes = 972 max segment size
	 */
	
	private static final int MAX_SEGMENT_SIZE = 972; // bytes
	
	/*
	 * HEADER
	 * every entry is 32 bits or 4 bytes
	 */
	private boolean FIN = false; // 0
	private boolean ACK = false; // 1
	private boolean SYN = false; // 2
	private int sequenceNumber = 0; // 3
	private int ackNumber = 0; // 4
	private int remainingBufferSize = 0; // 5

	private int checksum = 0; //8

	//TODO: fix dependencies
	private int payloadSize = 0; // 6
	/*
	 * PAYLOAD
	 */
	private byte[] payload; // 7
	
	/**
	 * Creates a packet object.
	 * @param FIN
	 * @param ACK
	 * @param SYN
	 * @param sequenceNumber
	 * @param ackNumber
	 * @param payload
	 */
	public Packet(boolean FIN, boolean ACK, boolean SYN, int sequenceNumber, 
			int ackNumber, byte[] payload) {
		this.FIN = FIN;
		this.ACK = ACK;
		this.SYN = SYN;
		this.sequenceNumber = sequenceNumber;
		this.ackNumber = ackNumber;
		
		this.payload = payload;
		
		// calculated header fields
		this.payloadSize = getPayloadSize();
		// remainingBufferSize should be window_size - buffer.size() calculated via connection
	}
	
	/**
	 * Converts a packet into a byte array for sending via UDP.
	 * @return packet in byte form
	 */
	public byte[] packetize() {
		ByteBuffer b = ByteBuffer.allocate(MAX_RTP_PACKET_SIZE);
		
		// header
		b.putInt((this.FIN == false) ? 0 : 1); // 0
		b.putInt((this.ACK == false) ? 0 : 1); // 1
		b.putInt((this.SYN == false) ? 0 : 1); // 2
		b.putInt(this.sequenceNumber); // 3
		b.putInt(this.ackNumber); // 4
		b.putInt(this.remainingBufferSize); // 5
		b.putInt(this.payloadSize); // 6
		
		// payload
		if (this.payload != null) {
			b.put(this.payload); // 7
		}
		
		return b.array();
	}
	
	/*
	 * Setter Methods
	 */
	public void setFIN(boolean value) {
		this.FIN = value;
	}
	
	public void setACK(boolean value) {
		this.ACK = value;
	}
	
	public void setSYN(boolean value) {
		this.SYN = value;
	}
	
	public void setSequenceNumber(int value) {
		this.sequenceNumber = value;
	}
	
	public void setAckNumber(int value) {
		this.ackNumber = value;
	}
	
	public void setRemainingBufferSize(int value) {
		this.remainingBufferSize = value;
	}
	
	public void setPayload(byte[] value) {
		this.payload = value;
	}
	
	/*
	 * Getter Methods
	 */
	
	public boolean getFIN() {
		return this.FIN;
	}
	
	public boolean getACK() {
		return this.ACK;
	}
	
	public boolean getSYN() {
		return this.SYN;
	}
	
	public int getSequenceNumber() {
		return this.sequenceNumber;
	}
	
	public int getAckNumber() {
		return this.ackNumber;
	}
	
	/**
	 * Recalculates the remaining buffer size from the payload
	 * @return remaining bytes in buffer
	 */
	public int getRemainingBufferSize() {
		return this.remainingBufferSize;
	}
	
	/**
	 * Recalculates the payload size from the payload
	 * @return bytes in the payload
	 */
	public int getPayloadSize() {
		if (this.payload != null) {
			this.payloadSize = this.payload.length;
			return this.payloadSize;
		}
		return 0; // if there is no rtp payload
	}
	
	public static int getMaxSegmentSize() {
		return MAX_SEGMENT_SIZE;
	}
	
	/**
	 * 
	 * @return payload byte array or null
	 */
	public byte[] getPayload() {
		return this.payload;
	}
}

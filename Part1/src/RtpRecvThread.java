import java.net.DatagramSocket;
import java.net.DatagramPacket;


public class RtpRecvThread extends Thread {
	private static int PACKET_SIZE = 2048;
	private DatagramSocket socket;
	
	public RtpRecvThread (DatagramSocket socket) {
		this.socket = socket;
	}
	
	public void run() {
		DatagramPacket p = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
		try {
			socket.receive(p);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args) {
		try {
			(new RtpRecvThread(new DatagramSocket())).start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}

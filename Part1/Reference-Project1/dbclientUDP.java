import java.net.*;
import java.io.*;

public class dbclientUDP {
	private static final int TIMEOUT = 2000; // Resent timeout (milliseconds)
	private static final int MAXTRIES = 3; // Maximum retransmissions
	
	public static void main(String[] args) throws IOException {
		if ((args.length < 3)) {
			throw new IllegalArgumentException("Parameters: <Server>:<Port> <Query Key> <Query Attribute> ... <Query Attribute>");
		}

		String[] serverAndPort = separate(args[0]);
		String serverNum = serverAndPort[0];
		int servPort = Integer.parseInt(serverAndPort[1]);
		
		InetAddress serverAddress = InetAddress.getByName(serverNum); // Server address
		// Convert the argument String to bytes using the default encoding
		
		StringBuilder attributeBuilder = new StringBuilder();
		for (int i = 2; i < args.length; i++) {
			attributeBuilder.append(args[i]);
			if (i != (args.length - 1)) {
				attributeBuilder.append(" "); // queries separated by spaces
			}
		}
		
		String attributeList = attributeBuilder.toString();		
		String query = new StringBuilder(args[1] + ":" + attributeList).toString();
		byte[] bytesToSend = query.getBytes();	

		DatagramSocket socket = new DatagramSocket();
		
		socket.setSoTimeout(TIMEOUT); // Maximum receive blocking time (milliseconds)
		
		DatagramPacket sendPacket = new DatagramPacket(bytesToSend, bytesToSend.length, serverAddress, servPort);
		DatagramPacket receivePacket = new DatagramPacket(new byte[500], 500);
		
		int tries = 0; // Packets may be lost, so we have to keep trying
		boolean receivedResponse = false;
		do {
			socket.send(sendPacket); // Send the echo string
			try {
				socket.receive(receivePacket); // Attempt echo reply reception
				
				if (!receivePacket.getAddress().equals(serverAddress)) { // Check source
					 throw new IOException("Received packet from an unknown source");
				}
				
				receivedResponse = true;
			} catch (InterruptedIOException e) { // We did not get anything
				tries += 1;
				System.out.println("The server has not answered in the last two seconds.");
				System.out.println("retrying...");
			}
		} while ((!receivedResponse) && (tries < MAXTRIES));
		
		if (receivedResponse) {
			System.out.println("Received: " + new String(receivePacket.getData()));
		} else {
			System.out.println("No response -- giving up.");
		}
		
		socket.close();
	}
	
	/**
	 * Separates the server number and the port number from the format
	 * server_number:port_number
	 * @param input
	 * @return a string array with the server # at index 0, and the port # at index 1
	 */
	private static String[] separate(String input) {
		StringBuilder serverBuilder = new StringBuilder();
		StringBuilder portBuilder = new StringBuilder();
		String[] result = new String[2];
		
		int indexOfColon = input.indexOf(":");
		
		for (int i = 0; i < indexOfColon; i++) {
			serverBuilder.append(input.charAt(i));
		}
		
		for (int i = indexOfColon + 1; i < input.length(); i++) {
			portBuilder.append(input.charAt(i));
		}
		
		String server = serverBuilder.toString();
		String port = portBuilder.toString();
		
		result[0] = server;
		result[1] = port;
		
		return result;
	}
}
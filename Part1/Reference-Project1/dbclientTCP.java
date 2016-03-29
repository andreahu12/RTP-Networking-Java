import java.net.*;
import java.nio.ByteBuffer;
import java.io.*;
import java.lang.StringBuilder;

public class dbclientTCP {
	public static void main(String[] args) throws IOException {
		if ((args.length < 3)) {
			throw new IllegalArgumentException("Parameters: <Server>:<Port> <Query Key> <Query Attribute> ... <Query Attribute>");
		}
		
		String[] serverAndPort = separate(args[0]);
		String server = serverAndPort[0];
		int servPort = Integer.parseInt(serverAndPort[1]);
		
		// Convert input String to bytes using the default character encoding
		StringBuilder attributeBuilder = new StringBuilder();
		for (int i = 2; i < args.length; i++) {
			attributeBuilder.append(args[i]);
			if (i != (args.length - 1)) {
				attributeBuilder.append(" "); // queries separated by spaces
			}
		}
		
		String attributeList = attributeBuilder.toString();		
		String query = new StringBuilder(args[1] + ":" + attributeList + "*").toString();
		byte[] byteBuffer = query.getBytes();		
		
		// Create socket that is connected to server on specified port
		Socket socket = new Socket(server, servPort);
		
		InputStream in = socket.getInputStream();
		OutputStream out = socket.getOutputStream();
		
//		out.write(byteBuffer.length);
		out.write(byteBuffer); // Send the encoded string to the server
		
		// get query results
		boolean receivedMessage = false;
		int totalBytesRecvd = 0;
		int bytesRcvd;
		while (!receivedMessage) {
			byteBuffer = new byte[500];
			if ((bytesRcvd = in.read(byteBuffer, totalBytesRecvd, byteBuffer.length - totalBytesRecvd)) == -1) {
				throw new SocketException("Connection closed prematurely");
			}
			totalBytesRecvd += bytesRcvd;
			receivedMessage = true;
		}
		
		System.out.println("From Server: " + new String(byteBuffer));
		
		socket.close(); // Close the socket and its streams
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
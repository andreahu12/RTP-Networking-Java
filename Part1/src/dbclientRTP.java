import java.net.*;
import java.nio.ByteBuffer;
import java.io.*;
import java.lang.StringBuilder;
import java.nio.charset.Charset;

/**
 * An example of how to use RTP as a client to connect to a server
 */
public class dbclientRTP {

    /**
     * Thread 1: socket(), start thread 2, connect(), send()
     * Thread 2: Receive()
     * @param args
     * @throws IOException
     */
	public static void main(String[] args) throws IOException {
		if ((args.length < 3)) {
			throw new IllegalArgumentException("Parameters: <Server>:<Port> <Query Key> " +
                    "<Query Attribute> ... <Query Attribute>");
		}
		// get args from command line
		String[] serverAndPort = separate(args[0]);
		String server = serverAndPort[0];
		int servPort = Integer.parseInt(serverAndPort[1]);

		// Gets list of desired columns and puts them in a string separated by spaces with the key in front with a ':'
		StringBuilder attributeBuilder = new StringBuilder();
		for (int i = 2; i < args.length; i++) {
			attributeBuilder.append(args[i]);
			if (i != (args.length - 1)) {
				attributeBuilder.append(" "); // queries separated by spaces
			}
		}
		String attributeList = attributeBuilder.toString();		
		String query = new StringBuilder(args[1] + ":" + attributeList + "*").toString();

        // Convert input String to bytes using the default character encoding
		byte[] byteBuffer = query.getBytes(Charset.forName("UTF-8"));
		
		// Create socket that is connected to server on specified port
		// TODO: connect
		InetAddress serverIP = InetAddress.getByName(server);
		int windowSizeInBytes = 1;
		
		Connection c = null;
		try {
			c = rtp.connect(serverIP, servPort, windowSizeInBytes);
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		
		try {			
			// TODO: send byte buffer
			rtp.send(byteBuffer, c);
			
			// get query results
			boolean receivedMessage = false;
			int totalBytesRecvd = 0;
			int bytesRcvd;
			while (!receivedMessage) {
				byteBuffer = new byte[500];
	//			if ((bytesRcvd = in.read(byteBuffer, totalBytesRecvd, byteBuffer.length - totalBytesRecvd)) == -1) {
	//				throw new SocketException("Connection closed prematurely");
	//			}
				
				if ((bytesRcvd = rtp.receive(byteBuffer, 500, c)) > 0) {
					totalBytesRecvd += bytesRcvd;
					receivedMessage = true;
				}
			}
			
			System.out.println("From Server: " + new String(byteBuffer));
			
			// TODO: close the connection
			rtp.close(c, SocketToCloseIs.ClientSocket); // Close the socket and its streams
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/*
	 * STRING PARSING HELPER METHODS
	 */
	
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

    /**
     * The thread for receiving data. starts when accept accept starts
     * Do not implement until we have one working first
     */
    private class ReceiveThread extends Thread{
        /**
         * Constructor if we need it
         */
        ReceiveThread(){
        }

        /**
         * called by start()
         */
        @Override
        public void run(){

        }
    }
}
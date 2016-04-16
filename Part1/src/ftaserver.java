


public class ftaserver {

	public static void main(String[] args) throws Exception {
		// input: $ java ftaserver P W
		if (args.length != 2) {
			throw new Exception("java ftaserver <Server UDP Port Number> <Max Window Size In Bytes>");
		}
		
		int serverPort = Integer.valueOf(args[0]);
		int maxWindowSizeInBytes = Integer.valueOf(args[1]);
		
		rtp.listen(serverPort);
		
		// TODO: start new receive thread
		
		
		/**
         * Listens for incoming connections by checking the SYN buffer for a package
         * Creates a new thread for each connection to send packages to said connection
         */
        while(true) {
			Connection c = rtp.accept(1);
//            (new ConnectionThread(c)).start(); // TODO: make sure this works
			// ah: can't close the client socket from the server
	        //clntSock.close(); // Close the socket. We are done with this client!
		}
        
		// commands: get F, get-post F G, disconnect
		
	}
	
	/**
	 * Determines what kind of command the client sent based on the byte array received
	 * @param message
	 * @return
	 */
	private static CommandReceived getCommand(byte[] message) {
		String messageStr = String.valueOf(message);
		String[] args = messageStr.split(" ");
		
		String command = args[0]; // "get", "get-post", or "disconnect"
		if (command.toLowerCase().equals("get")) {
			return CommandReceived.GET;
		} else if (command.toLowerCase().equals("get-post")) {
			return CommandReceived.GET_POST;
		} else if (command.toLowerCase().equals("disconnect")) {
			return CommandReceived.DISCONNECT;
		} else {
			System.out.println("ftaserver.getCommand: did not get a recognizable message-- " + messageStr);
			return null;
		}
	}
	
	/**
	 * Gets the name of the file to download from the server F
	 * @param message
	 * @return
	 */
	private static String getF(byte[] message) {
		String messageStr = String.valueOf(message);
		String[] args = messageStr.split(" ");
		
		if (args.length > 1) {
			return args[1];
		} else {
			System.out.println("ftaserver.getF: not enough arguments (got: " + args.length + ")");
			return null;
		}
	}
	
	/**
	 * Gets the name of the file to upload to the server G
	 * @param message
	 * @return
	 */
	private static String getG(byte[] message) {
		String messageStr = String.valueOf(message);
		String[] args = messageStr.split(" ");
		
		if (args.length == 3) {
			return args[2];
		} else {
			System.out.println("ftaserver.getG: wrong number of arguments (got " + args.length + ")");
			return null;
		}
	}
	
	
	/**
	 * Represents the 3 possible messages from the client
	 * @author andreahu
	 *
	 */
	private static enum CommandReceived {
		GET,
		GET_POST,
		DISCONNECT
	}
	
}

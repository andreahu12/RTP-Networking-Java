import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.Queue;




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
			Connection c = rtp.accept(maxWindowSizeInBytes);
			if (c == null) {
				return;
			}
            (new ConnectionThread(c)).start();
			// ah: can't close the client socket from the server
	        //clntSock.close(); // Close the socket. We are done with this client!
		}
        
		// commands: get F, get-post F G, disconnect
		
	}
	
    /**
     * The thread for any specific connection. starts when accept accepts a connection
     * Do not implement until we have single thread working first
     */
    private static class ConnectionThread extends Thread{
        Connection connection;
        /**
         * Constructor if we need it
         */
        ConnectionThread(Connection c) {
            connection = c;
        }

        /**
         * called by start()
         */
        @Override
        public void run() {

			System.out.println("ftaserver: Handling client at " + connection.getRemoteAddress() +
                    " on Port " + connection.getRemotePort());

			int totalBytesInMessage = ByteBuffer.wrap(rtp.receive(4, connection)).getInt();
			int bytesReceived = 0;
			
			Queue<Byte> commandList = new LinkedList<Byte>();
			
//			System.out.println("ftaserver: totalBytesInMessage = " + totalBytesInMessage);
			
			while (bytesReceived < totalBytesInMessage) {
				byte[] recv = rtp.receive(500, connection);
				for (byte b : recv) {
					commandList.add(b);
				}
				bytesReceived = bytesReceived + recv.length;
//				System.out.println("ftaserver: bytesReceived = " + bytesReceived);
			}
			
			byte[] commandBytes = new byte[commandList.size()];
			for (int i = 0; i < commandBytes.length; i++) {
				commandBytes[i] = commandList.poll();
			}
			
//			System.out.print("ftaserver: receiving...");
//			for (byte b : commandBytes) {
//				System.out.print(b+", ");
//			}
//			System.out.println();
			
//			System.out.println("ftaserver received: " + new String(commandBytes));
			
			CommandReceived command = getCommand(commandBytes);
			
			if ((command == CommandReceived.GET) || (command == CommandReceived.GET_POST)) {
				try {
				// send file F
				String f = getF(commandBytes);
				
				// get the bytes of file F
				Path toF = Paths.get(f);
				byte[] fInBytes = Files.readAllBytes(toF);
				
				// append the size in front
				int numBytesInF = fInBytes.length;
				ByteBuffer fBuffer = ByteBuffer.allocate(4 + numBytesInF);
				fBuffer.putInt(numBytesInF);
				fBuffer.put(fInBytes);
				
				// send it
				rtp.send(fBuffer.array(), connection);
				System.out.println("ftaserver: sent file F");
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} 
			
			if (command == CommandReceived.GET_POST) {
				// receive file G
				
				int numBytesInMessage = ByteBuffer.wrap(rtp.receive(4, connection)).getInt();
				int numBytesReceived = 0;
				
				Queue<Byte> gList = new LinkedList<Byte>();
				
				while (numBytesReceived < numBytesInMessage) {
					byte[] recv = rtp.receive(500, connection);
					for (byte b : recv) {
						gList.add(b);
					}
					numBytesReceived = numBytesReceived + recv.length;
//					System.out.println("ftaserver: numBytesReceived = " + numBytesReceived);
				}
				
				// turn it into a byte array
				byte[] gBytes = new byte[gList.size()];
				for (int i = 0; i < gBytes.length; i++) {
					gBytes[i] = gList.poll();
				}
				
//				System.out.println("ftaserver: received G in bytes");
				
				// save file G
				String g = getG(commandBytes);
				
				// safe f in the local directory
				try {
					saveFile("post_G.jpg", gBytes);
				} catch (IOException e) {
					e.printStackTrace();
				}
				System.out.println("ftaserver: saved file G");
				
			}
        }
    }
    
	/**
	 * Saves a file in the local directory
	 * @param filename
	 * @param data
	 * @throws IOException
	 */
	private static void saveFile(String filename, byte[] data) throws IOException {
		// safe file in the local directory
		FileOutputStream fos = new FileOutputStream(filename);
		fos.write(data);
		fos.close();
	}
	
	
	/**
	 * Determines what kind of command the client sent based on the byte array received
	 * @param message
	 * @return
	 */
	private static CommandReceived getCommand(byte[] message) {
		String messageStr = new String(message);
		String[] args = messageStr.split(" ");
		
		String command = args[0]; // "get", "get-post", or "disconnect"
//		System.out.println("ftaserver.getCommand: " + command.toLowerCase());
		if (command.toLowerCase().equals("get")) {
			return CommandReceived.GET;
		} else if (command.toLowerCase().equals("get-post")) {
			return CommandReceived.GET_POST;
		} else if (command.toLowerCase().equals("disconnect")) {
			return CommandReceived.DISCONNECT;
		} else {
			System.out.println("ftaserver.getCommand: did not get a recognizable message-- " + new String(messageStr));
			return null;
		}
	}
	
	/**
	 * Gets the name of the file to download from the server F
	 * @param message
	 * @return
	 */
	private static String getF(byte[] message) {
		String messageStr = new String(message);
		String[] args = messageStr.split(" ");
		
		if (args.length > 1) {
			return args[1];
		} else {
			System.out.println("ftaserver.getF: not enough arguments (got: " + args.length + ", " + messageStr + ")");
			return null;
		}
	}
	
	/**
	 * Gets the name of the file to upload to the server G
	 * @param message
	 * @return
	 */
	private static String getG(byte[] message) {
		String messageStr = new String(message);
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

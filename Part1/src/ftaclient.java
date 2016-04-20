import java.io.FileNotFoundException;
import java.io.FileOutputStream; 
import java.io.IOException;
import java.net.InetAddress; 
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;



public class ftaclient {

	public static void main(String[] args) {
		if (args.length != 2) {
			//throw new Exception("ftaclient args should be: <Server Host IP or Name>:<Server Port> <Receive Window In Bytes");
		}
		
		InetAddress serverIP = getServerAddress(args[0]);
		int serverPort = getServerPort(args[0]);
		int windowSizeInBytes = Integer.valueOf(args[1]);
		
		// java ftaclient H:P W
			// H: IP Address OR Host Name of ftaserver
			// P: UDP Port Number of ftaserver
			// W: Receive window size at ftaclient (in bytes)
		// get F
			// download file named "get_F" from server
		// get-post F G
			// download "get_F", upload "post_G"
		// disconnect: terminate gracefully
		
		Connection c = null;
		try {
			c = rtp.connect(serverIP, serverPort, windowSizeInBytes);
			
			if (c == null) {
				return;
			}
			
			boolean receivedDisconnect = false;
			
			while (!receivedDisconnect) {
				System.out.println("Enter a command (get F, get-post F G, or disconnect):");
				String input = System.console().readLine().toLowerCase();
				
				CommandFromCL command = parseCommand(input);
				
				if (command == CommandFromCL.DISCONNECT) {
					// terminate gracefully
					receivedDisconnect = true;
				} else if ((command == CommandFromCL.GET) || (command == CommandFromCL.GET_POST)) { 
					// this branch downloads F in get and get_post
					
					int numBytesInInput = input.getBytes().length;
					ByteBuffer inputBuffer = ByteBuffer.allocate(4 + numBytesInInput);
					inputBuffer.putInt(numBytesInInput);
					inputBuffer.put(input.getBytes());
					
					// send command to download a file named "get_F" from server
					System.out.print("ftaclient: sending...");
					for (byte b : input.getBytes()) {
						System.out.print(b+", ");
					}
					System.out.println();
					rtp.send(inputBuffer.array(), c);
					
					
					// receive file F
					Queue<Byte> resultList = new LinkedList<Byte>();
					
					int totalBytesInMessage = ByteBuffer.wrap(rtp.receive(4, c)).getInt();
					int bytesReceived = 0;
					
					while (bytesReceived < totalBytesInMessage) {
						byte[] recv = rtp.receive(500, c);
						for (byte b : recv) {
							resultList.add(b);
						}
						bytesReceived = bytesReceived + recv.length;
						System.out.println("ftaclient: bytesReceived = " + bytesReceived);
					}
					
					// turn it into a byte array
					byte[] fileF = new byte[resultList.size()];
					for (int i = 0; i < fileF.length; i++) {
						fileF[i] = resultList.poll();
					}
					
					System.out.println("ftaclient: got file F");
					
					// split the input into its components
					String f = getF(input.getBytes());
					
					// safe f in the local directory
					saveFile("output1.jpg", fileF);
				}
				
				if (command == CommandFromCL.GET_POST) {
					// download "get_F", upload "post_G"
					
					// split the input into its components
					String g = getG(input.getBytes());
					
					// get the bytes of file G
					Path toG = Paths.get(g);
					byte[] gInBytes = Files.readAllBytes(toG);
					
					// append the size of G to the front
					ByteBuffer gBuffer = ByteBuffer.allocate(4 + gInBytes.length);
					gBuffer.putInt(gInBytes.length);
					gBuffer.put(gInBytes);
					
					rtp.send(gBuffer.array(), c);
					System.out.println("ftaclient: sent file G to the server");
				}
			}
			rtp.close(c);
		} catch (Exception e1) {
			e1.printStackTrace();
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
	 * Takes input string and determines what command it is
	 * @param input
	 * @return
	 */
	private static CommandFromCL parseCommand(String input) {
		if (input.equals("disconnect")) {
			return CommandFromCL.DISCONNECT;
		} else if (input.contains("get-post")) {
			return CommandFromCL.GET_POST;
		} else if (input.contains("get")) {
			return CommandFromCL.GET;
		} else {
			System.out.println("ftaclient.parseCommand: received invalid command [" + input + "]");
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
			System.out.println("ftaclient.getF: not enough arguments (got: " + args.length + ")");
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
			System.out.println("ftaclient.getG: wrong number of arguments (got " + args.length + ")");
			return null;
		}
	}
	
	private static enum CommandFromCL {
		DISCONNECT,
		GET_POST,
		GET
	}
	
	/**
	 * Gets the server IP address from a string in the form of <br>
	 * <Server Host IP or Name>:<Server Port> <br>
	 * Which is from arg[0]
	 * @param s
	 * @return
	 */
	private static InetAddress getServerAddress(String s) {
		String[] result = s.split(":");
		InetAddress serverAddress = null;
		
		try {
			serverAddress = InetAddress.getByName(result[0]);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return serverAddress;
	}
	
	/**
	 * Gets the server port from a string in the form of <br>
	 * <Server Host IP or Name>:<Server Port> <br>
	 * Which is from arg[0]
	 * @param s
	 * @return
	 */
	private static int getServerPort(String s) {
		String[] result = s.split(":");
		return Integer.valueOf(result[1]);
	}

}

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Queue;


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
			
			boolean receivedDisconnect = false;
			
			while (!receivedDisconnect) {
				System.out.println("Enter a command (get F, get-post F G, or disconnect):");
				String input = System.console().readLine().toLowerCase();
				
				CommandFromCL command = parseCommand(input);
				
				if (command == CommandFromCL.DISCONNECT) {
					// move on to the close method call
					receivedDisconnect = true;
				} else {
					rtp.send(input.getBytes(), c);
					
					Queue<byte[]> resultList = (Queue<byte[]>) new ArrayList<byte[]>();
					
					byte[] size = rtp.receive(4, c);
					int numBytesInMessage = ByteBuffer.wrap(size).getInt();
					
					int bytesReceived = 0;
					
					while (bytesReceived < numBytesInMessage) {
						byte[] result = rtp.receive(500, c);
						resultList.add(result);
						bytesReceived = bytesReceived + result.length;
					}
					
					byte[] finalResult = new byte[numBytesInMessage];
					
					int i = 0;
					for (byte[] byteArray : resultList) {
						for (byte b : byteArray) {
							finalResult[i] = b;
							i++;
						}
					}
					
					System.out.println("ftaclient received: " + new String(finalResult));
				}

			}
			rtp.close(c);

//			else if (command == CommandFromCL.GET_POST) {
//				String[] sections = input.split(" ");
//				String f = sections[1];
//				String g = sections[2];
//			} else if (command == CommandFromCL.GET) {
//				String[] sections = input.split(" ");
//				String f = sections[1];
//				
//			}
			
//            byte[] test = {1,2,3,4};
//            System.out.println("dbClient: Sending data: 1,2,3,4");
//            rtp.send(test,c);
//            System.out.println("dbClient: Data sent");
//
//            System.out.println("dbClient: looking for 4 bytes of data");
//            Byte[] data = rtp.receive(4,c);
//
//            System.out.print("dbClient: read bytes: ");
//            for (Byte b:data) {
//                System.out.print(b.toString());
//            }
//            System.out.println();

		} catch (Exception e1) {
			e1.printStackTrace();
		}	
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

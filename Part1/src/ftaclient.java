
public class ftaclient {

	public static void main(String[] args) {
		if (args.length != 2) {
			//throw new Exception("ftaclient args should be: <Server Host IP or Name>:<Server Port> <Receive Window In Bytes");
		}
		
		String serverIPorName = args[0];
		int serverPort = Integer.valueOf(args[1]);
		int receiveWindowSize = Integer.valueOf(args[2]);
		
		
		
		// java ftaclient H:P W
			// H: IP Address OR Host Name of ftaserver
			// P: UDP Port Number of ftaserver
			// W: Receive window size at ftaclient (in bytes)
		// get F
			// download file named "get_F" from server
		// get-post F G
			// download "get_F", upload "post_G"
		// disconnect: terminate gracefully
		

	}

}

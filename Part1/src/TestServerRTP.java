import java.util.HashMap;

/**
 * An example of how to use RTP as a server to connect to multiple clients
 * @author jeffersonwang, andreahu
 */
public class TestServerRTP {
    private static final int BUFSIZE = 2048;//32; // SIZE OF THE RECEIVE BUFFER
    private static HashMap<String, String[]> db = new HashMap<String, String[]>();

    /**
     * Thread 1: socket(), bind(), listen(), start thread 2, accept()
     * Thread 2: receive
     * Thread 3-n: connection thread that does everything else
     * @param args port of desired listening socket
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Parameter(s): <Port>");
        }

        int servPort = Integer.parseInt(args[0]);
        // Create a server socket to accept client connection requests, socket bind and listen
        rtp.listen(servPort);

        // Start looking for packets in the receive buffers?
        (new ReceiveThread()).start();
        /**
         * Listens for incoming connections by checking the SYN buffer for a package
         * Creates a new thread for each connection to send packages to said connection
         */
        while(true) {
            Connection c = rtp.accept(1);
            (new ConnectionThread(c)).start();
            // ah: can't close the client socket from the server
            //clntSock.close(); // Close the socket. We are done with this client!
        }
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
        ConnectionThread(Connection c){
            connection = c;
        }

        /**
         * called by start()
         */
        @Override
        public void run(){
            System.out.println("testServerRTP: testing receive when requesting more data than a message in a single packet");
            byte[] data = rtp.receive(40,connection);
            System.out.print("testServerRTP: expected: 1,2,3,4 actual: " + bytesToString(data));
            System.out.println();
            System.out.println();

            System.out.println("testServerRTP: testing receive when cutting packet");
            data = rtp.receive(3,connection);
            System.out.print("testServerRTP: expected: 1,2,3 actual: " + bytesToString(data));
            System.out.println();
            System.out.println();

            System.out.println("testServerRTP: testing receive when emptying remainder");
            data = rtp.receive(3,connection);
            System.out.print("testServerRTP: expected: 4 actual: " + bytesToString(data));
            System.out.println();
            System.out.println();

            System.out.println("testServerRTP: testing 10000 byte data");
            data = rtp.receive(10000,connection);
            System.out.print("testServerRTP: expected: 10000 actual: " + (data.length));
            //System.out.print("testServerRTP: expected: 0,1,2,3,4 ....: " + bytesToString(data));
            System.out.println();
            System.out.println();


            System.out.println("testServerRTP: Sending data: 5,6,7,8");
            byte[] test = {5,6,7,8};
            rtp.send(test,connection);
            System.out.println("testServerRTP: data sent");
        }
    }

    private static String bytesToString(byte[] data){
        StringBuilder out = new StringBuilder();
        for (Byte b:data) {
            out.append(b.toString() + ",");
        }
        return out.toString();
    }

    private static byte[] createLargeMessage(){
        byte[] large = new byte[10000];
        for(int i = 0; i<10000; i++){
            large[i] = (byte)(i%256);
        }
        return large;
    }

    /**
     * The thread for receiving data. starts when accept accept starts
     * Do not implement until we have one working first
     */
    private static class ReceiveThread extends Thread{
        /**
         * Constructor if we need it
         */
//        ReceiveThread(){
//        }

        /**
         * called by start()
         */
        @Override
        public void run(){

        }
    }
}
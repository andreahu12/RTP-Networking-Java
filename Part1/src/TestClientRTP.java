import java.net.*;
import java.io.*;
import java.lang.StringBuilder;
import java.nio.charset.Charset;

/**
 * An example of how to use RTP as a client to connect to a server
 * @author andreahu, jeffersonwang
 */
public class TestClientRTP {

    /**
     * Thread 1: socket(), start thread 2, connect(), send()
     * Thread 2: Receive()
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        if ((args.length < 1)) {
            throw new IllegalArgumentException("Parameters: <Server>:<Port>");
        }
        // get args from command line
        String[] serverAndPort = separate(args[0]);
        String server = serverAndPort[0];
        int servPort = Integer.parseInt(serverAndPort[1]);

        // Create socket that is connected to server on specified port
        InetAddress serverIP = InetAddress.getByName(server);
        System.out.println("TestClientRTP: parsed server ip: "+serverIP);
        int windowSize = 1;

        Connection c = null;
        try {
            c = rtp.connect(serverIP, servPort, windowSize);

            byte[] test = {1,2,3,4};
            System.out.println("TestClientRTP: Sending data: 1,2,3,4");
            rtp.send(test,c);
            System.out.println("TestClientRTP: Data sent");

            System.out.println("TestClientRTP: Sending data: 1,2,3,4");
            rtp.send(test,c);
            System.out.println("TestClientRTP: Data sent");

            System.out.println("TestClientRTP: Sending large 10000 byte data");
            test = createLargeMessage();
            rtp.send(test,c);
            System.out.println("TestClientRTP: Data sent");

            byte[] test2 = {0,1,2,3,4,5};
            System.out.println("TestClientRTP: Sending data: 0,1,2,3,4,5");
            rtp.send(test2,c);
            System.out.println("TestClientRTP: Data sent");

//            System.out.println("TestClientRTP: looking for 4 bytes of data");
//            byte[] data = rtp.receive(4,c);
//
//            System.out.print("TestClientRTP: read bytes: ");
//            for (Byte b:data) {
//                System.out.print(b.toString());
//            }
//            System.out.println();

        } catch (Exception e1) {
            e1.printStackTrace();
        }
    }

    private static byte[] createLargeMessage(){
        byte[] large = new byte[10000];
        for(int i = 0; i<10000; i++){
            large[i] = (byte)(i%256);
        }
        return large;
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
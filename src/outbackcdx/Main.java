package outbackcdx;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

public class Main {
    public static void usage() {
        System.err.println("Usage: java " + Main.class.getName() + " [options...]");
        System.err.println("");
        System.err.println("  -b bindaddr   Bind to a particular IP address");
        System.err.println("  -d datadir    Directory to store index data under");
        System.err.println("  -i            Inherit the server socket via STDIN (for use with systemd, inetd etc)");
        System.err.println("  -p port       Local port to listen on");
        System.err.println("  -t count      Number of web server threads");
        System.err.println("  -v            Verbose logging");
        System.exit(1);
    }

    public static void main(String args[]) {
        String host = null;
        int port = 8080;
        int webThreads = Runtime.getRuntime().availableProcessors();
        boolean inheritSocket = false;
        File dataPath = new File("data");
        boolean verbose = false;
        Predicate<Capture> filter = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "-b":
                    host = args[++i];
                    break;
                case "-i":
                    inheritSocket = true;
                    break;
                case "-d":
                    dataPath = new File(args[++i]);
                    break;
                case "-v":
                    verbose = true;
                    break;
                case "-t":
                    webThreads = Integer.parseInt(args[++i]);
                    break;
                default:
                    usage();
                    break;
            }
        }

        try (DataStore dataStore = new DataStore(dataPath)) {
            Webapp controller = new Webapp(dataStore, verbose);
            ServerSocket socket = openSocket(host, port, inheritSocket);
            Web.Server server = new Web.Server(socket, controller);
            ExecutorService threadPool = Executors.newFixedThreadPool(webThreads);
            try {
                server.setAsyncRunner(threadPool::execute);
                server.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    server.stop();
                    dataStore.close();
                }));
                System.out.println("OutbackCDX http://" + (host == null ? "localhost" : host) + ":" + port);
                Thread.sleep(Long.MAX_VALUE);
            } finally {
                threadPool.shutdown();
            }
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

    private static ServerSocket openSocket(String host, int port, boolean inheritSocket) throws IOException {
        ServerSocket socket;Channel channel = System.inheritedChannel();
        if (inheritSocket && channel != null && channel instanceof ServerSocketChannel) {
            socket = ((ServerSocketChannel) channel).socket();
        } else {
            socket = new ServerSocket(port, -1, InetAddress.getByName(host));
        }
        return socket;
    }
}

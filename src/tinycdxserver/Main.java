package tinycdxserver;

import org.archive.accesscontrol.AccessControlClient;
import org.archive.accesscontrol.RobotsUnavailableException;
import org.archive.accesscontrol.RuleOracleUnavailableException;

import java.io.File;
import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.util.Date;
import java.util.function.Predicate;

public class Main {
    public static void usage() {
        System.err.println("Usage: java " + Server.class.getName() + " [options...]");
        System.err.println("");
        System.err.println("  -a url        Use a wayback access control oracle");
        System.err.println("  -b bindaddr   Bind to a particular IP address");
        System.err.println("  -d datadir    Directory to store index data under");
        System.err.println("  -i            Inherit the server socket via STDIN (for use with systemd, inetd etc)");
        System.err.println("  -p port       Local port to listen on");
        System.err.println("  -v            Verbose logging");
        System.exit(1);
    }

    public static void main(String args[]) {
        String host = null;
        int port = 8080;
        boolean inheritSocket = false;
        File dataPath = new File("data");
        boolean verbose = false;
        Predicate<Capture> filter = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-a":
                    filter = accessControlFilter(args[++i]);
                    break;
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
                default:
                    usage();
                    break;
            }
        }

        try (DataStore dataStore = new DataStore(dataPath, filter)) {
            final Server server;
            Channel channel = System.inheritedChannel();
            if (inheritSocket && channel != null && channel instanceof ServerSocketChannel) {
                server = new Server(dataStore, ((ServerSocketChannel) channel).socket());
            } else {
                server = new Server(dataStore, host, port);
            }
            server.verbose = verbose;
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                dataStore.close();
            }));
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

    private static Predicate<Capture> accessControlFilter(String oracleUrl) {
        AccessControlClient client = new AccessControlClient(oracleUrl);
        return capture -> {
            try {
                return "allow".equals(client.getPolicy(capture.original, new Date(capture.timestamp), new Date(), "public"));
            } catch (RobotsUnavailableException | RuleOracleUnavailableException e) {
                throw new RuntimeException(e);
            }
        };
    }
}

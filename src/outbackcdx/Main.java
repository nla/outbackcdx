package outbackcdx;

import outbackcdx.auth.Authorizer;
import outbackcdx.auth.JwtAuthorizer;
import outbackcdx.auth.KeycloakConfig;
import outbackcdx.auth.NullAuthorizer;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    public static void usage() {
        System.err.println("Usage: java " + Main.class.getName() + " [options...]");
        System.err.println("");
        System.err.println("  -b bindaddr           Bind to a particular IP address");
        System.err.println("  -d datadir            Directory to store index data under");
        System.err.println("  -i                    Inherit the server socket via STDIN (for use with systemd, inetd etc)");
        System.err.println("  -j jwks-url perm-path Use JSON Web Tokens for authorization");
        System.err.println("  -k url realm clientid Use a Keycloak server for authorization");
        System.err.println("  -p port               Local port to listen on");
        System.err.println("  -t count              Number of web server threads");
        System.err.println("  -r count              Cap on number of rocksdb records to scan to serve a single request");
        System.err.println("  -v                    Verbose logging");
        System.err.println();
        System.err.println("Primary mode (runs as a replication target for downstream Secondaries)");
        System.err.println("  --replication-window interval      interval, in seconds, to delete replication history from disk.");
        System.err.println("                                     0 disables automatic deletion. History files can be deleted manually by");
        System.err.println("                                     POSTing a replication sequenceNumber to /<collection>/truncate_replication");
        System.err.println();
        System.err.println("Secondary mode (runs read-only; polls upstream server on 'collection-url' for changes)");
        System.err.println("  --primary collection-url           URL of collection on upstream primary to poll for changes");
        System.err.println("  --update-interval poll-interval    Polling frequency for upstream changes, in seconds. Default: 10");
        System.err.println("  --accept-writes                    Allow writes to this node, even though running as a secondary");
        System.err.println("  -x                    Output CDX14 by default (instead of CDX11)");
        System.exit(1);
    }

    public static void main(String args[]) {
        boolean undertow = false;
        String host = null;
        int port = 8080;
        int webThreads = Runtime.getRuntime().availableProcessors();
        boolean inheritSocket = false;
        File dataPath = new File("data");
        boolean verbose = false;
        Authorizer authorizer = new NullAuthorizer();
        int pollingInterval = 10;
        List<String> collectionUrls = new ArrayList<>();
        Long replicationWindow = null;
        long scanCap = Long.MAX_VALUE;

        Map<String,Object> dashboardConfig = new HashMap<>();
        dashboardConfig.put("featureFlags", FeatureFlags.asMap());

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-u":
                    undertow = true;
                    break;
                case "-p":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "-b":
                    host = args[++i];
                    break;
                case "-d":
                    dataPath = new File(args[++i]);
                    break;
                case "-i":
                    inheritSocket = true;
                    break;
                case "-j":
                    try {
                        authorizer = new JwtAuthorizer(new URL(args[++i]), args[++i]);
                    } catch (MalformedURLException e) {
                        System.err.println("Malformed JWKS URL in -j option");
                        System.exit(1);
                    }
                    break;
                case "-k":
                    KeycloakConfig keycloakConfig = new KeycloakConfig(args[++i], args[++i], args[++i]);
                    authorizer = keycloakConfig.toAuthorizer();
                    dashboardConfig.put("keycloak", keycloakConfig);
                    break;
                case "-v":
                    verbose = true;
                    break;
                case "-t":
                    webThreads = Integer.parseInt(args[++i]);
                    break;
                case "-r":
                    scanCap = Long.parseLong(args[++i]);
                    break;
                case "--primary":
                    collectionUrls.add(args[++i]);
                    FeatureFlags.setSecondaryMode(true);
                    break;
                case "--accept-writes":
                    FeatureFlags.setAcceptWrites(true);
                    break;
                case "--update-interval":
                    pollingInterval = Integer.parseInt(args[++i]);
                    break;
                case "--replication-window":
                    replicationWindow = Long.parseLong(args[++i]);
                case "-x":
                    FeatureFlags.setCdx14(true);
                    break;
                default:
                    usage();
                    break;
            }
        }

        try (DataStore dataStore = new DataStore(dataPath, replicationWindow, scanCap)) {
            Webapp controller = new Webapp(dataStore, verbose, dashboardConfig);
            if (undertow) {
                UWeb.UServer server = new UWeb.UServer(host, port, controller, authorizer);
                server.start();
                System.out.println("OutbackCDX http://" + (host == null ? "localhost" : host) + ":" + port);
                Thread.sleep(Long.MAX_VALUE);
            } else {
                ServerSocket socket = openSocket(host, port, inheritSocket);
                Web.Server server = new Web.Server(socket, controller, authorizer);
                ExecutorService threadPool = Executors.newFixedThreadPool(webThreads);
                for (String collectionUrl: collectionUrls) {
                    ChangePollingThread cpt = new ChangePollingThread(collectionUrl, pollingInterval, dataStore);
                    cpt.setDaemon(true);
                    cpt.start();
                }
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
            }
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

    private static ServerSocket openSocket(String host, int port, boolean inheritSocket) throws IOException {
        ServerSocket socket;Channel channel = System.inheritedChannel();
        if (inheritSocket && channel instanceof ServerSocketChannel) {
            socket = ((ServerSocketChannel) channel).socket();
        } else {
            socket = new ServerSocket(port, -1, InetAddress.getByName(host));
        }
        return socket;
    }
}

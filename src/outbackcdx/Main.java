package outbackcdx;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.UnixOperatingSystemMXBean;

import outbackcdx.UrlCanonicalizer.ConfigurationException;
import outbackcdx.auth.Authorizer;
import outbackcdx.auth.JwtAuthorizer;
import outbackcdx.auth.KeycloakConfig;
import outbackcdx.auth.NullAuthorizer;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
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
        System.err.println("  -m max-open-files     Limit the number of open .sst files to control memory usage");
        System.err.println("                        (default " + maxOpenSstFilesHeuristic() + " based on system RAM and ulimit -n)");
        System.err.println("  -p port               Local port to listen on");
        System.err.println("  -t count              Number of web server threads");
        System.err.println("  -r count              Cap on number of rocksdb records to scan to serve a single request");
        System.err.println("  -x                    Output CDX14 by default (instead of CDX11)");
        System.err.println("  -v                    Verbose logging");
        System.err.println("  -y file               Custom fuzzy match canonicalization YAML configuration file");
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
        System.err.println("  --batch-size                       Approximate max size (in bytes) per replication batch");
        System.exit(1);
    }

    public static void main(String args[]) {
        boolean undertow = false;
        String host = null;
        int port = 8080;
        int webThreads = Runtime.getRuntime().availableProcessors();
        boolean inheritSocket = false;
        File dataPath = new File("data");
        int maxOpenSstFiles = maxOpenSstFilesHeuristic();
        boolean verbose = false;
        Authorizer authorizer = new NullAuthorizer();
        int pollingInterval = 10;
        List<String> collectionUrls = new ArrayList<>();
        Long replicationWindow = null;
        long scanCap = Long.MAX_VALUE;
        long batchSize = 10*1024*1024;
        String fuzzyYaml = null;

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
                case "-m":
                    maxOpenSstFiles = Integer.parseInt(args[++i]);
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
                    break;
                case "--batch-size":
                    batchSize = Long.parseLong(args[++i]);
                    break;
                case "-x":
                    FeatureFlags.setCdx14(true);
                    break;
                case "-y":
                    fuzzyYaml = args[++i];
                    break;
                default:
                    usage();
                    break;
            }
        }

        try {
            UrlCanonicalizer canonicalizer = new UrlCanonicalizer(fuzzyYaml);
            try (DataStore dataStore = new DataStore(dataPath, maxOpenSstFiles, replicationWindow, scanCap, canonicalizer)) {
                Webapp controller = new Webapp(dataStore, verbose, dashboardConfig, canonicalizer);
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
                        ChangePollingThread cpt = new ChangePollingThread(collectionUrl, pollingInterval, batchSize, dataStore);
                        cpt.setDaemon(true);
                        cpt.start();
                    }
                    try {
                        server.setAsyncRunner(threadPool);
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
            }
        } catch (InterruptedException | IOException | ConfigurationException e) {
            e.printStackTrace();
        }
    }

    private static int maxOpenSstFilesHeuristic() {
        Object bean = ManagementFactory.getOperatingSystemMXBean();
        if (!(bean instanceof OperatingSystemMXBean)) {
            System.err.println("Warning: unable to get OS memory information");
            return -1;
        }

        // assume each open SST file takes 10MB of RAM
        // allow RocksDB half of whatever's left after the JVM takes its share
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) bean;
        long physicalMemory = osBean.getTotalPhysicalMemorySize();
        long jvmMaxHeap = Runtime.getRuntime().maxMemory();
        long memoryAvailableToRocksDB = physicalMemory / 2 - jvmMaxHeap;
        long maxOpenFiles = memoryAvailableToRocksDB / (10 * 1024 * 1024);

        // but if ulimit -n is lower use that instead so we don't hit IO errors
        if (bean instanceof UnixOperatingSystemMXBean) {
            UnixOperatingSystemMXBean unixBean = (UnixOperatingSystemMXBean) bean;
            long maxFileDescriptors = unixBean.getMaxFileDescriptorCount() - unixBean.getOpenFileDescriptorCount() - 20;
            if (maxOpenFiles > maxFileDescriptors) {
                maxOpenFiles = maxFileDescriptors;
            }
        }

        // if we've got 40 terabytes of RAM we can't actually apply a limit
        // and hey we probably don't need one anyway!
        if (maxOpenFiles > Integer.MAX_VALUE) {
            return -1;
        }

        // we need to be able to open at least a few files
        // if there's this little RAM we're in trouble anyway
        if (maxOpenFiles < 16) {
            return 16;
        }

        return (int)maxOpenFiles;
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

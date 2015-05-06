package tinycdxserver;

import org.rocksdb.*;

import java.io.*;
import java.net.ServerSocket;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.util.List;
import java.util.Map;

public class Server extends NanoHTTPD {
    private final DataStore manager;
    boolean verbose = false;

    public Server(DataStore manager, String hostname, int port) {
        super(hostname, port);
        this.manager = manager;
    }

    public Server(DataStore manager, ServerSocket socket) {
        super(socket);
        this.manager = manager;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (session.getUri().equals("/")) {
                return collectionList();
            } else if (session.getMethod().equals(Method.GET)) {
                return query(session);
            } else if (session.getMethod().equals(Method.POST)) {
                return post(session);
            }
            return new Response(Response.Status.NOT_FOUND, "text/plain", "Not found\n");
        } catch (Exception e) {
            e.printStackTrace();
            return new Response(Response.Status.INTERNAL_ERROR, "text/plain", e.toString() + "\n");
        }
    }

    Response post(IHTTPSession session) throws IOException {
        String collection = session.getUri().substring(1);
        final RocksDB index = manager.getIndex(collection, true);
        BufferedReader in = new BufferedReader(new InputStreamReader(session.getInputStream()));
        long added = 0;
        WriteBatch batch = new WriteBatch();
        try {
            for (; ; ) {
                String line = in.readLine();
                if (verbose) {
                    System.out.println(line);
                }
                if (line == null) break;
                if (line.startsWith(" CDX")) continue;
                try {
                    String[] fields = line.split(" ");
                    Record record = new Record();
                    record.timestamp = Long.parseLong(fields[1]);
                    record.original = fields[2];
                    record.urlkey = UrlCanonicalizer.surtCanonicalize(record.original);
                    record.mimetype = fields[3];
                    record.status = fields[4].equals("-") ? 0 : Integer.parseInt(fields[4]);
                    record.digest = fields[5];
                    record.redirecturl = fields[6];
                    // TODO robots = fields[7]
                    record.length = fields[8].equals("-") ? 0 : Long.parseLong(fields[8]);
                    record.compressedoffset = Long.parseLong(fields[9]);
                    record.file = fields[10];
                    batch.put(record.encodeKey(), record.encodeValue());
                    added++;
                } catch (Exception e) {
                    return new Response(Response.Status.BAD_REQUEST, "text/plain", e.toString() + "\nAt line: " + line);
                }
            }
            WriteOptions options = new WriteOptions();
            try {
                options.setSync(true);
                index.write(options, batch);
            } catch (RocksDBException e) {
                e.printStackTrace();
                return new Response(Response.Status.INTERNAL_ERROR, "text/plain", e.toString());
            } finally {
                options.dispose();
            }
        } finally {
            batch.dispose();
        }
        return new Response(Response.Status.OK, "text/plain", "Added " + added + " records\n");
    }

    Response query(IHTTPSession session) throws IOException {
        String collection = session.getUri().substring(1);
        final RocksDB index = manager.getIndex(collection);
        if (index == null) {
            return new Response(Response.Status.NOT_FOUND, "text/plain", "Collection does not exist\n");
        }

        Map<String,String> params = session.getParms();
        if (params.containsKey("q")) {
            return XmlQuery.query(session, index);
        } else if (params.containsKey("url")) {
            return textQuery(index, params.get("url"));
        } else {
            return collectionDetails(index);
        }

    }

    private String slurp(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        char buf[] = new char[8192];
        InputStreamReader reader = new InputStreamReader(stream);
        try {
            for (;;) {
                int n = reader.read(buf);
                if (n < 0) break;
                sb.append(buf, 0, n);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private Response collectionList() throws IOException {
        String page = "<!doctype html><h1>tinycdxserver</h1>";

        List<String> collections = manager.listCollections();

        if (collections.isEmpty()) {
            page += "No collections.";
        } else {
            page += "<ul>";
            for (String collection : manager.listCollections()) {
                page += "<li><a href=" + collection + ">" + collection + "</a>";
            }
            page += "</ul>";
        }
        page += slurp(Server.class.getClassLoader().getResourceAsStream("tinycdxserver/usage.html"));
        return new Response(Response.Status.OK, "text/html", page);
    }

    private Response collectionDetails(RocksDB index) {
        String page = "<form>URL: <input name=url type=url><button type=submit>Query</button></form>\n<pre>";
        try {
            page += index.getProperty("rocksdb.stats");
            page += "\nEstimated number of records: " + index.getLongProperty("rocksdb.estimate-num-keys");
        } catch (RocksDBException e) {
            page += e.toString();
            e.printStackTrace();
        }
        return new Response(Response.Status.OK, "text/html", page);
    }

    private Response textQuery(final RocksDB index, String url) {
        final String canonUrl = UrlCanonicalizer.surtCanonicalize(url);
        return new Response(Response.Status.OK, "text/plain", new IStreamer() {
            @Override
            public void stream(OutputStream outputStream) throws IOException {
                Writer out = new BufferedWriter(new OutputStreamWriter(outputStream));
                RocksIterator it = index.newIterator();
                try {
                    it.seek(Record.encodeKey(canonUrl, 0));
                    for (; it.isValid(); it.next()) {
                        Record record = new Record(it.key(), it.value());
                        if (!record.urlkey.equals(canonUrl)) break;
                        out.append(record.toString()).append('\n');
                    }
                    out.flush();
                } finally {
                    it.dispose();
                }
            }
        });
    }

    public static void usage() {
        System.err.println("Usage: java " + Server.class.getName() + " [options...]");
        System.err.println("");
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

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-p")) {
                port = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-b")) {
                host = args[++i];
            } else if (args[i].equals("-i")) {
                inheritSocket = true;
            } else if (args[i].equals("-d")) {
                dataPath = new File(args[++i]);
            } else if (args[i].equals("-v")) {
                verbose = true;
            } else {
                usage();
            }
        }

        final DataStore dataStore = new DataStore(dataPath);
        try {
            final Server server;
            Channel channel = System.inheritedChannel();
            if (inheritSocket && channel != null && channel instanceof ServerSocketChannel) {
                server = new Server(dataStore, ((ServerSocketChannel) channel).socket());
            } else {
                server = new Server(dataStore, host, port);
            }
            server.verbose = verbose;
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    server.stop();
                    dataStore.close();
                }
            }));
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            dataStore.close();
        }
    }
}

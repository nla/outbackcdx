package tinycdxserver;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;

import java.io.*;
import java.net.ServerSocket;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

public class Server extends NanoHTTPD {
    private final DataStore manager;

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
                return new Response("tinycdxserver running\n");
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
        final DB index = manager.getIndex(collection, true);
        BufferedReader in = new BufferedReader(new InputStreamReader(session.getInputStream()));
        long added = 0;
        for (;;) {
            String line = in.readLine();
            System.out.println(line);
            if (line == null) break;
            String[] fields = line.split(" ");
            Record record = new Record();
            record.timestamp = Long.parseLong(fields[1]);
            record.original = fields[2];
            record.urlkey = UrlCanonicalizer.surtCanonicalize(record.original);
            record.mimetype = fields[3];
            record.status = Integer.parseInt(fields[4]);
            record.digest = fields[5];
            record.compressedoffset = Long.parseLong(fields[9]);
            record.file = fields[10];
            record.redirecturl = "";
            index.put(record.encodeKey(), record.encodeValue());
            added++;
        }
        return new Response(Response.Status.OK, "text/plain", "Added " + added + " records\n");
    }

    Response query(IHTTPSession session) throws IOException {
        String collection = session.getUri().substring(1);
        final DB index = manager.getIndex(collection);
        if (index == null) {
            return new Response(Response.Status.NOT_FOUND, "text/plain", "Collection does not exist\n");
        }

        Map<String,String> params = session.getParms();
        if (params.containsKey("q")) {
            return XmlQuery.query(session, index);
        }
        final String url = UrlCanonicalizer.surtCanonicalize(params.get("url"));

        return new Response(Response.Status.OK, "text/plain", new IStreamer() {
            @Override
            public void stream(OutputStream outputStream) throws IOException {
                Writer out = new BufferedWriter(new OutputStreamWriter(outputStream));
                DBIterator it = index.iterator();
                try {
                    it.seek(Record.encodeKey(url, 0));
                    while (it.hasNext()) {
                        Record record = new Record(it.next());
                        if (!record.urlkey.equals(url)) break;
                        out.append(record.toString()).append('\n');
                    }
                    out.flush();
                } finally {
                    it.close();
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
        System.exit(1);
    }

    public static void main(String args[]) {
        String host = null;
        int port = 8080;
        boolean inheritSocket = false;
        File dataPath = new File("data");

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-p")) {
                port = Integer.parseInt(args[++i]);
            } else if (args[i].equals("-b")) {
                host = args[++i];
            } else if (args[i].equals("-i")) {
                inheritSocket = true;
            } else if (args[i].equals("-d")) {
                dataPath = new File(args[++i]);
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

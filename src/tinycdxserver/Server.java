package tinycdxserver;


import com.grack.nanojson.JsonWriter;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static tinycdxserver.NanoHTTPD.Method.GET;
import static tinycdxserver.NanoHTTPD.Method.POST;
import static tinycdxserver.NanoHTTPD.Response.Status.*;

public class Server extends NanoHTTPD {
    private final DataStore dataStore;
    boolean verbose = false;

    public Server(DataStore dataStore, String hostname, int port) {
        super(hostname, port);
        this.dataStore = dataStore;
    }

    public Server(DataStore dataStore, ServerSocket socket) {
        super(socket);
        this.dataStore = dataStore;
    }

    String extension(String filename) {
        int i = filename.lastIndexOf('.');
        return i != -1 ? filename.substring(i + 1) : null;
    }

    String guessContentType(String filename) {
        switch (extension(filename)) {
            case "js": return "application/javascript";
            case "css": return "text/css";
            case "html": return "text/html";
            case "tag": return "riot/tag";
            default: return "application/octet-stream";
        }
    }

    Response serveResource(String uri) throws IOException {
        if (uri.contains("..")) {
            return notFound();
        }

        URL resource = getClass().getResource(uri.substring(1));
        if (resource == null) {
            return notFound();
        }

        Response response = new Response(OK, guessContentType(uri), resource.openStream());
        //response.addHeader("Cache-Control", "max-age=31536000");
        return response;
    }

    Response notFound() {
        return new Response(NOT_FOUND, "text/plain", "Not found\n");
    }

    Response jsonResponse(Object data) {
        Response response =  new Response(OK, "application/json", JsonWriter.string(data));
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }


    private static final Pattern CAPTURES_ROUTE = Pattern.compile("/api/collections/(" + DataStore.COLLECTION_PATTERN + ")/captures");
    private static final Pattern ALIASES_ROUTE = Pattern.compile("/api/collections/(" + DataStore.COLLECTION_PATTERN + ")/aliases");
    private static final Pattern STATS_ROUTE = Pattern.compile("/(" + DataStore.COLLECTION_PATTERN + ")/stats");

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();
            if (uri.equals("/")) {
                return collectionList();
            }
            if (uri.startsWith("/admin")) {
                return serveResource("/static/main.html");
            }
            if (method == GET && uri.equals("/api/collections")) {
                return jsonResponse(dataStore.listCollections());
            }

            {
                Matcher m = CAPTURES_ROUTE.matcher(uri);
                if (method == GET && m.matches()) {
                    String collection = m.group(1);
                    String key = session.getParms().getOrDefault("key", "");
                    long limit = Long.parseLong(session.getParms().getOrDefault("limit", "1000"));
                    Index index = dataStore.getIndex(collection);
                    List<Capture> results = StreamSupport.stream(index.capturesAfter(key).spliterator(), false)
                            .limit(limit)
                            .collect(Collectors.<Capture>toList());
                    return jsonResponse(results);
                }
            }

            {
                Matcher m = ALIASES_ROUTE.matcher(uri);
                if (method == GET && m.matches()) {
                    String collection = m.group(1);
                    String key = session.getParms().getOrDefault("key", "");
                    long limit = Long.parseLong(session.getParms().getOrDefault("limit", "1000"));
                    Index index = dataStore.getIndex(collection);
                    List<Alias> results = StreamSupport.stream(index.listAliases(key).spliterator(), false)
                            .limit(limit)
                            .collect(Collectors.<Alias>toList());
                    return jsonResponse(results);
                }
            }

            {
                Matcher m = STATS_ROUTE.matcher(uri);
                if (method == GET && m.matches()) {
                    String collection = m.group(1);
                    Index index = dataStore.getIndex(collection);
                    Response response = new Response(Response.Status.OK, "application/json",
                            JsonWriter.string().object()
                            .value("estimatedRecordCount", index.estimatedRecordCount())
                            .end().done());
                    response.addHeader("Access-Control-Allow-Origin", "*");
                    return response;
                }
            }

            if (method == GET && uri.startsWith("/static/")) {
                return serveResource(uri);
            }
            if (method == GET) {
                return query(session);
            }
            if (method == POST) {
                return post(session);
            }
            return notFound();
        } catch (Exception e) {
            e.printStackTrace();
            return new Response(INTERNAL_ERROR, "text/plain", e.toString() + "\n");
        }
    }

    Response post(IHTTPSession session) throws IOException {
        String collection = session.getUri().substring(1);
        final Index index = dataStore.getIndex(collection, true);
        BufferedReader in = new BufferedReader(new InputStreamReader(session.getInputStream()));
        long added = 0;

        try (Index.Batch batch = index.beginUpdate()) {
            while (true) {
                String line = in.readLine();
                if (verbose) {
                    System.out.println(line);
                }
                if (line == null) break;
                if (line.startsWith(" CDX")) continue;

                try {
                    if (line.startsWith("@alias ")) {
                        String[] fields = line.split(" ");
                        String aliasSurt = UrlCanonicalizer.surtCanonicalize(fields[1]);
                        String targetSurt = UrlCanonicalizer.surtCanonicalize(fields[2]);
                        batch.putAlias(aliasSurt, targetSurt);
                    } else {
                        batch.putCapture(Capture.fromCdxLine(line));
                    }
                    added++;
                } catch (Exception e) {
                    return new Response(Response.Status.BAD_REQUEST, "text/plain", e.toString() + "\nAt line: " + line);
                }
            }

            batch.commit();
        }
        return new Response(OK, "text/plain", "Added " + added + " records\n");
    }

    Response query(IHTTPSession session) throws IOException {
        String collection = session.getUri().substring(1);
        final Index index = dataStore.getIndex(collection);
        if (index == null) {
            return new Response(NOT_FOUND, "text/plain", "Collection does not exist\n");
        }

        Map<String,String> params = session.getParms();
        if (params.containsKey("q")) {
            return XmlQuery.query(session, index);
        } else if (params.containsKey("url")) {
            return WbCdxApi.query(session, index);
        } else {
            return collectionDetails(index.db);
        }

    }

    private String slurp(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        char buf[] = new char[8192];
        try (InputStreamReader reader = new InputStreamReader(stream)) {
            for (; ; ) {
                int n = reader.read(buf);
                if (n < 0) break;
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private Response collectionList() throws IOException {
        String page = "<!doctype html><h1>tinycdxserver</h1>";

        List<String> collections = dataStore.listCollections();

        if (collections.isEmpty()) {
            page += "No collections.";
        } else {
            page += "<ul>";
            for (String collection : dataStore.listCollections()) {
                page += "<li><a href=" + collection + ">" + collection + "</a>";
            }
            page += "</ul>";
        }
        page += slurp(Server.class.getClassLoader().getResourceAsStream("tinycdxserver/usage.html"));
        return new Response(OK, "text/html", page);
    }

    private Response collectionDetails(RocksDB db) {
        String page = "<form>URL: <input name=url type=url><button type=submit>Query</button></form>\n<pre>";
        try {
            page += db.getProperty("rocksdb.stats");
            page += "\nEstimated number of records: " + db.getLongProperty("rocksdb.estimate-num-keys");
        } catch (RocksDBException e) {
            page += e.toString();
            e.printStackTrace();
        }
        return new Response(OK, "text/html", page);
    }

}

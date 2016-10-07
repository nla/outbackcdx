package tinycdxserver;


import com.grack.nanojson.JsonWriter;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import tinycdxserver.NanoHTTPD.IHTTPSession;
import tinycdxserver.NanoHTTPD.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static tinycdxserver.NanoHTTPD.Method.GET;
import static tinycdxserver.NanoHTTPD.Method.POST;
import static tinycdxserver.NanoHTTPD.Response.Status.NOT_FOUND;
import static tinycdxserver.NanoHTTPD.Response.Status.OK;
import static tinycdxserver.Web.jsonResponse;
import static tinycdxserver.Web.serve;

class Controller {
    private final boolean verbose;
    private final DataStore dataStore;
    final Web.Router router = new Web.Router()
            .on(GET, "/", serve("dashboard.html", "text/html"))
            .on(GET, "/tinycdx.js", serve("tinycdx.js", "application/javascript"))
            .on(GET, "/api/collections", this::listCollections)
            .on(GET, "/<collection>", this::query)
            .on(POST, "/<collection>", this::post)
            .on(GET, "/<collection>/stats", this::stats)
            .on(GET, "/<collection>/captures", this::captures)
            .on(GET, "/<collection>/aliases", this::aliases);

    Controller(DataStore dataStore, boolean verbose) {
        this.dataStore = dataStore;
        this.verbose = verbose;
    }

    Response listCollections(IHTTPSession request) {
        return jsonResponse(dataStore.listCollections());
    }

    Response stats(IHTTPSession req) throws IOException {
        Index index = dataStore.getIndex(req.getParms().get("collection"));
        Response response = new Response(Response.Status.OK, "application/json",
                JsonWriter.string().object()
                        .value("estimatedRecordCount", index.estimatedRecordCount())
                        .end().done());
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    Response captures(IHTTPSession session) throws IOException {
        String collection = session.getParms().get("collection");
        String key = session.getParms().getOrDefault("key", "");
        long limit = Long.parseLong(session.getParms().getOrDefault("limit", "1000"));
        Index index = dataStore.getIndex(collection);
        List<Capture> results = StreamSupport.stream(index.capturesAfter(key).spliterator(), false)
                .limit(limit)
                .collect(Collectors.<Capture>toList());
        return jsonResponse(results);
    }

    Response aliases(IHTTPSession session) throws IOException {
        String collection = session.getParms().get("collection");
        String key = session.getParms().getOrDefault("key", "");
        long limit = Long.parseLong(session.getParms().getOrDefault("limit", "1000"));
        Index index = dataStore.getIndex(collection);
        List<Alias> results = StreamSupport.stream(index.listAliases(key).spliterator(), false)
                .limit(limit)
                .collect(Collectors.<Alias>toList());
        return jsonResponse(results);
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

    private Response dashboard(IHTTPSession request) throws IOException {
        return new Response(OK, "text/html", getClass().getResourceAsStream("dashboard.html"));
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

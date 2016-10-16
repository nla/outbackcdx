package tinycdxserver;


import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import tinycdxserver.NanoHTTPD.IHTTPSession;
import tinycdxserver.NanoHTTPD.Response;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.lang.System.out;
import static tinycdxserver.NanoHTTPD.Method.GET;
import static tinycdxserver.NanoHTTPD.Method.POST;
import static tinycdxserver.NanoHTTPD.Response.Status.NOT_FOUND;
import static tinycdxserver.NanoHTTPD.Response.Status.OK;
import static tinycdxserver.Web.jsonResponse;
import static tinycdxserver.Web.serve;

class Controller implements Web.Handler {
    private final Gson gson = new Gson();
    private final boolean verbose;
    private final DataStore dataStore;
    final Web.Router router = new Web.Router()
            .on(GET, "/", serve("dashboard.html", "text/html"))
            .on(GET, "/api.js", serve("api.js", "application/javascript"))
            .on(GET, "/add.svg", serve("add.svg", "image/svg+xml"))
            .on(GET, "/database.svg", serve("database.svg", "image/svg+xml"))
            .on(GET, "/outback.svg",  serve("outback.svg", "image/svg+xml"))

            .on(GET, "/lib/vue-router/2.0.0/vue-router.js", serve("lib/vue-router/2.0.0/vue-router.js", "application/javascript"))
            .on(GET, "/lib/vue/2.0.1/vue.js", serve("/META-INF/resources/webjars/vue/2.0.1/dist/vue.js", "application/javascript"))
            .on(GET, "/lib/lodash/4.15.0/lodash.min.js", serve("/META-INF/resources/webjars/lodash/4.15.0/lodash.min.js", "application/javascript"))
            .on(GET, "/lib/moment/2.15.1/moment.min.js", serve("/META-INF/resources/webjars/moment/2.15.1/min/moment.min.js", "application/javascript"))
            .on(GET, "/lib/pikaday/1.4.0/pikaday.js", serve("/META-INF/resources/webjars/pikaday/1.4.0/pikaday.js", "application/javascript"))
            .on(GET, "/lib/pikaday/1.4.0/pikaday.css", serve("/META-INF/resources/webjars/pikaday/1.4.0/css/pikaday.css", "text/css"))

            .on(GET, "/api/collections", this::listCollections)
            .on(GET, "/<collection>", this::query)
            .on(POST, "/<collection>", this::post)
            .on(GET, "/<collection>/stats", this::stats)
            .on(GET, "/<collection>/captures", this::captures)
            .on(GET, "/<collection>/aliases", this::aliases)
            .on(GET, "/<collection>/access/rules", this::listAccessRules);

    Controller(DataStore dataStore, boolean verbose) {
        this.dataStore = dataStore;
        this.verbose = verbose;
    }

    Response listCollections(IHTTPSession request) {
        return jsonResponse(dataStore.listCollections());
    }

    Response stats(IHTTPSession req) throws IOException, Web.ResponseException {
        Index index = getIndex(req);
        Map<String,Object> map = new HashMap<>();
        map.put("estimatedRecordCount", index.estimatedRecordCount());
        Response response = new Response(Response.Status.OK, "application/json",
                gson.toJson(map));
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    Response captures(IHTTPSession session) throws IOException, Web.ResponseException {
        Index index = getIndex(session);
        String key = session.getParms().getOrDefault("key", "");
        long limit = Long.parseLong(session.getParms().getOrDefault("limit", "1000"));
        List<Capture> results = StreamSupport.stream(index.capturesAfter(key).spliterator(), false)
                .limit(limit)
                .collect(Collectors.<Capture>toList());
        return jsonResponse(results);
    }

    Response aliases(IHTTPSession session) throws IOException, Web.ResponseException {
        Index index = getIndex(session);
        String key = session.getParms().getOrDefault("key", "");
        long limit = Long.parseLong(session.getParms().getOrDefault("limit", "1000"));
        List<Alias> results = StreamSupport.stream(index.listAliases(key).spliterator(), false)
                .limit(limit)
                .collect(Collectors.<Alias>toList());
        return jsonResponse(results);
    }

    Response post(IHTTPSession session) throws IOException {
        String collection = session.getParms().get("collection");
        final Index index = dataStore.getIndex(collection, true);
        BufferedReader in = new BufferedReader(new InputStreamReader(session.getInputStream()));
        long added = 0;

        try (Index.Batch batch = index.beginUpdate()) {
            while (true) {
                String line = in.readLine();
                if (verbose) {
                    out.println(line);
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

    Response query(IHTTPSession session) throws IOException, Web.ResponseException {
        Index index = getIndex(session);
        Map<String,String> params = session.getParms();
        if (params.containsKey("q")) {
            return XmlQuery.query(session, index);
        } else if (params.containsKey("url")) {
            return WbCdxApi.query(session, index);
        } else {
            return collectionDetails(index.db);
        }

    }

    private Index getIndex(IHTTPSession session) throws IOException, Web.ResponseException {
        String collection = session.getParms().get("collection");
        final Index index = dataStore.getIndex(collection);
        if (index == null) {
            throw new Web.ResponseException(new Response(NOT_FOUND, "text/plain", "Collection does not exist"));
        }
        return index;
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

    private Response listAccessRules(IHTTPSession request) throws IOException, Web.ResponseException {
        Index index = getIndex(request);
        Iterable<AccessRule> rules = index.accessControl.list();
        return new Response(OK, "application/json", outputStream -> {
            OutputStream out = new BufferedOutputStream(outputStream);
            JsonWriter json = gson.newJsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            json.beginArray();
            for (AccessRule rule : rules) {
                gson.toJson(rule, AccessRule.class, json);
            }
            json.endArray();
            json.close();
            out.flush();
        });
    }

    @Override
    public Response handle(IHTTPSession session) throws IOException, Web.ResponseException {
        Response response = router.handle(session);
        if (response != null) {
            response.addHeader("Access-Control-Allow-Origin", "*");
        }
        return response;
    }
}

package outbackcdx;


import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import outbackcdx.NanoHTTPD.IHTTPSession;
import outbackcdx.NanoHTTPD.Response;
import outbackcdx.auth.Authorizer;
import outbackcdx.auth.Permission;

import javax.xml.stream.XMLStreamException;
import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static java.lang.System.out;
import static java.nio.charset.StandardCharsets.UTF_8;
import static outbackcdx.Json.GSON;
import static outbackcdx.NanoHTTPD.Method.*;
import static outbackcdx.NanoHTTPD.Response.Status.*;
import static outbackcdx.Web.*;

class Webapp implements Web.Handler {
    private final boolean verbose;
    private final DataStore dataStore;
    private final Web.Router router;
    private final Map<String,Object> dashboardConfig;

    private Response configJson(IHTTPSession req) {
        return jsonResponse(dashboardConfig);
    }

    private Response listAccessPolicies(IHTTPSession req) throws IOException, Web.ResponseException {
        return jsonResponse(getIndex(req).accessControl.listPolicies());
    }

    private Response deleteAccessRule(IHTTPSession req) throws IOException, Web.ResponseException, RocksDBException {
        long ruleId = Long.parseLong(req.getParms().get("ruleId"));
        boolean found = getIndex(req).accessControl.deleteRule(ruleId);
        return found ? ok() : notFound();
    }

    Webapp(DataStore dataStore, boolean verbose, Authorizer authorizer, Map<String, Object> dashboardConfig) {
        this.dataStore = dataStore;
        this.verbose = verbose;
        this.dashboardConfig = dashboardConfig;

        router = new Router(authorizer);
        router.on(GET, "/", serve("dashboard.html"));
        router.on(GET, "/api", serve("api.html"));
        router.on(GET, "/api.js", serve("api.js"));
        router.on(GET, "/add.svg", serve("add.svg"));
        router.on(GET, "/database.svg", serve("database.svg"));
        router.on(GET, "/outback.svg", serve("outback.svg"));
        router.on(GET, "/favicon.ico", serve("outback.svg"));
        router.on(GET, "/swagger.json", serve("swagger.json"));
        router.on(GET, "/lib/vue-router/2.0.0/vue-router.js", serve("lib/vue-router/2.0.0/vue-router.js"));
        router.on(GET, "/lib/vue/2.0.1/vue.js", serve("/META-INF/resources/webjars/vue/2.0.1/dist/vue.js"));
        router.on(GET, "/lib/lodash/4.15.0/lodash.min.js", serve("/META-INF/resources/webjars/lodash/4.15.0/lodash.min.js"));
        router.on(GET, "/lib/moment/2.15.2/moment.min.js", serve("/META-INF/resources/webjars/moment/2.15.2/min/moment.min.js"));
        router.on(GET, "/lib/pikaday/1.4.0/pikaday.js", serve("/META-INF/resources/webjars/pikaday/1.4.0/pikaday.js"));
        router.on(GET, "/lib/pikaday/1.4.0/pikaday.css", serve("/META-INF/resources/webjars/pikaday/1.4.0/css/pikaday.css"));
        router.on(GET, "/lib/redoc/1.4.1/redoc.min.js", serve("/META-INF/resources/webjars/redoc/1.4.1/dist/redoc.min.js"));
        router.on(GET, "/api/collections", this::listCollections);
        router.on(GET, "/config.json", this::configJson);
        router.on(GET, "/<collection>", this::query);
        router.on(POST, "/<collection>", this::post, Permission.INDEX_EDIT);
        router.on(POST, "/<collection>/delete", this::delete, Permission.INDEX_EDIT);
        router.on(GET, "/<collection>/stats", this::stats);
        router.on(GET, "/<collection>/captures", this::captures);
        router.on(GET, "/<collection>/aliases", this::aliases);

        if (FeatureFlags.experimentalAccessControl()) {
            router.on(GET, "/<collection>/ap/<accesspoint>", this::query);
            router.on(GET, "/<collection>/ap/<accesspoint>/check", this::checkAccess);
            router.on(POST, "/<collection>/ap/<accesspoint>/check", this::checkAccessBulk);
            router.on(GET, "/<collection>/access/rules", this::listAccessRules);
            router.on(POST, "/<collection>/access/rules", this::postAccessRules, Permission.RULES_EDIT);
            router.on(GET, "/<collection>/access/rules/new", this::getNewAccessRule, Permission.RULES_EDIT);
            router.on(GET, "/<collection>/access/rules/<ruleId>", this::getAccessRule);
            router.on(DELETE, "/<collection>/access/rules/<ruleId>", this::deleteAccessRule, Permission.RULES_EDIT);
            router.on(GET, "/<collection>/access/policies", this::listAccessPolicies);
            router.on(POST, "/<collection>/access/policies", this::postAccessPolicy, Permission.POLICIES_EDIT);
            router.on(GET, "/<collection>/access/policies/<policyId>", this::getAccessPolicy);
        }
    }

    Response listCollections(IHTTPSession request) {
        return jsonResponse(dataStore.listCollections());
    }

    Response stats(IHTTPSession req) throws IOException, Web.ResponseException {
        Index index = getIndex(req);
        Map<String,Object> map = new HashMap<>();
        map.put("estimatedRecordCount", index.estimatedRecordCount());
        Response response = new Response(Response.Status.OK, "application/json",
                GSON.toJson(map));
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

    Response delete(IHTTPSession session) throws IOException {
        String collection = session.getParms().get("collection");
        final Index index = dataStore.getIndex(collection);
        BufferedReader in = new BufferedReader(new InputStreamReader(session.getInputStream()));
        long deleted = 0;

        try (Index.Batch batch = index.beginUpdate()) {
            while (true) {
                String line = in.readLine();
                if (verbose) {
                    out.println("DELETE " + line);
                }
                if (line == null) break;
                if (line.startsWith(" CDX")) continue;

                try {
                    if (line.startsWith("@alias ")) {
                        throw new UnsupportedOperationException("Deleting of aliases is not yet implemented");
                    }

                    batch.deleteCapture(Capture.fromCdxLine(line));
                    deleted++;
                } catch (Exception e) {
                    return new Response(BAD_REQUEST, "text/plain", "At line: " + line + "\n" + formatStackTrace(e));
                }
            }

            batch.commit();
        }
        return new Response(OK, "text/plain", "Deleted " + deleted + " records\n");
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
                    return new Response(BAD_REQUEST, "text/plain", "At line: " + line + "\n" + formatStackTrace(e));
                }
            }

            batch.commit();
        }
        return new Response(OK, "text/plain", "Added " + added + " records\n");
    }

    private String formatStackTrace(Exception e) {
        StringWriter stacktrace = new StringWriter();
        e.printStackTrace(new PrintWriter(stacktrace));
        return stacktrace.toString();
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

    private <T> T fromJson(IHTTPSession session, Class<T> clazz) {
        return GSON.fromJson(new InputStreamReader(session.getInputStream(), UTF_8), clazz);
    }

    private Response getAccessPolicy(IHTTPSession req) throws IOException, Web.ResponseException {
        long policyId = Long.parseLong(req.getParms().get("policyId"));
        AccessPolicy policy = getIndex(req).accessControl.policy(policyId);
        if (policy == null) {
            return notFound();
        }
        return jsonResponse(policy);
    }

    private Response postAccessPolicy(IHTTPSession session) throws IOException, Web.ResponseException, RocksDBException {
        AccessPolicy policy = fromJson(session, AccessPolicy.class);
        Long id = getIndex(session).accessControl.put(policy);
        return id == null ? ok() : created(id);
    }

    private Response postAccessRules(IHTTPSession session) throws IOException, Web.ResponseException, RocksDBException {
        AccessControl accessControl = getIndex(session).accessControl;

        // parse rules
        List<AccessRule> rules;
        boolean single = false;
        if ("application/xml".equals(session.getHeaders().get("content-type"))) {
            try {
                rules = AccessRuleXml.parseRules(session.getInputStream());
            } catch (XMLStreamException e) {
                return new Response(BAD_REQUEST, "text/plain", formatStackTrace(e));
            }
        } else { // JSON format
            JsonReader reader = GSON.newJsonReader(new InputStreamReader(session.getInputStream(), UTF_8));
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                rules = new ArrayList<>();
                while (reader.hasNext()) {
                    AccessRule rule = GSON.fromJson(reader, AccessRule.class);
                    rules.add(rule);
                }
                reader.endArray();
            } else { // single rule
                rules = Arrays.asList((AccessRule)GSON.fromJson(reader, AccessRule.class));
                single = true;
            }
        }

        // validate rules
        List<AccessRuleError> errors = new ArrayList<>();
        for (AccessRule rule: rules) {
            errors.addAll(rule.validate());
        }

        // return an error response if any failed
        if (!errors.isEmpty()) {
            Response response = jsonResponse(errors);
            response.setStatus(BAD_REQUEST);
            return response;
        }

        // save all the rules
        List<Long> ids = new ArrayList<>();
        for (AccessRule rule : rules) {
            ids.add(accessControl.put(rule));
        }

        // return succesful response
        if (single) {
            Long id = ids.get(0);
            return id == null ? ok() : created(id);
        } else {
            return jsonResponse(ids);
        }
    }

    private Response ok() {
        return new Response(OK, null, "");
    }

    private Response created(long id) {
        Map<String,String> map = new HashMap<>();
        map.put("id", Long.toString(id));
        return new Response(CREATED, "application/json", GSON.toJson(map));
    }

    private Response getAccessRule(IHTTPSession req) throws IOException, Web.ResponseException, RocksDBException {
        Index index = getIndex(req);
        Long ruleId = Long.parseLong(req.getParms().get("ruleId"));
        AccessRule rule = index.accessControl.rule(ruleId);
        if (rule == null) {
            return notFound();
        }
        return jsonResponse(rule);
    }

    private Response getNewAccessRule(IHTTPSession request) {
        AccessRule rule = new AccessRule();
        return jsonResponse(rule);
    }

    private Response listAccessRules(IHTTPSession request) throws IOException, Web.ResponseException {
        Index index = getIndex(request);
        List<AccessRule> rules = new ArrayList<>(index.accessControl.list());
        if (request.getParms().getOrDefault("sort", "id").equals("surt")) {
            Comparator<AccessRule> comparator = Comparator.comparing(rule -> rule.ssurtPrefixes().findFirst().orElse(""));
            rules.sort(comparator.thenComparingLong(rule -> rule.id));
        }
        return new Response(OK, "application/json", outputStream -> {
            OutputStream out = new BufferedOutputStream(outputStream);
            JsonWriter json = GSON.newJsonWriter(new OutputStreamWriter(out, UTF_8));
            json.beginArray();
            for (AccessRule rule : rules) {
                GSON.toJson(rule, AccessRule.class, json);
            }
            json.endArray();
            json.close();
            out.flush();
        });
    }

    Response checkAccess(IHTTPSession request) throws IOException, ResponseException {
        String accesspoint = request.getParms().get("accesspoint");
        String url = getMandatoryParam(request, "url");
        String timestamp = getMandatoryParam(request, "timestamp");

        Date captureTime = Date.from(LocalDateTime.parse(timestamp, Capture.arcTimeFormat).toInstant(ZoneOffset.UTC));
        Date accessTime = new Date();

        return jsonResponse(getIndex(request).accessControl.checkAccess(accesspoint, url, captureTime, accessTime));
    }

    public static class AccessQuery {
        public String url;
        public String timestamp;
    }

    Response checkAccessBulk(IHTTPSession request) throws IOException, ResponseException {
        String accesspoint = request.getParms().get("accesspoint");
        Index index = getIndex(request);

        AccessQuery[] queries = fromJson(request, AccessQuery[].class);
        List<AccessDecision> responses = new ArrayList<>();

        for (AccessQuery query: queries) {
            Date captureTime = Date.from(LocalDateTime.parse(query.timestamp, Capture.arcTimeFormat).toInstant(ZoneOffset.UTC));
            Date accessTime = new Date();
            responses.add(index.accessControl.checkAccess(accesspoint, query.url, captureTime, accessTime));
        }

        return jsonResponse(responses);
    }


    private String getMandatoryParam(IHTTPSession request, String name) throws ResponseException {
        String value = request.getParms().get(name);
        if (value == null) {
            throw new Web.ResponseException(badRequest("missing mandatory parameter: " + name));
        }
        return value;
    }

    @Override
    public Response handle(IHTTPSession session) throws Exception {
        Response response = router.handle(session);
        if (response != null) {
            response.addHeader("Access-Control-Allow-Origin", "*");
        }
        return response;
    }

}

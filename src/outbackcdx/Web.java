package outbackcdx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import outbackcdx.auth.Authorizer;
import outbackcdx.auth.Permission;
import outbackcdx.auth.Permit;

import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static outbackcdx.Json.JSON_MAPPER;
import static outbackcdx.Web.Status.*;

class Web {
    private static final Map<String,String> versionCache = new HashMap<>();

    interface Handler {
        Response handle(Request request) throws Exception;
    }

    enum Method {
        GET, POST, PUT, DELETE
    }

    public static class Status {
        public static final int OK = 200, CREATED = 201,
                TEMPORARY_REDIRECT = 307,
                BAD_REQUEST = 400, FORBIDDEN = 403, NOT_FOUND = 404,
                INTERNAL_ERROR = 500;
    }

    interface IStreamer {
        void stream(OutputStream out) throws IOException;
    }

    public static class Response {
        private int status;
        private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final long bodyLength;
        private final IStreamer bodyWriter;

        public static final Response ALREADY_SENT = new Response(-1, null, "");

        public Response(int status, String mime, String body) {
            this.status = status;
            byte[] bodyBytes = body.getBytes(UTF_8);
            this.bodyLength = bodyBytes.length;
            this.bodyWriter = out -> out.write(bodyBytes);
            if (mime != null) addHeader("Content-Type", mime);
        }

        public Response(int status, String mime, InputStream inputStream) {
            this.status = status;
            this.bodyLength = 0;
            this.bodyWriter = inputStream::transferTo;
            if (mime != null) addHeader("Content-Type", mime);
        }

        public Response(int status, String mime, IStreamer bodyWriter) {
            this.status = status;
            this.bodyLength = 0;
            this.bodyWriter = bodyWriter;
            if (mime != null) addHeader("Content-Type", mime);
        }

        public void addHeader(String name, String value) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public int getStatus() {
            return status;
        }

        public Map<String, List<String>> getHeaders() {
            return headers;
        }

        public IStreamer getBodyWriter() {
            return bodyWriter;
        }
    }

    static class SHandler implements HttpHandler {
        private final Handler handler;
        private final Authorizer authorizer;

        SHandler(Handler handler, Authorizer authorizer) {
            this.handler = handler;
            this.authorizer = authorizer;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                Response response;
                try {
                    String authnHeader = exchange.getRequestHeaders().getFirst("authorization");
                    Permit permit = authorizer.verify(authnHeader);
                    SRequest request = new SRequest(exchange, permit);
                    response = handler.handle(request);
                } catch (Web.ResponseException e) {
                    response = e.response;
                } catch (Exception e) {
                    e.printStackTrace();
                    response = new Response(INTERNAL_ERROR, "text/plain", e + "\n");
                }

                if (response != Response.ALREADY_SENT) {
                    exchange.getResponseHeaders().putAll(response.headers);
                    exchange.sendResponseHeaders(response.status, response.bodyLength);
                    response.bodyWriter.stream(exchange.getResponseBody());
                }
            } finally {
                exchange.close();
            }
        }
    }

    static class SRequest implements Request {
        private final HttpExchange exchange;
        private final Permit permit;
        private final MultiMap<String, String> params = new MultiMap<>();

        SRequest(HttpExchange exchange, Permit permit) {
            this.exchange = exchange;
            this.permit = permit;
            parseQueryString(exchange.getRequestURI().getQuery());
        }

        private void parseQueryString(String query) {
            if (query == null) return;
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) continue;
                String[] parts = pair.split("=", 2);
                if (parts.length != 2) continue;
                params.add(URLDecoder.decode(parts[0], UTF_8),
                        URLDecoder.decode(parts[1], UTF_8));
            }
        }

        @Override
        public String method() {
            return exchange.getRequestMethod();
        }

        @Override
        public String path() {
            return exchange.getRequestURI().getPath();
        }

        @Override
        public String contextPath() {
            String contextPath = exchange.getHttpContext().getPath();
            return contextPath.equals("/") ? "" : contextPath;
        }

        @Override
        public MultiMap<String, String> params() {
            return params;
        }

        @Override
        public String header(String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }

        @Override
        public InputStream inputStream() {
            return exchange.getRequestBody();
        }

        @Override
        public boolean hasPermission(Permission permission) {
            return permit.permissions.contains(permission);
        }

        @Override
        public String username() {
            return permit.username;
        }

        @Override
        public String url() {
            return exchange.getRequestURI().toString();
        }

        @Override
        public OutputStream streamResponse(int status, MultiMap<String, String> headers) throws IOException {
            if (headers != null) headers.forEach(exchange.getResponseHeaders()::add);
            exchange.sendResponseHeaders(status, 0);
            return exchange.getResponseBody();
        }
    }

    public static class ResponseException extends Exception {
        final Response response;

        ResponseException(Response response) {
            this.response = response;
        }
    }

    private static String guessType(String file) {
        switch (file.substring(file.lastIndexOf('.') + 1)) {
            case "css":
                return "text/css";
            case "html":
                return "text/html";
            case "js":
                return "application/javascript";
            case "json":
                return "application/json";
            case "svg":
                return "image/svg+xml";
            default:
                throw new IllegalArgumentException("Unknown file type: " + file);
        }
    }

    static Handler serve(String file) {
        URL url = Web.class.getResource(file);
        if (url == null) {
            throw new IllegalArgumentException("No such resource: " + file);
        }
        return req -> new Response(OK, guessType(file), url.openStream());
    }

    static synchronized String version(String groupId, String artifactId) {
        String version = versionCache.get(groupId + ":" + artifactId);
        if (version != null) return version;
        String path = "/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        URL url = Webapp.class.getResource(path);
        if (url == null) throw new RuntimeException("Not found on classpath: " + path);
        Properties properties = new Properties();
        try (InputStream stream = url.openStream()) {
            properties.load(stream);
        } catch (IOException e) {
            throw new RuntimeException("Error reading " + path, e);
        }
        version = properties.getProperty("version");
        versionCache.put(groupId + ":" + artifactId, version);
        return version;
    }

    static Handler interpolated(String file) {
        URL url = Web.class.getResource(file);
        if (url == null) {
            throw new IllegalArgumentException("No such resource: " + file);
        }
        String raw;
        try (InputStream stream = url.openStream()) {
            raw = slurp(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String processed = interpolate(raw);
        return req -> new Response(OK, guessType(file), processed);
    }

    private static String interpolate(String raw) {
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\\$\\{version:([^:}]+):([^:}]+)}").matcher(raw);
        int pos = 0;
        while (m.find()) {
            sb.append(raw, pos, m.start());
            sb.append(version(m.group(1), m.group(2)));
            pos = m.end();
        }
        sb.append(raw, pos, raw.length());
        return sb.toString();
    }

    private static String slurp(InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n = stream.read(buffer); n >= 0; n = stream.read(buffer)) {
            baos.write(buffer, 0, n);
        }
        return baos.toString(UTF_8);
    }

    static Response jsonResponse(Object data) throws JsonProcessingException {
        Response response =  new Response(OK, "application/json", JSON_MAPPER.writeValueAsString(data));
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    static Response notFound() {
        return new Response(NOT_FOUND, "text/plain", "Not found\n");
    }

    static Response forbidden(String permission) {
        return new Response(FORBIDDEN, "text/plain", "Permission '" + permission + "' is required for this action.\n");
    }

    static Response badRequest(String message) {
        return new Response(BAD_REQUEST, "text/plain", message);
    }

    static Response redirect(String location) {
        Response response = new Response(TEMPORARY_REDIRECT, "text/plain", "Temporary Redirect");
        response.addHeader("Location", location);
        return response;
    }

    static class Router implements Handler {
        private final List<Route> routes = new ArrayList<>();

        @Override
        public Response handle(Request request) throws Exception {
            for (Route route : routes) {
                Response result = route.handle(request);
                if (result != null) {
                    return result;
                }
            }
            return Web.notFound();
        }

        public Router on(Method method, String pathPattern, Handler handler, Permission permission) {
            routes.add(new Route(method, pathPattern, handler, permission));
            return this;
        }

        public Router on(Method method, String pathPattern, Handler handler) {
            return on(method, pathPattern, handler, null);
        }
    }

    private static class Route {
        private final static Pattern KEY_PATTERN = Pattern.compile("<([a-z_][a-zA-Z0-9_]*)(?::([^>]*))?>");

        private final Method method;
        private final Handler handler;
        private final String pattern;
        private final Pattern re;
        private final List<String> keys = new ArrayList<>();
        private final Permission permission;

        Route(Method method, String pattern, Handler handler, Permission permission) {
            this.method = method;
            this.handler = handler;
            this.pattern = pattern;
            this.permission = permission;
            this.re = compile();
        }

        private Pattern compile() {
            StringBuilder out = new StringBuilder();
            Matcher m = KEY_PATTERN.matcher(pattern);
            int pos = 0;
            while (m.find(pos)) {
                String key = m.group(1);
                String regex = m.group(2);
                if (regex == null) {
                    regex = "[^/,;?]+";
                }

                out.append(Pattern.quote(pattern.substring(pos, m.start())));
                out.append('(').append(regex).append(')');

                keys.add(key);
                pos = m.end();
            }

            out.append(Pattern.quote(pattern.substring(pos)));
            return Pattern.compile(out.toString());
        }

        public Response handle(Request request) throws Exception {
            if (method != null && !request.method().equalsIgnoreCase(method.name())) {
                return null;
            }

            Matcher match = re.matcher(request.relativePath());
            if (!match.matches()) {
                return null;
            }

            if (permission != null && !request.hasPermission(permission)) {
                return Web.forbidden(permission.name().toLowerCase());
            }

            for (int i = 0; i < match.groupCount(); i++) {
                request.params().put(keys.get(i), match.group(i + 1));
            }

            return handler.handle(request);
        }

        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    public interface Request {
        String method();

        /**
         * The full request path including the context path.
         */
        String path();

        /**
         * The request path relative to the context path.
         */
        default String relativePath() {
            return path().substring(contextPath().length());
        }

        String contextPath();

        MultiMap<String, String> params();

        String header(String name);

        InputStream inputStream();

        boolean hasPermission(Permission permission);

        String username();

        default String param(String name) {
            return params().get(name);
        }

        default String param(String name, String defaultValue) {
            return params().getOrDefault(name, defaultValue);
        }

        default String mandatoryParam(String name) throws ResponseException {
            String value = param(name);
            if (value == null) {
                throw new ResponseException(badRequest("missing mandatory parameter: " + name));
            }
            return value;
        }

        /**
         * Should be called by constructor to stash the original url. Needs to happen
         * before before Route.handle() because it adds path tokens to params(), making
         * it impossible to compute the original url.
         */
        default String rebuildUrl() {
            StringBuilder buf = new StringBuilder(path());
            if (params() != null) {
                boolean first = true;
                for (String key: params().keySet()) {
                    for (String value: params().getAll(key)) {
                        buf.append(first ? '?' : '&');
                        first = false;
                        buf.append(URLEncoder.encode(key, UTF_8));
                        buf.append('=');
                        buf.append(URLEncoder.encode(value, UTF_8));
                    }
                }
            }
            return buf.toString();
        }

        String url();

        OutputStream streamResponse(int status, MultiMap<String, String> headers) throws IOException;
    }
}

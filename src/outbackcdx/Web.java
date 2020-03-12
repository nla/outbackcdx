package outbackcdx;

import static outbackcdx.Json.GSON;
import static outbackcdx.NanoHTTPD.Response.Status.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import outbackcdx.NanoHTTPD.IHTTPSession;
import outbackcdx.NanoHTTPD.Method;
import outbackcdx.NanoHTTPD.Response;
import outbackcdx.auth.Authorizer;
import outbackcdx.auth.Permission;
import outbackcdx.auth.Permit;

class Web {
    private static final Map<String,String> versionCache = new HashMap<>();

    interface Handler {
        Response handle(Request request) throws Exception;
    }

    static class Server extends NanoHTTPD {
        private final Handler handler;
        private final Authorizer authorizer;
        private final String contextPath;

        Server(ServerSocket socket, String contextPath, Handler handler, Authorizer authorizer) {
            super(socket);
            this.contextPath = contextPath;
            this.handler = handler;
            this.authorizer = authorizer;
        }

        @Override
        public Response serve(IHTTPSession session) {
            try {
                String authnHeader = session.getHeaders().getOrDefault("authorization", "");
                Permit permit = authorizer.verify(authnHeader);
                NRequest request = new NRequest(session, permit, contextPath);
                return handler.handle(request);
            } catch (Web.ResponseException e) {
                return e.response;
            } catch (Exception e) {
                e.printStackTrace();
                return new Response(INTERNAL_ERROR, "text/plain", e.toString() + "\n");
            }
        }
    }

    static class NRequest implements Request {
        private final IHTTPSession session;
        private final Permit permit;
        private final String url;
        private final String contextPath;

        NRequest(IHTTPSession session, Permit permit, String contextPath) {
            this.session = session;
            this.permit = permit;
            this.contextPath = contextPath;
            this.url = rebuildUrl();
        }

        @Override
        public String method() {
            return session.getMethod().name();
        }

        @Override
        public String path() {
            return session.getUri();
        }

        @Override
        public String contextPath() {
            return contextPath;
        }

        @Override
        public MultiMap<String, String> params() {
            return session.getParms();
        }

        @Override
        public String header(String name) {
            return session.getHeaders().get(name);
        }

        @Override
        public InputStream inputStream() {
            return session.getInputStream();
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
            return url;
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
        return baos.toString("utf-8");
    }

    static Response jsonResponse(Object data) {
        Response response =  new Response(OK, "application/json", GSON.toJson(data));
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

    public static interface Request {
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
                        try {
                            buf.append(URLEncoder.encode(key, "UTF-8"));
                            buf.append('=');
                            buf.append(URLEncoder.encode(value, "UTF-8"));
                        } catch (UnsupportedEncodingException e) {
                            throw new RuntimeException(e); // not possible
                        }
                    }
                }
            }
            return buf.toString();
        }

        String url();
    }
}

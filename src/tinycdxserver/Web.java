package tinycdxserver;

import com.grack.nanojson.JsonWriter;
import tinycdxserver.NanoHTTPD.IHTTPSession;
import tinycdxserver.NanoHTTPD.Method;
import tinycdxserver.NanoHTTPD.Response;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tinycdxserver.NanoHTTPD.Response.Status.INTERNAL_ERROR;
import static tinycdxserver.NanoHTTPD.Response.Status.NOT_FOUND;
import static tinycdxserver.NanoHTTPD.Response.Status.OK;

class Web {

    interface Handler {
        Response handle(IHTTPSession session) throws IOException;
    }

    static class Server extends NanoHTTPD {
        private final Handler handler;

        Server(ServerSocket socket, Handler handler) {
            super(socket);
            this.handler = handler;
        }

        @Override
        public Response serve(IHTTPSession request) {
            try {
                return handler.handle(request);
            } catch (Exception e) {
                e.printStackTrace();
                return new Response(INTERNAL_ERROR, "text/plain", e.toString() + "\n");
            }
        }
    }

    private static String extension(String filename) {
        int i = filename.lastIndexOf('.');
        return i != -1 ? filename.substring(i + 1) : "";
    }

    private static String guessContentType(String filename) {
        switch (extension(filename)) {
            case "js": return "application/javascript";
            case "css": return "text/css";
            case "html": return "text/html";
            case "tag": return "riot/tag";
            default: return "application/octet-stream";
        }
    }

    static Response serveResource(String uri) throws IOException {
        if (uri.contains("..")) {
            return notFound();
        }

        URL resource = Web.class.getResource(uri.substring(1));
        if (resource == null) {
            return notFound();
        }

        return new Response(OK, guessContentType(uri), resource.openStream());
    }

    static Response jsonResponse(Object data) {
        Response response =  new Response(OK, "application/json", JsonWriter.string(data));
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    private static Response notFound() {
        return new Response(NOT_FOUND, "text/plain", "Not found\n");
    }

    static class Router implements Handler {
        private final List<Handler> routes = new ArrayList<>();

        @Override
        public Response handle(IHTTPSession request) {
            try {
                for (Handler route : routes) {
                    Response result = route.handle(request);
                    if (result != null) {
                        return result;
                    }
                }
                return Web.notFound();
            } catch (Exception e) {
                e.printStackTrace();
                return new Response(INTERNAL_ERROR, "text/plain", e.toString() + "\n");
            }
        }

        public Router on(Method method, String pathPattern, Handler handler) {
            routes.add(new Route(method, pathPattern, handler));
            return this;
        }
    }

    private static class Route implements Handler {
        private final static Pattern KEY_PATTERN = Pattern.compile("<([a-z_][a-zA-Z0-9_]*)(?::([^>]*))?>");

        private final Method method;
        private final Handler handler;
        private final String pattern;
        private final Pattern re;
        private final List<String> keys = new ArrayList<>();

        Route(Method method, String pattern, Handler handler) {
            this.method = method;
            this.handler = handler;
            this.pattern = pattern;
            this.re = compile();
        }

        @Override
        public Response handle(IHTTPSession request) throws IOException {
            if (method == null || method == request.getMethod()) {
                Matcher m = re.matcher(request.getUri());
                if (m.matches()) {
                    for (int i = 0; i < m.groupCount(); i++) {
                        request.getParms().put(keys.get(i), m.group(i + 1));
                    }
                    return handler.handle(request);
                }
            }
            return null;
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
    }

}

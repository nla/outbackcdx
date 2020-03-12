package outbackcdx;

import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import outbackcdx.auth.Authorizer;
import outbackcdx.auth.Permission;
import outbackcdx.auth.Permit;

import java.io.*;
import java.util.Deque;
import java.util.Map;

public class UWeb {

    static class UServer {
        private final Undertow undertow;
        private final Authorizer authorizer;
        private final Web.Handler handler;
        private final String contextPath;

        UServer(String host, int port, String contextPath, Web.Handler handler, Authorizer authorizer) {
            this.handler = handler;
            this.authorizer = authorizer;
            this.contextPath = contextPath;
            undertow = Undertow.builder()
                    .setHandler(new BlockingHandler(this::dispatch))
                    .addHttpListener(port, host)
                    .build();
        }

        private void dispatch(HttpServerExchange exchange) throws Exception {
            String authnHeader = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
            if (authnHeader == null) {
                authnHeader = "";
            }
            try {
                Permit permit = authorizer.verify(authnHeader);
                URequest request = new URequest(exchange, permit, contextPath);
                NanoHTTPD.Response response = handler.handle(request);
                sendResponse(exchange, response);
            } catch (Web.ResponseException e) {
                sendResponse(exchange, e.response);
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                e.printStackTrace();
                sendResponse(exchange, new NanoHTTPD.Response(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", sw.toString()));
            }
        }

        private void sendResponse(HttpServerExchange exchange, NanoHTTPD.Response response) throws IOException {
            exchange.setStatusCode(response.getStatus().getRequestStatus());
            if (response.getMimeType() != null) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, response.getMimeType());
            }
            NanoHTTPD.IStreamer streamer = response.getStreamer();
            OutputStream outputStream = exchange.getOutputStream();
            if (streamer != null) {
                streamer.stream(outputStream);
            } else {
                InputStream stream = response.getData();
                byte[] buf = new byte[8192];
                while (true) {
                    int n = stream.read(buf);
                    if (n < 0) break;
                    outputStream.write(buf, 0, n);
                }
            }
            outputStream.close();
        }

        public void start() {
            undertow.start();
        }
    }

    static class URequest implements Web.Request {
        private final HttpServerExchange exchange;
        private final MultiMap<String,String> params;
        private final Permit permit;
        private final String url;
        private final String contextPath;

        public URequest(HttpServerExchange exchange, Permit permit, String contextPath) {
            this.exchange = exchange;
            this.permit = permit;
            this.contextPath = contextPath;
            params = new MultiMap<>();
            for (Map.Entry<String, Deque<String>> pair : exchange.getQueryParameters().entrySet()) {
                for (String value: pair.getValue()) {
                    params.add(pair.getKey(), value);
                }
            }
            this.url = rebuildUrl();
        }

        @Override
        public String method() {
            return exchange.getRequestMethod().toString();
        }

        @Override
        public String path() {
            return exchange.getRequestPath();
        }

        @Override
        public String contextPath() {
            return contextPath;
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
            return exchange.getInputStream();
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
}

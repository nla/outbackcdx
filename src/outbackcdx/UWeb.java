package outbackcdx;

import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import outbackcdx.auth.Authorizer;
import outbackcdx.auth.Permission;
import outbackcdx.auth.Permit;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.Deque;
import java.util.Map;

public class UWeb {

    static class UServer implements Closeable {
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
                Web.Response response = handler.handle(request);
                if (response != Web.Response.ALREADY_SENT) sendResponse(exchange, response);
            } catch (Web.ResponseException e) {
                sendResponse(exchange, e.response);
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                e.printStackTrace();
                sendResponse(exchange, new Web.Response(Web.Status.INTERNAL_ERROR, "text/plain", sw.toString()));
            }
        }

        private void sendResponse(HttpServerExchange exchange, Web.Response response) throws IOException {
            exchange.setStatusCode(response.getStatus());
            response.getHeaders().forEach((name, values) -> {
                for (String value : values) {
                    exchange.getResponseHeaders().add(HttpString.tryFromString(name), value);
                }
            });
            Web.IStreamer streamer = response.getBodyWriter();
            OutputStream outputStream = exchange.getOutputStream();
            if (streamer != null) {
                streamer.stream(outputStream);
            }
            outputStream.close();
        }

        public void start() {
            undertow.start();
        }

        public void close() {
            undertow.stop();
        }

        public int port() {
            return ((InetSocketAddress)undertow.getListenerInfo().get(0).getAddress()).getPort();
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

        @Override
        public OutputStream streamResponse(int status, String contentType, Map<String, String> headers) throws IOException {
            if (headers != null) {
                headers.forEach((name, value) ->
                        exchange.getResponseHeaders().add(HttpString.tryFromString(name), value));
            }
            exchange.setStatusCode(status);
            return exchange.getOutputStream();
        }
    }
}

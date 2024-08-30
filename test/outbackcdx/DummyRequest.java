package outbackcdx;

import outbackcdx.auth.Permission;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

class DummyRequest implements Web.Request {
    private final MultiMap<String, String> params = new MultiMap<>();
    private final Web.Method method;
    private final String url;
    private final String data;
    private ByteArrayOutputStream streamedResponseBody;
    private int streamedStatus;
    private Map<String, String> streamedHeaders;
    private String streamedContentType;

    public DummyRequest(Web.Method method, String url) {
        this(method, url, null);
    }

    public DummyRequest(Web.Method method, String url, String data) {
        this.method = method;
        this.url = url;
        this.data = data;
    }

    @Override
    public String method() {
        return method.name();
    }

    @Override
    public String path() {
        return url;
    }

    @Override
    public String contextPath() {
        return "";
    }

    @Override
    public MultiMap<String, String> params() {
        return params;
    }

    @Override
    public String header(String name) {
        return "";
    }

    @Override
    public InputStream inputStream() {
        if (data != null) return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
        return null;
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return true;
    }

    @Override
    public String username() {
        return "test";
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public OutputStream streamResponse(int status, String contentType, Map<String, String> headers) throws IOException {
        this.streamedResponseBody = new ByteArrayOutputStream();
        this.streamedStatus = status;
        this.streamedHeaders = headers;
        this.streamedContentType = contentType;
        return streamedResponseBody;
    }

    public void parm(String name, String value) {
        params.add(name, value);
    }

    public Web.Response streamedResponse() {
        Web.Response response = new Web.Response(streamedStatus, streamedContentType,
                new ByteArrayInputStream(streamedResponseBody.toByteArray()));
        if (streamedHeaders != null) streamedHeaders.forEach(response::addHeader);
        return response;
    }
}

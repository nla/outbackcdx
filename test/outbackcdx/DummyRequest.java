package outbackcdx;

import outbackcdx.auth.Permission;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

class DummyRequest implements Web.Request {
    private final MultiMap<String, String> params = new MultiMap<>();
    private final Web.Method method;
    private final String url;
    private final String data;

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

    public void parm(String name, String value) {
        params.add(name, value);
    }
}

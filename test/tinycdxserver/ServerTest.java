package tinycdxserver;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ServerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void test() throws IOException {
        File root = folder.newFolder();
        DataStore manager = new DataStore(root);
        Server server = new Server(manager, "127.0.0.1", -1);
        server.post(new DummySession("/test").data("- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n"));

    }

    private static class DummySession implements NanoHTTPD.IHTTPSession {
        InputStream stream = new ByteArrayInputStream(new byte[0]);
        Map<String, String> parms = new HashMap<String, String>();
        String url;

        public DummySession(String url) {
            this.url = url;
        }

        public DummySession data(String data) {
            stream = new ByteArrayInputStream(data.getBytes(Charset.forName("UTF-8")));
            return this;
        }

        @Override
        public void execute() throws IOException {
            // nothing
        }

        @Override
        public Map<String, String> getParms() {
            return parms;
        }

        @Override
        public Map<String, String> getHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public String getUri() {
            return url;
        }

        @Override
        public String getQueryParameterString() {
            return "";
        }

        @Override
        public NanoHTTPD.Method getMethod() {
            return NanoHTTPD.Method.GET;
        }

        @Override
        public InputStream getInputStream() {
            return stream;
        }
    }
}

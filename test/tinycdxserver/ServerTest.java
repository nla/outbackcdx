package tinycdxserver;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Server server;

    @Before
    public void setUp() throws IOException {
        File root = folder.newFolder();
        DataStore manager = new DataStore(root, null);
        server = new Server(manager, "127.0.0.1", -1);
    }

    private String readOutput(NanoHTTPD.Response response) throws UnsupportedEncodingException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.send(out);
        return out.toString("UTF-8");
    }

    @Test
    public void test() throws IOException {
        server.post(new DummySession("/test").data("- 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20050614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n"));
        server.post(new DummySession("/test").data("- 20060614070159 http://nla.gov.au/ text/html 200 XKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20050614070144-00003-crawling016.archive.org\n- 20050614070159 http://example.com/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX - - - 337023 NLA-AU-CRAWL-000-20060614070144-00003-crawling016.archive.org\n"));
        {
            NanoHTTPD.Response response = server.query(new DummySession("/test").parm("url", "nla.gov.au"));
            assertEquals(NanoHTTPD.Response.Status.OK, response.getStatus());
            String data = readOutput(response);
            assertTrue(data.indexOf("au,gov,nla)/ 20050614070159 http://nla.gov.au/ text/html 200 AKMCCEPOOWFMGGO5635HFZXGFRLRGWIX 0") != -1);
            assertTrue(data.indexOf("example") == -1);
        }


        {
            NanoHTTPD.Response response = server.query(new DummySession("/test").parm("q", "type:urlquery url:http%3A%2F%2Fnla.gov.au%2F"));
            String data = readOutput(response);
            assertTrue(data.indexOf("20050614070159") != -1);
            assertTrue(data.indexOf("20060614070159") != -1);
        }

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

        public DummySession parm(String key, String value) {
            parms.put(key, value);
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

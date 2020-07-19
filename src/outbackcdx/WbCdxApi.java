package outbackcdx;

import static java.nio.charset.StandardCharsets.UTF_8;
import static outbackcdx.Json.GSON;
import static outbackcdx.NanoHTTPD.Response.Status.OK;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Date;

import com.google.gson.stream.JsonWriter;

import outbackcdx.NanoHTTPD.Response;

/**
 * Implements a partial, semi-compatible subset of the various CDX server APIs.
 *
 * The intent is to at least be compatible with Pywb's RemoteCDXSource. Anything
 * extra is bonus.
 *
 * wb: https://github.com/internetarchive/wayback/tree/master/wayback-cdx-server
 * pywb: https://github.com/ikreymer/pywb/wiki/CDX-Server-API
 */
public class WbCdxApi {
    public static Response queryIndex(Web.Request request, Index index, Iterable<FilterPlugin> filterPlugins) {
        Query query = new Query(request.params(), filterPlugins);
        Iterable<Capture> captures = query.execute(index);

        boolean outputJson = "json".equals(request.param("output"));
        boolean outputJson2 = "jsondict".equals(request.param("output"));

        Response response = new Response(OK, outputJson || outputJson2 ? "application/json" : "text/plain", outputStream -> {
            Writer out = new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8));
            OutputFormat outf;

            if (outputJson) {
                outf = new JsonFormat(out, query.fields);
            } else if (outputJson2) {
                outf = new JsonDictFormat(out, query.fields);
            } else {
                outf = new TextFormat(out, query.fields);
            }

            long row = 0;
            try {
                for (Capture capture : captures) {
                    if (row >= query.limit) {
                        break;
                    }
                    outf.writeCapture(capture);
                    row++;
                }
            } catch (Exception e) {
                System.err.println(new Date() + ": exception " + e + " thrown processing captures");
                e.printStackTrace();
                out.write("warning: output may be incomplete, error occurred processing captures\n");
            }

            outf.close();
            out.flush();
        });
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("outbackcdx-urlkey", query.urlkey);
        return response;
    }

    interface OutputFormat {
        void writeCapture(Capture capture) throws IOException;
        void close() throws IOException;
    }

    static class JsonDictFormat implements OutputFormat {
        private final JsonWriter out;
        private final String[] fields;

        JsonDictFormat(Writer out, String[] fields) throws IOException {
            this.fields = fields;
            this.out = GSON.newJsonWriter(out);
            this.out.beginArray();
        }

        @Override
        public void writeCapture(Capture capture) throws IOException {
            out.beginObject();
            for (String field : fields) {
                Object value = capture.get(field);
                if (value instanceof Long) {
                    out.name(field).value((long) value);
                } else if (value instanceof Integer) {
                    out.name(field).value((int) value);
                } else if (value instanceof String) {
                    out.name(field).value((String) value);
                } else {
                    throw new UnsupportedOperationException("Don't know how to format: " + field + " (" + value.getClass() + ")");
                }
            }
            out.endObject();
        }

        @Override
        public void close() throws IOException {
            out.endArray();
            out.close();
        }
    }

    /**
     * Formats captures as a JSON array. Supports the field names used by both
     * pywb and wayback-cdx-server.
     */
    static class JsonFormat implements OutputFormat {
        private final JsonWriter out;
        private final String[] fields;

        JsonFormat(Writer out, String[] fields) throws IOException {
            this.fields = fields;
            this.out = GSON.newJsonWriter(out);
            this.out.beginArray();
        }

        @Override
        public void writeCapture(Capture capture) throws IOException {
            out.beginArray();
            for (String field : fields) {
                Object value = capture.get(field);
                if (value instanceof Long) {
                    out.value((long) value);
                } else if (value instanceof Integer) {
                    out.value((int) value);
                } else if (value instanceof String) {
                    out.value((String) value);
                } else {
                    throw new UnsupportedOperationException("Don't know how to format: " + field + " (" + value.getClass() + ")");
                }
            }
            out.endArray();
        }

        @Override
        public void close() throws IOException {
            out.endArray();
            out.close();
        }
    }

    static class TextFormat implements OutputFormat {
        private final Writer out;
        private final String[] fields;

        TextFormat(Writer out, String[] fields) {
            this.out = out;
            this.fields = fields;
        }

        @Override
        public void writeCapture(Capture capture) throws IOException {
            for (int i = 0; i < fields.length; i++) {
                String field = fields[i];
                Object value = capture.get(field);
                out.write(value.toString());
                if (i < fields.length - 1) {
                    out.write(' ');
                }
            }
            out.write('\n');
        }

        @Override
        public void close() {

        }
    }
}

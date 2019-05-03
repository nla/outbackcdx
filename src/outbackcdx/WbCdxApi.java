package outbackcdx;

import com.google.gson.stream.JsonWriter;
import outbackcdx.NanoHTTPD.Response;

import java.io.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static outbackcdx.Json.GSON;
import static outbackcdx.NanoHTTPD.Response.Status.OK;

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
    private static Query query;

    public static Response queryIndex(Web.Request request, Index index, Iterable<FilterPlugin> filterPlugins) {
        query = new Query(request.params());
        for (FilterPlugin filterPlugin : filterPlugins) {
            query.addPredicate(filterPlugin.newFilter(query));
        }
        Iterable<Capture> captures = query.execute(index);

        boolean outputJson = "json".equals(request.param("output"));
        // Check request headers for Accept: application/ors+cdxj and write CDXJ instead if requested.
        Response response = new Response(OK, outputJson ? "application/json" : "text/plain", outputStream -> {
            Writer out = new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8));
            OutputFormat outf = outputJson ? new JsonFormat(out, query.fields) : new TextFormat(out, query.fields);

            long row = 0;
            for (Capture capture : captures) {
                if (row >= query.limit) {
                    break;
                }
                outf.writeCapture(capture);
                row++;
            }

            outf.close();
            out.flush();
        });
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    public Query getQuery() {
        return query;
    }

    interface OutputFormat {
        void writeCapture(Capture capture) throws IOException;
        void close() throws IOException;
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

        TextFormat(Writer out, String[] fields) {
            this.out = out;
        }

        @Override
        public void writeCapture(Capture capture) throws IOException {
            out.write(capture.toString());
            out.write('\n');
        }

        @Override
        public void close() {

        }
    }
}

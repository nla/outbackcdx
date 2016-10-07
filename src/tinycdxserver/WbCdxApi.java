package tinycdxserver;

import com.grack.nanojson.JsonAppendableWriter;
import com.grack.nanojson.JsonWriter;
import tinycdxserver.NanoHTTPD.Response;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static tinycdxserver.NanoHTTPD.Response.Status.OK;

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
    public static Response query(NanoHTTPD.IHTTPSession session, Index index) {
        String url = session.getParms().get("url");
        String matchType = session.getParms().get("matchType");
        String limitParam = session.getParms().get("limit");
        String fl = session.getParms().get("fl");
        if (fl == null) {
            fl = "urlkey,timestamp,original,mimetype,statuscode,digest,length,offset,filename";
        }
        String[] fields = fl.split(",");
        long limit = limitParam == null ? Long.MAX_VALUE : Long.parseLong(limitParam);

        boolean outputJson = "json".equals(session.getParms().get("output"));
        Response response = new Response(OK, outputJson ? "application/json" : "text/plain", outputStream -> {
            Writer out = new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8));
            OutputFormat outf = outputJson ? new JsonFormat(out, fields) : new TextFormat(out, fields);

            long row = 0;
            for (Capture capture : queryForMatchType(index, matchType, url)) {
                if (row >= limit) {
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

    interface OutputFormat {
        void writeCapture(Capture capture) throws IOException;
        void close();
    }

    /**
     * Formats captures as a JSON array. Supports the field names used by both
     * pywb and wayback-cdx-server.
     */
    static class JsonFormat implements OutputFormat {
        private final JsonAppendableWriter out;
        private final String[] fields;

        JsonFormat(Writer out, String[] fields) {
            this.fields = fields;
            this.out = JsonWriter.on(out).array().array(Arrays.asList(fields));
        }

        @Override
        public void writeCapture(Capture capture) {
            out.array();
            for (String field : fields) {
                switch (field) {
                    case "urlkey":
                        out.value(capture.urlkey);
                        break;
                    case "timestamp":
                        out.value(capture.timestamp);
                        break;
                    case "url":
                    case "original":
                        out.value(capture.original);
                        break;
                    case "mime":
                    case "mimetype":
                        out.value(capture.mimetype);
                        break;
                    case "statuscode":
                    case "status":
                        out.value(capture.status);
                        break;
                    case "digest":
                        out.value(capture.digest);
                        break;
                    case "redirecturl":
                    case "redirect":
                        out.value(capture.redirecturl);
                        break;
                    case "offset":
                        out.value(capture.compressedoffset);
                        break;
                    case "filename":
                        out.value(capture.file);
                        break;
                }
            }
            out.end();
        }

        @Override
        public void close() {
            out.end().done();
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
        }

        @Override
        public void close() {

        }
    }


    private static Iterable<Capture> queryForMatchType(Index index, String matchType, String url) {
        String surt = UrlCanonicalizer.surtCanonicalize(url);
        if (matchType == null) {
            matchType = "exact";
        }
        switch (matchType) {
            case "exact":
                if (url.endsWith("*")) {
                    return queryForMatchType(index, "prefix", url.substring(0, url.length() - 1));
                } else if (url.startsWith("*.")) {
                    return queryForMatchType(index, "domain", url.substring(2));
                }
                return index.query(surt);
            case "prefix":
                if (url.endsWith("/") && !surt.endsWith("/")) {
                    surt += "/";
                }
                return index.prefixQuery(surt);
            case "host":
                return index.prefixQuery(hostFromSurt(surt) + ")/");
            case "domain":
                String host = hostFromSurt(surt);
                return index.rangeQuery(host, host + "-");
            default:
                throw new IllegalArgumentException("unknown matchType: " + matchType);
        }
    }

    /**
     * "org,example)/foo/bar" => "org,example"
     */
    static String hostFromSurt(String surtPrefix) {
        int i = surtPrefix.indexOf(")/");
        return i < 0 ? surtPrefix : surtPrefix.substring(0, i);
    }


}
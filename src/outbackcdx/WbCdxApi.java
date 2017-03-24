package outbackcdx;

import com.google.gson.stream.JsonWriter;
import outbackcdx.NanoHTTPD.Response;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

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
    public static Response query(NanoHTTPD.IHTTPSession session, Index index) {
        String accessPoint = session.getParms().get("accesspoint");
        String url = session.getParms().get("url");
        String matchType = session.getParms().getOrDefault("matchType", "exact");
        String limitParam = session.getParms().get("limit");
        String sort = session.getParms().get("sort");
        String fl = session.getParms().get("fl");
        String closest = session.getParms().get("closest");
        if (fl == null) {
            fl = "urlkey,timestamp,original,mimetype,statuscode,digest,length,offset,filename";
        }
        String[] fields = fl.split(",");
        long limit = limitParam == null ? Long.MAX_VALUE : Long.parseLong(limitParam);

        Iterable<Capture> captures;

        if ("closest".equals(sort)) {
            if (!"exact".equals(matchType)) {
                throw new IllegalArgumentException("sort=closest is currently only implemented for exact matches");
            }
            if (closest == null) {
                throw new IllegalArgumentException("closest={timestamp} is mandatory when using sort=closest");
            }
            captures = index.closestQuery(UrlCanonicalizer.surtCanonicalize(url), Long.parseLong(closest), accessPoint);
        } else if ("reverse".equals(sort)) {
            if (!"exact".equals(matchType)) {
                throw new IllegalArgumentException("sort=closest is currently only implemented for exact matches");
            }
            captures = index.reverseQuery(UrlCanonicalizer.surtCanonicalize(url), accessPoint);
        } else {
            captures = queryForMatchType(index, matchType, url, accessPoint);
        }

        boolean outputJson = "json".equals(session.getParms().get("output"));
        Response response = new Response(OK, outputJson ? "application/json" : "text/plain", outputStream -> {
            Writer out = new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8));
            OutputFormat outf = outputJson ? new JsonFormat(out, fields) : new TextFormat(out, fields);

            long row = 0;
            for (Capture capture : captures) {
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
                    case "length":
                        out.value(capture.length);
                        break;
                    case "offset":
                        out.value(capture.compressedoffset);
                        break;
                    case "filename":
                        out.value(capture.file);
                        break;
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


    private static Iterable<Capture> queryForMatchType(Index index, String matchType, String url, String accessPoint) {
        String surt = UrlCanonicalizer.surtCanonicalize(url);
        if (matchType == null) {
            matchType = "exact";
        }
        switch (matchType) {
            case "exact":
                if (url.endsWith("*")) {
                    return queryForMatchType(index, "prefix", url.substring(0, url.length() - 1), accessPoint);
                } else if (url.startsWith("*.")) {
                    return queryForMatchType(index, "domain", url.substring(2), accessPoint);
                }
                return index.query(surt, accessPoint);
            case "prefix":
                if (url.endsWith("/") && !surt.endsWith("/")) {
                    surt += "/";
                }
                return index.prefixQuery(surt, accessPoint);
            case "host":
                return index.prefixQuery(hostFromSurt(surt) + ")/", accessPoint);
            case "domain":
                String host = hostFromSurt(surt);
                return index.rangeQuery(host, host + "-", accessPoint);
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

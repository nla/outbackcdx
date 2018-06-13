package outbackcdx;

import com.google.gson.stream.JsonWriter;
import outbackcdx.NanoHTTPD.Response;

import java.io.*;
import java.util.Map;

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
        Query query = new Query(session.getParms());
        Iterable<Capture> captures = query.execute(index);

        boolean outputJson = "json".equals(session.getParms().get("output"));
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

    static class Query {
        String accessPoint;
        MatchType matchType;
        SortType sort;
        String url;
        String closest;
        String[] fields;
        boolean outputJson;
        long limit;

        Query(Map<String,String> params) {
            accessPoint = params.get("accesspoint");
            url = params.get("url");
            matchType = MatchType.valueOf(params.getOrDefault("matchType", "default").toUpperCase());
            sort = SortType.valueOf(params.getOrDefault("sort", "default").toUpperCase());
            closest = params.get("closest");

            String fl = params.getOrDefault("fl", "urlkey,timestamp,original,mimetype,statuscode,digest,length,offset,filename");
            fields = fl.split(",");

            String limitParam = params.get("limit");
            limit = limitParam == null ? Long.MAX_VALUE : Long.parseLong(limitParam);

            outputJson = "json".equals(params.get("output"));
        }

        void expandWildcards() {
            if (matchType == MatchType.DEFAULT) {
                if (url.endsWith("*")) {
                    matchType = MatchType.PREFIX;
                    url = url.substring(0, url.length() - 1);
                } else if (url.startsWith("*.")) {
                    matchType = MatchType.DOMAIN;
                    url = url.substring(2);
                } else {
                    matchType = MatchType.EXACT;
                }
            }
        }

        void validate() {
            if (sort == SortType.CLOSEST) {
                if (matchType != MatchType.EXACT) {
                    throw new IllegalArgumentException("sort=closest is currently only implemented for exact matches");
                }
                if (closest == null) {
                    throw new IllegalArgumentException("closest={timestamp} is mandatory when using sort=closest");
                }
            } else if (sort == SortType.REVERSE) {
                if (matchType != MatchType.EXACT) {
                    throw new IllegalArgumentException("sort=reverse is currently only implemented for exact matches");
                }
            }
        }

        Iterable<Capture> execute(Index index) {
            compatibilityHacks();
            expandWildcards();
            validate();

            String surt = UrlCanonicalizer.surtCanonicalize(url);

            switch (matchType) {
                case EXACT:
                    switch (sort) {
                        case DEFAULT:
                            return index.query(surt, accessPoint);
                        case CLOSEST:
                            return index.closestQuery(UrlCanonicalizer.surtCanonicalize(url), Long.parseLong(closest), accessPoint);
                        case REVERSE:
                            return index.reverseQuery(UrlCanonicalizer.surtCanonicalize(url), accessPoint);
                    }
                case PREFIX:
                    if (url.endsWith("/") && !surt.endsWith("/")) {
                        surt += "/";
                    }
                    return index.prefixQuery(surt, accessPoint);
                case HOST:
                    return index.prefixQuery(hostFromSurt(surt) + ")/", accessPoint);
                case DOMAIN:
                    String host = hostFromSurt(surt);
                    return index.rangeQuery(host, host + "-", accessPoint);
                case RANGE:
                    return index.rangeQuery(surt, "~", accessPoint);
                default:
                    throw new IllegalArgumentException("unknown matchType: " + matchType);
            }
        }

        private void compatibilityHacks() {
            /*
             * Cope pywb 2.0 sending nonsensical closest queries like ?url=foo&closest=&sort=closest.
             */
            if (sort == SortType.CLOSEST && (closest == null || closest.isEmpty())) {
                sort = SortType.DEFAULT;
            }
        }
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

    enum MatchType {
        DEFAULT, EXACT, PREFIX, HOST, DOMAIN, RANGE;
    }

    enum SortType {
        DEFAULT, CLOSEST, REVERSE
    }

    /**
     * "org,example)/foo/bar" => "org,example"
     */
    static String hostFromSurt(String surtPrefix) {
        int i = surtPrefix.indexOf(")/");
        return i < 0 ? surtPrefix : surtPrefix.substring(0, i);
    }


}

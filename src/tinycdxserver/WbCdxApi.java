package tinycdxserver;

import com.grack.nanojson.JsonStringWriter;
import com.grack.nanojson.JsonWriter;
import tinycdxserver.NanoHTTPD.Response;

import java.io.BufferedWriter;
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
        return new Response(OK, outputJson ? "application/json" : "text/plain", outputStream -> {
            Writer out = new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8));

            if (outputJson) {
                out.append('[');
                JsonWriter.on(out).array(Arrays.asList(fields)).done();
                out.append(",\n");
            }

            long row = 0;
            for (Capture capture : queryForMatchType(index, matchType, url)) {
                if (row >= limit) {
                    break;
                }
                if (outputJson) {
                    if (row > 0) {
                        out.append(",\n");
                    }
                    out.append(toJsonArray(capture, fields));
                } else {
                    out.append(capture.toString()).append('\n');
                }
                row++;
            }

            if (outputJson) {
                out.append("]\n");
            }

            out.flush();
        });
    }

    /**
     * Returns this capture a JSON string. Uses pywb's format.
     */
    public static String toJsonArray(Capture capture, String[] fields) {
        JsonStringWriter out = JsonWriter.string().array();
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
        return out.end().done();
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

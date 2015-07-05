package tinycdxserver;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * URL canonicalization rules.
 * <p/>
 * Based on the IA ones but we're a bit more aggressive on session ids based on other examples we've seen in the
 * wild.
 */
public class UrlCanonicalizer {
    private static final Pattern WWW_PREFIX = Pattern.compile("^www\\d*\\.");
    private static final Pattern PATH_SESSIONIDS[] = {
            Pattern.compile("/\\([0-9a-z]{24}\\)(/[^\\?]+.aspx)"),
            Pattern.compile(";jsessionid=[0-9a-z]{32}()$")
    };
    private static final Pattern QUERY_SESSIONID = Pattern.compile(
            "jsessionid=[0-9a-z]{10,}"
            + "|sessionid=[0-9a-z]{16,}"
            + "|phpsessid=[0-9a-z]{16,}"
            + "|sid=[0-9a-z]{16,}"
            + "|aspsessionid[a-z]{8}=[0-9a-z]{16,}");
    private static final Pattern CF_SESSIONID = Pattern.compile("(?:^|&)cfid=[0-9]+&cftoken=[0-9a-z-]+");
    private static final Pattern TABS_OR_LINEFEEDS = Pattern.compile("[\t\r\n]");
    private static final Pattern UNDOTTED_IP = Pattern.compile("(?:0x)?[0-9]{1,12}");
    private static final Pattern DOTTED_IP = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");

    private static URL makeUrl(String rawUrl) throws MalformedURLException {
        rawUrl = TABS_OR_LINEFEEDS.matcher(rawUrl).replaceAll("");
        if (!hasScheme(rawUrl)) {
            rawUrl = "http://" + rawUrl;
        }
        return new URL(rawUrl);
    }

    public static String toUnschemedSurt(String url) {
        try {
            return toUnschemedSurt(makeUrl(url));
        } catch (MalformedURLException e) {
            return url;
        }
    }

    public static String toUnschemedSurt(URL url) {
        StringBuilder result = new StringBuilder();
        String host = url.getHost();
        if (!DOTTED_IP.matcher(host).matches()) {
            List<String> segments = Arrays.asList(host.split("\\."));
            Collections.reverse(segments);
            host = String.join(",", segments) + ")";
        }
        result.append(host);
        if (url.getPort() != -1) {
            result.append(':').append(Integer.toString(url.getPort()));
        }
        result.append(url.getPath());
        if (url.getQuery() != null) {
            result.append('?').append(url.getQuery());
        }
        return result.toString();
    }

    public static URL canonicalize(URL url) throws MalformedURLException {
        String scheme = canonicalizeScheme(url.getProtocol());
        String host = canonicalizeHost(url.getHost());
        int port = canonicalizePort(scheme, url.getPort());
        String path = canonicalizePath(url.getPath());
        String query = canonicalizeQuery(url.getQuery());
        String pathAndQuery = query == null ? path : path + "?" + query;
        return new URL(scheme, host, port, pathAndQuery);
    }

    public static String canonicalize(String rawUrl) {
        try {
            return canonicalize(makeUrl(rawUrl)).toString();
        } catch (MalformedURLException e) {
            return rawUrl;
        }
    }

    public static String surtCanonicalize(String url) {
        return toUnschemedSurt(canonicalize(url));
    }

    private static boolean hasScheme(String url) {
        int colon = url.indexOf(":");
        int slash = url.indexOf("/");
        return colon != -1 && (colon < slash || slash == -1);
    }

    private static int canonicalizePort(String scheme, int port) {
        if (port == 80 && "http".equals(scheme)) {
            return -1;
        } else if (port == 443 && "https".equals(scheme)) {
            return -1;
        } else {
            return port;
        }
    }

    private static String canonicalizeScheme(String scheme) {
        if (scheme != null) {
            scheme = scheme.toLowerCase();
        }
        return scheme;
    }

    private static String canonicalizeQuery(String query) {
        if (query != null) {
            query = query.toLowerCase();
            String fields[] = query.split("&");
            Arrays.sort(fields);
            ArrayList<String> filtered = new ArrayList<String>();
            for (String field : fields) {
                if (!QUERY_SESSIONID.matcher(field).matches() && !field.equals("")) {
                    filtered.add(field);
                }
            }
            query = String.join("&", filtered);
            query = canonicalizeUrlEncoding(query);
            query = CF_SESSIONID.matcher(query).replaceFirst("");
            if (query.equals("")) {
                query = null;
            }
        }
        return query;
    }

    private static String canonicalizePath(String path) {
        if (path != null) {
            path = path.toLowerCase();
            path = canonicalizeUrlEncoding(path);
            path = canonicalizePathSegments(path);

            for (Pattern PATH_SESSIONID : PATH_SESSIONIDS) {
                path = PATH_SESSIONID.matcher(path).replaceFirst("$1");
            }
        }
        return path;
    }

    private static String canonicalizeHost(String host) {
        host = host.replace("..", ".");
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        host = host.replaceFirst("\\.$", "");
        host = IDN.toASCII(host);
        host = host.toLowerCase();
        host = canonicalizeUrlEncoding(host);
        host = canonicalizeIP(host);
        host = WWW_PREFIX.matcher(host).replaceFirst("");
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }

    private static String canonicalizePathSegments(String path) {
        ArrayList<String> out = new ArrayList<String>();
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
            } else if (!segment.equals("") && !segment.equals(".")) {
                out.add(segment);
            }
        }
        return "/" + String.join("/", out);
    }

    private static String canonicalizeIP(String host) {
        if (UNDOTTED_IP.matcher(host).matches()) {
            try {
                long x = Long.decode(host);
                if (x >= 0 && x < (1L << 32)) {
                    return String.format("%d.%d.%d.%d", (x >> 24) & 0xff, (x >> 16) & 0xff, (x >> 8) & 0xff, x & 0xff);
                }
            } catch (NumberFormatException e) {
                // not valid
            }
        }
        InetAddress ip = InetAddresses.forUriString(host);
        if (ip != null) {
            return InetAddresses.toUriString(ip);
        }
        return host; // not an IP
    }

    private static String canonicalizeUrlEncoding(String s) {
        return urlEncodeIllegals(fullyUrlDecode(s));
    }

    private static String fullyUrlDecode(String s) {
        String prev;
        do {
            prev = s;
            s = urlDecode(s);
        } while (!s.equals(prev));
        return prev;
    }

    private static String urlEncodeIllegals(String s) {
        StringBuilder out = new StringBuilder();
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte rawByte : bytes) {
            int b = rawByte & 0xff;
            if (b == '%' || b == '#' || b <= 0x20 || b >= 0x7f) {
                out.append('%').append(String.format("%02x", b));
            } else {
                out.append((char) b);
            }
        }
        return out.toString();
    }

    static String urlDecode(String s) {
        ByteBuffer bb = null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '%') {
                if (bb == null) {
                    bb = ByteBuffer.allocate((s.length() - i) / 3);
                }
                while (i + 2 < s.length() && s.charAt(i) == '%') {
                    int d1 = Character.digit(s.charAt(i + 1), 16);
                    int d2 = Character.digit(s.charAt(i + 2), 16);
                    if (d1 < 0 || d2 < 0) break;
                    bb.put((byte) (d1 << 4 | d2));
                    i += 3;
                }
                bb.flip();
                tryDecodeUtf8(bb, out);
                bb.clear();
                if (i < s.length()) {
                    out.append(s.charAt(i));
                }
            } else {
                out.append(s.charAt(i));
            }

        }
        return out.toString();
    }

    private static void tryDecodeUtf8(ByteBuffer bb, StringBuilder out) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        CharBuffer cb = CharBuffer.allocate(bb.remaining());
        while (bb.hasRemaining()) {
            CoderResult result = decoder.decode(bb, cb, true);
            if (result.isMalformed()) {
                for (int i = 0; i < result.length(); i++) {
                    out.append('%').append(String.format("%02x", bb.get()));
                }
            }
            out.append(cb.flip());
            cb.clear();
        }
    }
}

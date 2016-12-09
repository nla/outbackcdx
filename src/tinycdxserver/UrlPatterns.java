package tinycdxserver;

import org.netpreserve.urlcanon.Canonicalizer;
import org.netpreserve.urlcanon.ParsedUrl;

public class UrlPatterns {

    private static void reverseDomain(String host, StringBuilder out) {
        int i = host.lastIndexOf('.');
        int j = host.length();
        while (i != -1) {
            out.append(host, i + 1, j);
            out.append(',');
            j = i;
            i = host.lastIndexOf('.', i - 1);
        }
        out.append(host, 0, j);
        out.append(',');
    }

    /**
     * Converts an exact URL, a URL containing pywb-style "*" wildcards to a SSURT prefix.
     * SSURTs are passed through unaltered. Exact matches are suffixed with a space.
     */
    public static String toSsurtPrefix(String pattern) {
        if (pattern.startsWith("*.")) {
            if (pattern.contains("/")) {
                throw new IllegalArgumentException("can't use a domain wildcard with a path");
            }
            StringBuilder out = new StringBuilder();
            reverseDomain(pattern.substring(2), out);
            return out.toString().toLowerCase();
        } else if (pattern.endsWith("*")) {
            ParsedUrl url = ParsedUrl.parseUrl(pattern.substring(0, pattern.length() - 1));
            Canonicalizer.WHATWG.canonicalize(url);
            return url.ssurt().toString();
        } else {
            ParsedUrl url = ParsedUrl.parseUrl(pattern.substring(0, pattern.length()));
            Canonicalizer.WHATWG.canonicalize(url);
            return url.ssurt().toString() + " ";
        }
    }

}

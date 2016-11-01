package tinycdxserver;

import io.mola.galimatias.*;

/**
 * Superior SURT. Transforms scheme://userinfo@domain.tld:port/path?query#fragment into
 * (tld,domain,):port:scheme:userinfo/path?query#fragment.
 *
 * Note: port and scheme are mandatory, userinfo is allowed to be blank but colon in front of it must be present.
 * There is no such thing as a relative SSURT.
 *
 * Resolves the following issues with Heritrix SURTs:
 *
 * 1. Prefix based rules are more powerful and can match any of:
 *    domain, domain+port, domain+port+scheme, domain+port+scheme+userinfo, domain+port+scheme+userinfo+path.
 * 2. Correct parsing of a SURT is awkward due to ")" being allowed in userinfo but also used to delimit it.
 *    So SSURT instead treats () as part of the host, similar to the way [] is used by IPv6 addresses.
 * 3. 'Practically' reversible. This should be true: WHATWG(unSSURT(SSURT(url))) = WHATWG(url) where WHATWG() is the
 *    canoncalisation browsers do by deserializing and reserializing per the WHATWG URL spec.
 *
 * Example SSURT prefixes:
 *
 * (au,gov,nla,            => *.nla.gov.au
 * (au,gov,nla,):          => everything on host 'nla.gov.au' regardless of scheme and port.
 * (au,gov,nla,):80:       => everything on port 80 on host 'nla.gov.au'
 * (au,gov,nla,):80:http:  => http on port 80 on host 'nla.gov.au' any userinfo
 * (au,gov,nla,):80:http:/ => http on port 80 on host 'nla.gov.au' blank userinfo
 * 10.                     => everything in ipv4 subnet 10.0.0.0/8
 * [2001:0db8:             => everything in ipv6 subnet 2001:0db8:
 *
 * Indicative grammar (grammars alone are not powerful enough to describe URL parsing):
 *
 * SSURT = sshost ":" port ":" scheme ":" [ userinfo ] "/" path [ "?" query ] [ "#" fragment ]
 * sshost = "(" revdomain ",)" / IPv4address / "[" IPv6address "]"
 *
 * Note: Canonicalisation should be per https://url.spec.whatwg.org/ We don't lowercase the path or query, drop "www."
 * or reorder the query pairs. For many use cases it will be be useful to layer on more aggressive rules (wider
 * lowercasing, exhaustive percent decoding etc) but SSURT by itself does not imply them.
 *
 * Open questions: should we pad IPv6 addresses with leading zeros and expand zero compress (::)?
 * Should we convert IPv4 addresses to mapped IPv6? (or vice versa)
 *
 */
public class SSURT {

    public static String fromUrl(String input) {
        try {
            // note: this is the galimatias URL parser not java.net.URL
            URL url = URL.parse(input);
            return fromUrl(url.host(), url.port(),
                    url.scheme(), url.userInfo(), url.path(),
                    url.query(), null);
        } catch (GalimatiasParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String fromUrl(Host host, int port, String scheme, String userInfo, String path, String query,
                                 String fragment) {
        StringBuilder out = new StringBuilder();
        if (host instanceof Domain) {
            out.append('(');
            reverseDomain(host.toString(), out);
            out.append(')');
        } else if (host instanceof IPv4Address) {
            out.append(host);
        } else {
            out.append('[').append(host.toString()).append(']');
        }
        out.append(':').append(port);
        out.append(':').append(scheme);
        out.append(':').append(userInfo == null ? "" : userInfo);
        out.append(path);
        if (query != null) {
            out.append('?').append(query);
        }
        if (fragment != null) {
            out.append('#').append(fragment);
        }
        return out.toString();
    }

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
    public static String prefixFromPattern(String pattern) {
        // FIXME: distinguish between SSURT and SURT.
        // We should accept SURTs but convert them to SSURTs.
        if (isAlreadySsurt(pattern)) {
            return pattern;
        } else if (pattern.startsWith("*.")) {
            if (pattern.contains("/")) {
                throw new IllegalArgumentException("can't use a domain wildcard with a path");
            }
            StringBuilder out = new StringBuilder("(");
            reverseDomain(pattern.substring(2), out);
            return out.toString();
        } else if (pattern.endsWith("*")) {
            return fromUrl(pattern.substring(0, pattern.length() - 1));
        } else {
            return fromUrl(pattern) + " ";
        }
    }

    private static boolean isAlreadySsurt(String s) {
        if (s.isEmpty()) {
            return true;
        }
        char c = s.charAt(0);
        return c == '(' || c == '[' || '0' <= c && c <= '9';
    }
}

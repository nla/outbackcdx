package outbackcdx;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * URL canonicalization rules.
 * <p/>
 * Based on the IA ones but we're a bit more aggressive on session ids based on other examples we've seen in the
 * wild.
 */
public class UrlCanonicalizer {
    static final Pattern WWW_PREFIX = Pattern.compile("^www\\d*\\.");
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
    static final Pattern DOTTED_IP = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");

    /**
     * This class implements pywb fuzzy matching as canonicalization rules.
     *
     * <p>
     * Urls are sometimes created dynamically and include extraneous parameters that
     * have no meaningful effect on the server response. Fuzzy matching helps to
     * play back non-identical but equivalent urls, by ignoring the parts of the url
     * that don't matter. The way it works in pywb, in a nutshell, is that it
     * queries the cdx index for a surt prefix, and filters for urls that contain
     * the meaningful substrings of the requested url. The "meaningful substrings"
     * are the captured groups from the regular expression match. See
     * {@link https://github.com/webrecorder/pywb/wiki/Fuzzy-Match-Rules} for more
     * details.
     * 
     * <p>
     * What we do here with the outbackcdx fuzzy canonicalization rules is to
     * explicitly include only the meaningful parts of the url in the canonicalized
     * version. Where pywb would do a scan over urls matching the prefix, grepping
     * for the meaningful substrings, we instead canonicalize directly to something
     * like <code>fuzzy:com,example)/url/prefix?meaningful&substring</code>.
     * 
     * <p>
     * The advantage of this approach is performance. An O(n) prefix scan in pywb
     * becomes an O(1) exact match. The downside is that urls in the index need to
     * be recanonicalized when the fuzzy match rules change (TODO add some
     * instructions). But the difference in performance is critical when you have
     * millions of captures matching a prefix.
     * 
     * <p>
     * {@link UrlCanonicalizer} can read a pywb rules.yaml file to configure the
     * fuzzy canonicalization rules. The rules.yaml can be copied verbatim from
     * pywb, modulo one change documented on
     * {@link UrlCanonicalizer#UrlCanonicalizer(String)}. Settings irrelevant to
     * fuzzy matching are ignored.
     * 
     * <p>
     * Here are some example rules. TODO explain further all the supported
     * configuration parameters.
     * 
     * <pre>
     * rules:
     * - url_prefix: 'com,twitter)/i/profiles/show/'
     *   fuzzy_lookup: '/profiles/show/.*with_replies\?.*(max_id=[^&]+)'
     * - url_prefix: 'com,twitter)/i/timeline'
     *   fuzzy_lookup:
     *   - max_position
     *   - include_entities
     * - url_prefix: 'com,facebook)/ajax/pagelet/generic.php/photoviewerpagelet'
     *   fuzzy_lookup:
     *     match: '("(?:cursor|cursorindex)":["\d\w]+)'
     *     find_all: true
     * - url_prefix: 'com,staticflickr,'
     *   fuzzy_lookup:
     *     match: '([0-9]+_[a-z0-9]+).*?.jpg'
     *     replace: '/'
     *     # replace: 'staticflickr,'
     * - url_prefix: ['com,yimg,l)/g/combo', 'com,yimg,s)/pw/combo', 'com,yahooapis,yui)/combo']
     *   fuzzy_lookup: '([^/]+(?:\.css|\.js))'
     * - url_prefix: 'com,vimeo,av)/'
     *   # only use non query part of url, ignore query
     *   fuzzy_lookup: '()'
     * - url_prefix: 'com,googlevideo,'
     *   fuzzy_lookup:
     *     match:
     *       regex: 'com,googlevideo.*&#47;videoplayback.*'
     *       args:
     *       - id
     *       - itag
     *       #- mime
     *     filter:
     *     - 'urlkey:{0}'
     *     - '!mimetype:text/plain'
     *     type: 'domain'
     * - url_prefix: com,example,zuh)/
     *   fuzzy_lookup: '[&?](?:.*)'
     * </pre>
     *
     * @author nlevitt
     * @see {@link https://github.com/webrecorder/pywb/wiki/Fuzzy-Match-Rules}
     * @see {@link https://github.com/webrecorder/pywb/blob/5f938e68797/pywb/warcserver/index/fuzzymatcher.py}
     */
    public static class FuzzyRule {
        final List<String> urlPrefixes;
        final Pattern pattern;
        final String replaceAfter;
        final boolean findAll;
        final boolean isDomain;

        private FuzzyRule(List<String> urlPrefixes, String regex, String replaceAfter, boolean findAll, boolean isDomain) {
            this.urlPrefixes = urlPrefixes;
            this.pattern = Pattern.compile(regex);
            this.replaceAfter = replaceAfter;
            this.findAll = findAll;
            this.isDomain = isDomain;
        }

        @Override
        public String toString() {
            return "<urlPrefixes=" + urlPrefixes + ",pattern="+ pattern + "...>";
        }

        @SuppressWarnings("unchecked")
        public static FuzzyRule from(Map<String, Object> item) {
            // ignore stuff we don't recognize, for compatibility with
            // irrelevant or future pywb settings
            if (!item.containsKey("url_prefix") || !item.containsKey("fuzzy_lookup")) {
                return null;
            }

            List<String> urlPrefixes;
            if (item.get("url_prefix") instanceof String) {
                urlPrefixes = Arrays.asList((String) item.get("url_prefix"));
            } else {
                urlPrefixes = (List<String>) item.get("url_prefix");
            }

            String regex = null;
            String replaceAfter = "?";
            boolean findAll = false;
            boolean isDomain = false;

            if (item.get("fuzzy_lookup") instanceof Map) {
                Map<String, Object> fuzzyLookup = (Map<String,Object>) item.get("fuzzy_lookup");
                regex = makeRegex(fuzzyLookup.get("match"));
                replaceAfter = (String) fuzzyLookup.getOrDefault("replace", "?");
                findAll = (boolean) fuzzyLookup.getOrDefault("find_all", false);
                if ("domain".equals(fuzzyLookup.get("type"))) {
                    isDomain = true;
                }
            } else {
                regex = makeRegex(item.get("fuzzy_lookup"));
            }

            return new FuzzyRule(urlPrefixes, regex, replaceAfter, findAll, isDomain);
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private static String makeRegex(Object config) {
            if (config instanceof String) {
                return (String) config;
            } else if (config instanceof Map) {
                String regex = (String) ((Map) config).getOrDefault("regex", "");
                regex += makeQueryMatchRegex((List<String>) ((Map) config).getOrDefault("args", Collections.EMPTY_LIST));
                return regex;
            } else {
                return makeQueryMatchRegex((List<String>) config);
            }
        }

        private static String makeQueryMatchRegex(List<String> paramsList) {
            List<String> tmpList = new ArrayList<String>(paramsList);
            tmpList.sort(null);
            for (int i = 0; i < tmpList.size(); i++) {
                String escaped = Pattern.quote(tmpList.get(i));
                String reSnip = "[?&](" + escaped + "=[^&]+)";
                tmpList.set(i, reSnip);
            }
            String result = String.join(".*", tmpList);
            return result;
        }

        public String apply(String surt) {
            for (String prefix: urlPrefixes) {
                if (surt.startsWith(prefix)) {
                    Matcher m = pattern.matcher(surt);
                    List<String> groups = new ArrayList<String>();
                    boolean regexMatches = false;
                    if (findAll) {
                        while (m.find()) {
                            regexMatches = true;
                            groups.add(m.group());
                        }
                    } else {
                        if (m.find()) {
                            regexMatches = true;
                            for (int i = 1; i <= m.groupCount(); i++) {
                                if (m.group(i) != null) {
                                    groups.add(m.group(i));
                                }
                            }
                        }
                    }

                    if (regexMatches) {
                        int replaceAfterIndex = surt.indexOf(replaceAfter);
                        String pref;
                        if (isDomain) {
                            pref = prefix + '?';
                        } else if (replaceAfterIndex >= 0) {
                            pref = surt.substring(0, surt.indexOf(replaceAfter) + replaceAfter.length());
                        } else {
                            pref = surt + '?';
                        }
                        String newSurt = "fuzzy:" + pref + String.join("&", groups);
                        return newSurt;
                    }
                }
            }

            return null;
        }
    }
    List<FuzzyRule> fuzzyRules = new ArrayList<FuzzyRule>();

    public static class ConfigurationException extends Exception {
        private static final long serialVersionUID = 1L;
        public ConfigurationException(String msg) {
            super(msg);
        }
    }

    /**
     * Reads fuzzy canonicalization rules from <code>fuzzyYamlFile</code>, if
     * provided. <code>fuzzyYamlFile</code> should be a pywb rules.yaml file.
     * Unfortunately there is one change you'll want to make from the pywb
     * rules.yaml: remove this last rule, which would force every url to be
     * pointlessly fuzzy-canonicalized:
     * 
     * <pre>
     *     # all domain rules -- fallback to this dataset
     *     #=================================================================
     *     # Applies to all urls -- should be last
     *     - url_prefix: ''
     *       fuzzy_lookup:
     *         match: '()'
     * </pre>
     * 
     * @param fuzzyYamlFile pywb rules.yaml file
     */
    public UrlCanonicalizer(String fuzzyYamlFile) throws FileNotFoundException, IOException, ConfigurationException {
        if (fuzzyYamlFile != null) {
            try (FileInputStream input = new FileInputStream(fuzzyYamlFile)) {
                loadRules(input);
            }
        }
    }

    /**
     * @param input pywb rules.yaml input stream
     * @see #UrlCanonicalizer(String)
     */
    public UrlCanonicalizer(InputStream input) throws ConfigurationException {
        loadRules(input);
    }

    private void loadRules(InputStream input) throws ConfigurationException {
        LoadSettings yamlSettings = LoadSettings.builder().build();
        Load yamlLoader = new Load(yamlSettings);

        @SuppressWarnings("unchecked")
        Map<String,Object> rulesYaml = (Map<String, Object>) yamlLoader.loadFromInputStream(input);

        @SuppressWarnings("unchecked")
        Iterable<Map<String,Object>> ruleConfigs = (Iterable<Map<String,Object>>) rulesYaml.get("rules");

        for (Map<String, Object> config: ruleConfigs) {
            FuzzyRule rule = FuzzyRule.from(config);
            if (rule != null) {
                fuzzyRules.add(rule);
            }
        }
    }

    public UrlCanonicalizer() {
    }

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

    private static boolean shouldStripQueryString(URL url) {
        /*
         * This is a hack for compatibility with the NLA's legacy PANDORA web archive. URLs were link rewritten
         * to unique filenames but the query string needs to be ignored.
         *
         * For example: "style.php?ver=1.2" was rewritten to "style62ea.css" but the HTML links to
         * "style62ea.css?ver=1.2".
         */
        return FeatureFlags.pandoraHacks()
                && url.getHost().equalsIgnoreCase("pandora.nla.gov.au")
                && url.getPath().startsWith("/pan/");
    }

    public static URL canonicalize(URL url) throws MalformedURLException {
        String scheme = canonicalizeScheme(url.getProtocol());
        String host = canonicalizeHost(url.getHost());
        int port = canonicalizePort(scheme, url.getPort());
        String path = canonicalizePath(url.getPath());
        String query = canonicalizeQuery(url.getQuery());
        String pathAndQuery = query == null || shouldStripQueryString(url) ? path : path + "?" + query;
        return new URL(scheme, host, port, pathAndQuery);
    }

    public static String canonicalize(String rawUrl) {
        try {
            return canonicalize(makeUrl(rawUrl)).toString();
        } catch (MalformedURLException e) {
            return rawUrl;
        }
    }

    protected static Pattern SPECIAL_URL_REGEX =
            Pattern.compile("^([^/]+):(https?://.*)", Pattern.CASE_INSENSITIVE);

    /**
     * Canonicalizes <code>url</code> and returns in SURT form, for use as a key in
     * the CDX index.
     *
     * <p>
     * Handles urls that look like <code>{some-scheme}:{http(s)-url}</code>
     * specially, e.g.
     *
     * <ul>
     * <li><code>youtube-dl:http://example.com/</code>
     * <li><code>youtube-dl:00001:http://example.com/</code>
     * <li><code>urn:transclusions:http://example.com/</code>
     * <li><code>screenshot:https://example.com/</code>
     * </ul>
     *
     * <p>
     * We split the scheme off, canonicalize and surtify the http(s) url, then stick
     * the scheme back on. For example, <code>youtube-dl:http://example.com/</code>
     * becomes <code>youtube-dl:com,example)/</code>.
     *
     * <p>
     * As an extra-special case, we change the scheme
     * <code>urn:transclusions</code> to <code>youtube-dl</code>, since by
     * convention, both schemes indicate youtube-dl json.
     */
    public String surtCanonicalize(String url) {
        Matcher m = SPECIAL_URL_REGEX.matcher(url);
        String surt;
        if (m.matches()) {
            String scheme = canonicalizeScheme(m.group(1));
            if ("urn:transclusions".equals(scheme)) {
                scheme = "youtube-dl";
            }
            surt = scheme + ":" + toUnschemedSurt(canonicalize(m.group(2)));
        } else {
            surt = toUnschemedSurt(canonicalize(url));
        }

        for (FuzzyRule rule: fuzzyRules) {
            String fuzz = rule.apply(surt);
            if (fuzz != null) {
                surt = fuzz;
                break;
            }
        }

        return surt;
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

    static String canonicalizeQuery(String query) {
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
        try {
            host = IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            // XXX: IDN.toASCII throws in the face of very long domain segments
            // let's just try to continue
        }
        host = host.toLowerCase();
        host = canonicalizeUrlEncoding(host);
        host = canonicalizeIP(host);
        host = WWW_PREFIX.matcher(host).replaceFirst("");
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }

    static String canonicalizePathSegments(String path) {
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

    static String canonicalizeUrlEncoding(String s) {
        if (s == null) {
            return null;
        }
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

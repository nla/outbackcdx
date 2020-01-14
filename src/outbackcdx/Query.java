package outbackcdx;

import java.util.function.Predicate;

public class Query {
    private static final String DEFAULT_FIELDS = "urlkey,timestamp,original,mimetype,statuscode,digest,redirecturl,robotflags,length,offset,filename";
    private static final String DEFAULT_FIELDS_CDX14 = DEFAULT_FIELDS + ",originalLength,originalOffset,originalFilename";

    public static final long MIN_TIMESTAMP = 0l;
    public static final long MAX_TIMESTAMP = 99999999999999l;

    String accessPoint;
    MatchType matchType;
    Sort sort;
    String url;
    String urlkey;
    String closest;
    String[] fields;
    boolean outputJson;
    long limit;
    Predicate<Capture> predicate;
    long from = MIN_TIMESTAMP;
    long to = MAX_TIMESTAMP;
    String collapseToLastSpec;

    public Query(MultiMap<String, String> params, Iterable<FilterPlugin> filterPlugins) {
        accessPoint = params.get("accesspoint");
        url = params.get("url");
        urlkey = params.get("urlkey");
        matchType = MatchType.valueOf(params.getOrDefault("matchType", "default").toUpperCase());
        sort = Sort.valueOf(params.getOrDefault("sort", "default").toUpperCase());
        closest = params.get("closest");
        if (params.containsKey("from")) {
            from = timestamp14Long(params.get("from"), '0');
        }
        if (params.containsKey("to")) {
            to = timestamp14Long(params.get("to"), '9');
        }

        predicate = capture -> true;
        if (params.getAll("filter") != null) {
            for (String filterSpec: params.getAll("filter")) {
                Filter filter = Filter.fromSpec(filterSpec);
                addPredicate(filter);
            }
        }

        if (filterPlugins != null) {
            for (FilterPlugin filterPlugin : filterPlugins) {
                addPredicate(filterPlugin.newFilter(params));
            }
        }

        // collapse / collapseToFirst has to be the last filter applied
        String collapseToFirstSpec = params.getOrDefault("collapseToFirst", params.get("collapse"));
        if (collapseToFirstSpec != null) {
            Filter filter = Filter.collapseToFirst(collapseToFirstSpec);
            addPredicate(filter);
        } else if (params.containsKey("collapseToLast")) {
            // collapseToLast can't be implemented as a predicate 
            collapseToLastSpec = params.get("collapseToLast");
        }

        String fl = params.getOrDefault("fl", FeatureFlags.cdx14() ? DEFAULT_FIELDS_CDX14 : DEFAULT_FIELDS);
        fields = fl.split(",");

        String limitParam = params.get("limit");
        limit = limitParam == null ? Long.MAX_VALUE : Long.parseLong(limitParam);

        outputJson = "json".equals(params.get("output"));
    }

    /**
     * Pads timestamp with {@code padDigit} if shorter than 14 digits, or truncates
     * to 14 digits if longer than 14 digits, and converts to long.
     *
     * For example:
     * <ul>
     * <li>"2019" -> 20190000000000l
     * <li>"20190128123456789" -> 20190128123456l
     * </ul>
     *
     * @throws NumberFormatException if the string does not contain a parsable long.
     */
    protected long timestamp14Long(String timestamp, char padDigit) {
        StringBuilder buf = new StringBuilder(timestamp);
        while (buf.length() < 14) {
            buf.append(padDigit);
        }
        buf.setLength(14);
        long result = Long.parseLong(buf.toString());
        return result;
    }

    public String getAccessPoint() {
        return accessPoint;
    }

    public void addPredicate(Predicate<Capture> predicate) {
        this.predicate = this.predicate.and(predicate);
    }

    void expandWildcards() {
        if (matchType == MatchType.DEFAULT) {
            if (url != null && url.endsWith("*")) {
                matchType = MatchType.PREFIX;
                url = url.substring(0, url.length() - 1);
            } else if (url != null && url.startsWith("*.")) {
                matchType = MatchType.DOMAIN;
                url = url.substring(2);
            } else {
                matchType = MatchType.EXACT;
            }
        }
    }

    void validate() {
        if ((url == null && urlkey == null) || (url != null && urlkey != null)) {
            throw new IllegalArgumentException("exactly one of 'url' or 'urlkey' is required");
        }
        if (sort == Sort.CLOSEST) {
            if (matchType != MatchType.EXACT) {
                throw new IllegalArgumentException("sort=closest is currently only implemented for exact matches");
            }
            if (closest == null) {
                throw new IllegalArgumentException("closest={timestamp} is mandatory when using sort=closest");
            }
        } else if (sort == Sort.REVERSE) {
            if (matchType != MatchType.EXACT) {
                throw new IllegalArgumentException("sort=reverse is currently only implemented for exact matches");
            }
        } else if (from != MIN_TIMESTAMP || to != MAX_TIMESTAMP) {
            if (matchType != MatchType.EXACT) {
                throw new IllegalArgumentException("from={timestamp} and to={timestamp} are currently only implemented for exact matches");
            }
            if (sort == Sort.CLOSEST) {
                throw new IllegalArgumentException("from={timestamp} and to={timestamp} are currently not implemented for sort=closest queries");
            }
        }
    }

    Iterable<Capture> execute(Index index) {
        compatibilityHacks();
        expandWildcards();
        validate();

        if (urlkey == null) {
            urlkey = index.canonicalizer.surtCanonicalize(url);
        }

        Iterable<Capture> captures = index.execute(this);
        if (collapseToLastSpec != null) {
            captures = Filter.collapseToLast(captures, collapseToLastSpec);
        }
        return captures;
    }

    private void compatibilityHacks() {
        /*
         * Cope pywb 2.0 sending nonsensical closest queries like ?url=foo&closest=&sort=closest.
         */
        if (sort == Sort.CLOSEST && (closest == null || closest.isEmpty())) {
            sort = Sort.DEFAULT;
        }
    }

    enum MatchType {
        DEFAULT, EXACT, PREFIX, HOST, DOMAIN, RANGE;
    }

    enum Sort {
        DEFAULT, CLOSEST, REVERSE
    }
}

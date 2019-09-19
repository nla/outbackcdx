package outbackcdx;

import java.util.Map;
import java.util.function.Predicate;

public class Query {
    private static final String DEFAULT_FIELDS = "urlkey,timestamp,original,mimetype,statuscode,digest,length,redirecturl,robotflags,offset,filename";
    private static final String DEFAULT_FIELDS_CDX14 = DEFAULT_FIELDS + ",originalLength,originalOffset,originalFilename";
    String accessPoint;
    MatchType matchType;
    Sort sort;
    String url;
    String closest;
    String[] fields;
    boolean outputJson;
    long limit;
    Predicate<Capture> filter;

    public Query(Map<String,String> params) {
        accessPoint = params.get("accesspoint");
        url = params.get("url");
        matchType = MatchType.valueOf(params.getOrDefault("matchType", "default").toUpperCase());
        sort = Sort.valueOf(params.getOrDefault("sort", "default").toUpperCase());
        closest = params.get("closest");
        filter = params.containsKey("filter") ? new RegexFilter(params.get("filter")) : (capture -> true);

        String fl = params.getOrDefault("fl", FeatureFlags.cdx14() ? DEFAULT_FIELDS_CDX14 : DEFAULT_FIELDS);
        fields = fl.split(",");

        String limitParam = params.get("limit");
        limit = limitParam == null ? Long.MAX_VALUE : Long.parseLong(limitParam);

        outputJson = "json".equals(params.get("output"));
    }

    public String getAccessPoint() {
        return accessPoint;
    }

    public void addPredicate(Predicate<Capture> predicate) {
        filter = filter.and(predicate);
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
        }
    }

    Iterable<Capture> execute(Index index) {
        compatibilityHacks();
        expandWildcards();
        validate();
        return index.execute(this);
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

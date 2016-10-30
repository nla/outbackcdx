package tinycdxserver;

import java.util.HashMap;
import java.util.Map;

/**
 * Experimental features that can be turned on with a flag. This is so they can
 * be merged into master (thus avoiding the code diverging) before they are
 * mature.
 */
public class FeatureFlags {
    public static boolean experimentalAccessControl() {
        return "1".equals(System.getenv("EXPERIMENTAL_ACCESS_CONTROL"));
    }

    public static Map<String, Boolean> asMap() {
        Map<String,Boolean> map = new HashMap<>();
        map.put("experimentalAccessControl", experimentalAccessControl());
        return map;
    }
}

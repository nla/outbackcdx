package outbackcdx;

import java.util.HashMap;
import java.util.Map;

/**
 * Experimental features that can be turned on with a flag. This is so they can
 * be merged into master (thus avoiding the code diverging) before they are
 * mature.
 */
public class FeatureFlags {
    private static boolean experimentalAccessControl;
    private static boolean pandoraHacks;
    private static boolean secondaryMode;
    private static boolean filterPlugins;

    static {
        experimentalAccessControl = "1".equals(System.getenv("EXPERIMENTAL_ACCESS_CONTROL"));
        pandoraHacks = "1".equals(System.getenv("PANDORA_HACKS"));
        filterPlugins = "1".equals(System.getenv("FILTER_PLUGINS"));
    }

    public static boolean pandoraHacks() {
        return pandoraHacks;
    }

    public static boolean experimentalAccessControl() {
        return experimentalAccessControl;
    }

    public static boolean filterPlugins() {
        return filterPlugins;
    }

    public static void setExperimentalAccessControl(boolean enabled) {
        FeatureFlags.experimentalAccessControl = enabled;
    }

    public static void setPandoraHacks(boolean enabled) {
        FeatureFlags.pandoraHacks = enabled;
    }

    public static void setFilterPlugins(boolean enabled) {
        FeatureFlags.filterPlugins = enabled;
    }

    public static void setSecondaryMode(boolean enabled){ secondaryMode = enabled; }

    public static boolean isSecondary() { return secondaryMode; }

    public static Map<String, Boolean> asMap() {
        Map<String,Boolean> map = new HashMap<>();
        map.put("experimentalAccessControl", experimentalAccessControl());
        map.put("pandoraHacks", pandoraHacks());
        map.put("secondaryMode", isSecondary());
        map.put("filterPlugins", filterPlugins());
        return map;
    }
}

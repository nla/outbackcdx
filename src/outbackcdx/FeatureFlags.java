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
    private static boolean acceptWrites;
    private static boolean cdx14;
    private static int indexVersion = 3;

    static {
        experimentalAccessControl = "1".equals(System.getenv("EXPERIMENTAL_ACCESS_CONTROL"));
        pandoraHacks = "1".equals(System.getenv("PANDORA_HACKS"));
        filterPlugins = "1".equals(System.getenv("FILTER_PLUGINS"));
        cdx14 = "1".equals(System.getenv("CDX14"));
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

    public static boolean cdx14() {
        return cdx14;
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

    public static void setAcceptWrites(boolean enabled){ acceptWrites = enabled; }

    public static boolean acceptsWrites() { return acceptWrites; }

    public static void setCdx14(boolean enabled) {
        cdx14 = enabled;
    }

    public static Map<String, Object> asMap() {
        Map<String,Object> map = new HashMap<>();
        map.put("experimentalAccessControl", experimentalAccessControl());
        map.put("pandoraHacks", pandoraHacks());
        map.put("secondaryMode", isSecondary());
        map.put("filterPlugins", filterPlugins());
        map.put("acceptsWrites", acceptsWrites());
        map.put("cdx14", cdx14);
        map.put("indexVersion", indexVersion());
        return map;
    }

    public static int indexVersion() {
        return indexVersion;
    }

    public static void setIndexVersion(int indexVersion) {
        if (indexVersion != 3 && indexVersion != 4) {
            throw new IllegalArgumentException("Only index versions 3 and 4 are supported, not " + indexVersion);
        }
        FeatureFlags.indexVersion = indexVersion;
    }
}

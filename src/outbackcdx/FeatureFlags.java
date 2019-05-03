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

    static {
        experimentalAccessControl = "1".equals(System.getenv("EXPERIMENTAL_ACCESS_CONTROL"));
        pandoraHacks = "1".equals(System.getenv("PANDORA_HACKS"));
    }

    public static boolean pandoraHacks() {
        return pandoraHacks;
    }

    public static boolean experimentalAccessControl() {
        return experimentalAccessControl;
    }

    public static void setExperimentalAccessControl(boolean enabled) {
        experimentalAccessControl = enabled;
    }

    public static void setSecondaryMode(boolean enabled){ secondaryMode = enabled; }

    public static boolean isSecondary() { return secondaryMode; }

    public static Map<String, Boolean> asMap() {
        Map<String,Boolean> map = new HashMap<>();
        map.put("experimentalAccessControl", experimentalAccessControl());
        map.put("pandoraHacks", pandoraHacks());
        map.put("secondaryMode", isSecondary());
        return map;
    }

    public static void setPandoraHacks(boolean pandoraHacks) {
        FeatureFlags.pandoraHacks = pandoraHacks;
    }
}

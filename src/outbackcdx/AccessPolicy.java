package outbackcdx;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class AccessPolicy {
    Long id;
    String name;

    /**
     * Access points permitted to view the captures under this policy.
     */
    Set<String> accessPoints = new HashSet<>();

    public AccessPolicy() {
    }

    public AccessPolicy(String name, String... accessPoints) {
        this.name = name;
        this.accessPoints.addAll(Arrays.asList(accessPoints));
    }
}

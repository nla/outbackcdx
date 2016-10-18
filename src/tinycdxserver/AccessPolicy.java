package tinycdxserver;

import java.util.HashSet;
import java.util.Set;

public class AccessPolicy {
    Long id;
    String name;
    Set<String> permittedAccessPoints = new HashSet<>();
}

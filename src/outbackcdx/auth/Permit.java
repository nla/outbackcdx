package outbackcdx.auth;

import java.util.EnumSet;
import java.util.Set;

public class Permit {
    public final String username;
    public final Set<Permission> permissions;

    public Permit(String username, Set<Permission> permissions) {
        this.username = username;
        this.permissions = permissions;
    }

    public static Permit full() {
        return new Permit("anonymous", EnumSet.allOf(Permission.class));
    }
}

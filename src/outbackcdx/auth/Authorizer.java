package outbackcdx.auth;

import java.util.Set;

public interface Authorizer {
    Set<Permission> verify(String authzHeader) throws AuthException;
}

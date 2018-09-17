package outbackcdx.auth;

import java.util.Set;

public interface Authorizer {
    Permit verify(String authzHeader) throws AuthException;
}

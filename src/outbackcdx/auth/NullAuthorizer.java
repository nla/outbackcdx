package outbackcdx.auth;

import outbackcdx.NanoHTTPD;

import java.util.EnumSet;
import java.util.Set;

public class NullAuthorizer implements Authorizer {
    @Override
    public Set<Permission> verify(String authzHeader) {
        return EnumSet.allOf(Permission.class);
    }
}

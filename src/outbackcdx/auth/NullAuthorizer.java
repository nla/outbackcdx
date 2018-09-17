package outbackcdx.auth;

import outbackcdx.NanoHTTPD;

import java.util.EnumSet;
import java.util.Set;

public class NullAuthorizer implements Authorizer {
    @Override
    public Permit verify(String authzHeader) {
        return Permit.full();
    }
}

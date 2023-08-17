package outbackcdx.auth;

public class NullAuthorizer implements Authorizer {
    @Override
    public Permit verify(String authzHeader) {
        return Permit.full();
    }
}

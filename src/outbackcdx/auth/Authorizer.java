package outbackcdx.auth;

public interface Authorizer {
    Permit verify(String authzHeader) throws AuthException;
}

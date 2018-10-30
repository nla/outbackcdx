package outbackcdx.auth;

public class AuthException extends Exception {
    public AuthException(String message, Exception cause) {
        super(message, cause);
    }

    public AuthException(String message) {
        super(message);
    }
}

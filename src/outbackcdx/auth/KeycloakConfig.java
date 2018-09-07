package outbackcdx.auth;

import java.net.MalformedURLException;
import java.net.URL;

public class KeycloakConfig {
    private final String url;
    private final String realm;
    private final String clientId;

    public KeycloakConfig(String url, String realm, String clientId) {
        this.url = url;
        this.realm = realm;
        this.clientId = clientId;
    }

    public Authorizer toAuthorizer() {
        try {
            URL certsUrl = new URL(url + "/realms/" + realm + "/protocol/openid-connect/certs");
            String permsPath = "resource_access/" + clientId + "/roles";
            return new JwtAuthorizer(certsUrl, permsPath);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public String getUrl() {
        return url;
    }

    public String getRealm() {
        return realm;
    }

    public String getClientId() {
        return clientId;
    }
}

package outbackcdx.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.*;

public class JwtAuthorizer implements Authorizer {
    private final String permsPath;
    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();

    public JwtAuthorizer(URL jwksUrl, String permsPath) throws MalformedURLException {
        this(new RemoteJWKSet<>(jwksUrl), permsPath);
    }

    JwtAuthorizer(JWKSource<SecurityContext> jwkSource, String permsPath) {
        this.permsPath = permsPath;
        jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
    }

    @SuppressWarnings("unchecked")
    static List<String> lookup(Map<String,Object> nestedMap, String path) throws AuthException {
        Object value = nestedMap;
        for (String segment: path.split("/")) {
            value = ((Map<String, Object>) value).get(segment);
            if (value == null) {
                throw new AuthException("Claim path " + path + " not found in access token");
            }
        }
        return (List<String>) value;
    }

    @Override
    public Set<Permission> verify(String authzHeader) throws AuthException {
        try {
            if (!authzHeader.regionMatches(true, 0, "bearer ", 0, "bearer ".length())) {
                return Collections.emptySet();
            }

            String token = authzHeader.substring("bearer ".length());
            JWTClaimsSet claimsSet = jwtProcessor.process(token, null);
            List<String> roles = lookup(claimsSet.getClaims(), permsPath);

            EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
            for (String role : roles) {
                Permission permission;
                try {
                    permission = Permission.valueOf(role.toUpperCase());
                } catch (IllegalArgumentException e) {
                    continue; // ignore unknown permissions I guess
                }
                permissions.add(permission);
            }
            return permissions;
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new AuthException("Invalid acccess token: " + e.getMessage(), e);
        }
    }
}

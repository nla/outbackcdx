package outbackcdx.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.Test;

import java.sql.Date;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static outbackcdx.auth.Permission.INDEX_EDIT;
import static outbackcdx.auth.Permission.RULES_EDIT;

public class JwtAuthorizerTest {
    @SuppressWarnings("unchecked")
    @Test
    public void lookup() throws AuthException {
        Map m1 = new HashMap();
        m1.put("two", Arrays.asList("a", "b", "c"));
        Map m2 = new HashMap();
        m2.put("one", m1);
        assertEquals(Arrays.asList("a", "b", "c"), JwtAuthorizer.lookup(m2, "one/two"));
    }

    @Test
    public void test() throws Exception {
        RSAKey rsaJWK = new RSAKeyGenerator(2048).generate();
        RSAKey rsaPublicJWK = rsaJWK.toPublicJWK();
        JWSSigner signer = new RSASSASigner(rsaJWK);
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)))
                .claim("permissions", Arrays.asList(RULES_EDIT.toString(), INDEX_EDIT.toString()))
                .build();

        SignedJWT signedJWT = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJWK.getKeyID()).build(),
                claimsSet);
        signedJWT.sign(signer);
        String token = signedJWT.serialize();

        JwtAuthorizer authorizer = new JwtAuthorizer(new ImmutableJWKSet<>(new JWKSet(rsaPublicJWK)), "permissions");
        Set<Permission> permissions = authorizer.verify("beARer " + token).permissions;
        assertEquals(EnumSet.of(RULES_EDIT, INDEX_EDIT), permissions);
    }
}
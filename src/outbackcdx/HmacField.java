package outbackcdx;

import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

public class HmacField implements ComputedField {
    Pattern VARIABLE_PATTERN = Pattern.compile("\\$([a-zA-Z0-9_]+|\\{[a-zA-Z0-9_]+})");

    private final String algorithm;
    private final String messageTemplate;
    private final String fieldTemplate;
    private final String key;
    private final int expiresSecs;
    private boolean useDigest;

    public HmacField(String algorithm, String messageTemplate, String fieldTemplate, String key, int expiresSecs) {
        this.algorithm = algorithm;
        this.messageTemplate = messageTemplate;
        this.fieldTemplate = fieldTemplate;
        this.key = key;
        this.expiresSecs = expiresSecs;

        // detect if algorithm is a Mac or MessageDigest
        try {
            Mac.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            try {
                MessageDigest.getInstance(algorithm);
                useDigest = true;
            } catch (NoSuchAlgorithmException ex) {
                throw new RuntimeException(ex);
            }
        }

        validateConfig();
    }

    /**
     * Perform a test operation so we fail on startup if the algorithm or template is bogus.
     */
    private void validateConfig() {
        Capture capture = new Capture();
        capture.file = "test.warc.gz";
        capture.urlkey = "test";
        capture.original = "test.warc.gz";
        capture.robotflags = "";
        capture.mimetype = "text/html";
        capture.digest = "example";
        capture.redirecturl = "";
        get(capture);
    }

    public Object get(Capture capture) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = now.plusSeconds(expiresSecs);
        String message = interpolate(messageTemplate, capture, now, expires, null, key);
        byte[] hmacBytes;
        try {
            if (useDigest) {
                MessageDigest digest = MessageDigest.getInstance(algorithm);
                hmacBytes = digest.digest(message.getBytes(UTF_8));
            } else {
                Mac hmac = Mac.getInstance(algorithm);
                hmac.init(new SecretKeySpec(key.getBytes(UTF_8), algorithm));
                hmacBytes = hmac.doFinal(message.getBytes(UTF_8));
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
        String hmacHex = Hex.encodeHexString(hmacBytes);
        return interpolate(fieldTemplate, capture, now, expires, hmacBytes, null);
    }

    private String interpolate(String template, Capture capture, Instant now, Instant expires, byte[] hmac, String key) {
        StringBuffer buffer = new StringBuffer();
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        while (matcher.find()) {
            String variable = matcher.group(1);
            String value;
            switch (variable) {
                case "expires_iso8601":
                    value = expires.toString();
                    break;
                case "expires":
                    value = Long.toString(expires.getEpochSecond());
                    break;
                case "expires_hex":
                    value = Long.toHexString(expires.getEpochSecond());
                    break;
                case "hmac_hex":
                    value = Hex.encodeHexString(hmac);
                    break;
                case "hmac_base64":
                    value = Base64.getEncoder().withoutPadding().encodeToString(hmac);
                    break;
                case "hmac_base64_pct":
                    value = Base64.getEncoder().withoutPadding().encodeToString(hmac).replace("+", "%2B");
                    break;
                case "hmac_base64_url":
                    value = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
                    break;
                case "now_iso8601":
                    value = now.toString();
                    break;
                case "now":
                    value = Long.toString(now.getEpochSecond());
                    break;
                case "now_hex":
                    value = Long.toHexString(now.getEpochSecond());
                    break;
                case "secret_key":
                    value = key;
                    break;
                case "dollar":
                    value = "$";
                    break;
                case "CR":
                    value = "\r";
                    break;
                case "LF":
                    value = "\n";
                    break;
                case "CRLF":
                    value = "\r\n";
                    break;
                default:
                    value = capture.get(variable).toString();
                    break;
            }
            matcher.appendReplacement(buffer, value);
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}

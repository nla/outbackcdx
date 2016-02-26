package tinycdxserver;

import java.nio.charset.StandardCharsets;

public class Alias {
    public String alias;
    public String target;

    public Alias(byte[] key, byte[] value) {
        alias = new String(key, StandardCharsets.US_ASCII);
        target = new String(value, StandardCharsets.US_ASCII);
    }
}

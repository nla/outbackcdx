package tinycdxserver;

import org.junit.Test;

import java.time.Period;

import static org.junit.Assert.assertEquals;
import static tinycdxserver.Json.GSON;

public class JsonTest {
    @Test
    public void roundtripPeriod() {
        Period original = Period.of(1,2,3);
        String json = GSON.toJson(original);
        Period decoded = GSON.fromJson(json, Period.class);
        assertEquals(original, decoded);
    }
}

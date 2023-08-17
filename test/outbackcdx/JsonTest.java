package outbackcdx;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Test;

import java.time.Period;

import static org.junit.Assert.assertEquals;
import static outbackcdx.Json.JSON_MAPPER;

public class JsonTest {
    @Test
    public void roundtripPeriod() throws JsonProcessingException {
        Period original = Period.of(1,2,3);
        String json = JSON_MAPPER.writeValueAsString(original);
        Period decoded = JSON_MAPPER.readValue(json, Period.class);
        assertEquals(original, decoded);
    }
}

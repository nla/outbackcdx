package outbackcdx;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

import java.io.IOException;
import java.time.Period;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;

public class Json {
    public static final CBORFactory CBOR_FACTORY = new CBORFactory();
    public static final ObjectMapper CBOR_MAPPER = new ObjectMapper(CBOR_FACTORY);
    public static final JsonFactory JSON_FACTORY = new JsonFactory();
    public static final ObjectMapper JSON_MAPPER = new ObjectMapper(JSON_FACTORY)
            .registerModule(new SimpleModule()
                    .addDeserializer(Period.class, new JsonDeserializer<Period>() {
                        @Override
                        public Period deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
                            JsonNode node = p.getCodec().readTree(p);
                            int years = node.get("years").asInt(0);
                            int months = node.get("months").asInt(0);
                            int days = node.get("days").asInt(0);
                            return Period.of(years, months, days);
                        }
                    })
                    .addSerializer(Period.class, new JsonSerializer<Period>() {
                        @Override
                        public void serialize(Period value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                            gen.writeStartObject();
                            gen.writeNumberField("years", value.getYears());
                            gen.writeNumberField("months", value.getMonths());
                            gen.writeNumberField("days", value.getDays());
                            gen.writeEndObject();
                        }
                    }))
            .disable(WRITE_DATES_AS_TIMESTAMPS)
            .setDateFormat(new StdDateFormat().withColonInTimeZone(true));
}

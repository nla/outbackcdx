package outbackcdx;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Period;

public class Json {
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
            .registerTypeAdapter(Period.class, new PeriodTypeAdapter())
            .create();
    public static final CBORFactory CBOR_FACTORY = new CBORFactory();
    public static final ObjectMapper CBOR_MAPPER = new ObjectMapper(CBOR_FACTORY);
    public static final JsonFactory JSON_FACTORY = new JsonFactory();
    public static final ObjectMapper JSON_MAPPER = new ObjectMapper(JSON_FACTORY);

    private static class PeriodTypeAdapter extends TypeAdapter<Period> {
        @Override
        public void write(JsonWriter out, Period value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.beginObject();
            out.name("years");
            out.value(value.getYears());
            out.name("months");
            out.value(value.getMonths());
            out.name("days");
            out.value(value.getDays());
            out.endObject();
        }

        @Override
        public Period read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            int years = 0;
            int months = 0;
            int days = 0;
            in.beginObject();
            while (in.hasNext()) {
                switch (in.nextName()) {
                    case "years": years = in.nextInt(); break;
                    case "months": months = in.nextInt(); break;
                    case "days": days = in.nextInt(); break;
                    default: in.skipValue(); break;
                }
            }
            in.endObject();
            return Period.of(years, months, days);
        }
    }
}

package outbackcdx;

import com.fasterxml.jackson.core.JsonGenerator;
import com.google.gson.stream.JsonWriter;
import outbackcdx.NanoHTTPD.Response;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static outbackcdx.Json.GSON;
import static outbackcdx.NanoHTTPD.Response.Status.OK;

/**
 * Implements a partial, semi-compatible subset of the various CDX server APIs.
 *
 * The intent is to at least be compatible with Pywb's RemoteCDXSource. Anything
 * extra is bonus.
 *
 * wb: https://github.com/internetarchive/wayback/tree/master/wayback-cdx-server
 * pywb: https://github.com/ikreymer/pywb/wiki/CDX-Server-API
 */
public class WbCdxApi {
    private final Iterable<FilterPlugin> filterPlugins;
    private final Map<String, ComputedField> computedFields;

    public WbCdxApi(Iterable<FilterPlugin> filterPlugins, Map<String, ComputedField> computedFields) {
        this.filterPlugins = filterPlugins;
        this.computedFields = computedFields;
    }

    public Response queryIndex(Web.Request request, Index index) {
        Query query = new Query(request.params(), filterPlugins);
        Iterable<Capture> captures = query.execute(index);

        FormatFactory format;
        String contentType;
        switch (request.param("output", "cdx")) {
            case "json":
                format = JsonFormat::new;
                contentType = "application/json";
                break;
            case "jsondict":
                format = JsonDictFormat::new;
                contentType = "application/json";
                break;
            case "cdxj":
                format = CdxjFormat::new;
                contentType = "text/x-cdxj";
                break;
            default:
                format = TextFormat::new;
                contentType = "text/plain";
                break;
        }

        Response response = new Response(OK, contentType, outputStream -> {
            Writer out = new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8));
            OutputFormat outf = format.construct(query, computedFields, out);
            long row = 0;
            try {
                for (Capture capture : captures) {
                    if (row >= query.limit) {
                        break;
                    }
                    outf.writeCapture(capture);
                    row++;
                }
            } catch (Exception e) {
                System.err.println(new Date() + ": exception " + e + " thrown processing captures");
                e.printStackTrace();
                out.write("warning: output may be incomplete, error occurred processing captures\n");
            }

            outf.close();
            out.flush();
        });
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("outbackcdx-urlkey", query.urlkey);
        return response;
    }

    interface FormatFactory {
        OutputFormat construct(Query query, Map<String, ComputedField> computedFields, Writer out) throws IOException;
    }

    static abstract class OutputFormat {
        protected final Query query;
        protected final Writer writer;
        protected final Map<String, ComputedField> computedFields;

        OutputFormat(Query query, Map<String, ComputedField> computedFields, Writer writer) {
            this.query = query;
            this.writer = writer;
            this.computedFields = computedFields;
        }

        protected Object computeField(Capture capture, String field) {
            ComputedField computed = computedFields.get(field);
            if (computed != null) {
                return computed.get(capture);
            }
            return capture.get(field);
        }

        public abstract void writeCapture(Capture capture) throws IOException;
        void close() throws IOException {}
    }

    static class JsonDictFormat extends OutputFormat {
        private final JsonWriter jsonWriter;

        JsonDictFormat(Query query, Map<String, ComputedField> computedFields, Writer writer) throws IOException {
            super(query, computedFields, writer);
            this.jsonWriter = GSON.newJsonWriter(writer);
            jsonWriter.beginArray();
        }

        @Override
        public void writeCapture(Capture capture) throws IOException {
            jsonWriter.beginObject();
            for (String field : Arrays.asList(query.fields)) {
                Object value = computeField(capture, field);
                if (value == null || "-".equals(value)) {
                    // omit it
                } else if (value instanceof Long) {
                    jsonWriter.name(field).value((long) value);
                } else if (value instanceof Integer) {
                    jsonWriter.name(field).value((int) value);
                } else if (value instanceof String) {
                    jsonWriter.name(field).value((String) value);
                } else {
                    throw new UnsupportedOperationException("Don't know how to format: " + field + " (" + value.getClass() + ")");
                }
            }
            jsonWriter.endObject();
        }

        @Override
        public void close() throws IOException {
            jsonWriter.endArray();
            jsonWriter.close();
        }
    }

    /**
     * Formats captures as a JSON array. Supports the field names used by both
     * pywb and wayback-cdx-server.
     */
    static class JsonFormat extends OutputFormat {
        private final JsonWriter jsonWriter;

        JsonFormat(Query query, Map<String, ComputedField> computedFields, Writer writer) throws IOException {
            super(query, computedFields, writer);
            this.jsonWriter = GSON.newJsonWriter(writer);
            jsonWriter.beginArray();
        }

        @Override
        public void writeCapture(Capture capture) throws IOException {
            jsonWriter.beginArray();
            for (String field : query.fields) {
                Object value = computeField(capture, field);
                if (value == null) {
                    jsonWriter.nullValue();
                } if (value instanceof Long) {
                    jsonWriter.value((long) value);
                } else if (value instanceof Integer) {
                    jsonWriter.value((int) value);
                } else if (value instanceof String) {
                    jsonWriter.value((String) value);
                } else {
                    throw new UnsupportedOperationException("Don't know how to format: " + field + " (" + value.getClass() + ")");
                }
            }
            jsonWriter.endArray();
        }

        @Override
        public void close() throws IOException {
            jsonWriter.endArray();
            jsonWriter.close();
        }
    }

    static class TextFormat extends OutputFormat {
        TextFormat(Query query, Map<String, ComputedField> computedFields, Writer writer) {
            super(query, computedFields, writer);
        }

        @Override
        public void writeCapture(Capture capture) throws IOException {
            for (int i = 0; i < query.fields.length; i++) {
                String field = query.fields[i];
                Object value = computeField(capture, field);
                writer.write(value == null ? "-" : value.toString());
                if (i < query.fields.length - 1) {
                    writer.write(' ');
                }
            }
            writer.write('\n');
        }
    }

    static class CdxjFormat extends OutputFormat {
        CdxjFormat(Query query, Map<String, ComputedField> computedFields, Writer writer) {
            super(query, computedFields, writer);
        }

        @Override
        public void writeCapture(Capture capture) throws IOException {
            writer.write(capture.urlkey);
            writer.write(' ');
            writer.write(String.valueOf(capture.timestamp));
            writer.write(' ');
            try (JsonGenerator generator = Json.JSON_MAPPER.createGenerator(writer)) {
                List<String> filteredFields = Arrays.stream(query.fields).filter(f -> !f.equals("urlkey") && !f.equals("timestamp")).collect(toList());
                generator.writeStartObject();
                for (String field : filteredFields) {
                    Object value = computeField(capture, field);
                    if (value == null || "-".equals(value)) {
                        // omit it
                    } else if (value instanceof Long) {
                        generator.writeStringField(field, value.toString()); // pywb uses strings for everything
                    } else if (value instanceof Integer) {
                        generator.writeStringField(field, value.toString()); // pywb uses strings for everything
                    } else if (value instanceof String) {
                        generator.writeStringField(field, (String) value);
                    } else {
                        generator.writeFieldName(field);
                        Json.JSON_MAPPER.writeValue(generator, value);
                    }
                }
                if (query.allFields && capture.extra != null) {
                    for (Map.Entry<String, Object> entry : capture.extra.entrySet()) {
                        generator.writeFieldName(entry.getKey());
                        Json.JSON_MAPPER.writeValue(generator, entry.getValue());
                    }
                }
                generator.writeEndObject();
            }
            writer.write('\n');
        }
    }
}

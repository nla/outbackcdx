package tinycdxserver;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Implements the Wayback RemoteCollection XmlQuery interface.
 */
public class XmlQuery {
    final static String DEFAULT_ENCODING = "UTF-8";

    private static Map<String,String> decodeQueryString(String q) {
        Map<String,String> m = new HashMap<String, String>();
        for (String entry : q.split(" ")) {
            String[] fields = entry.split(":", 2);
            m.put(fields[0], fields[1]);
        }
        return m;
    }

    private static void writeElement(XMLStreamWriter out, String name, Object value) throws XMLStreamException {
        out.writeStartElement(name);
        out.writeCharacters(value.toString());
        out.writeEndElement();
    }

    public static NanoHTTPD.Response query(NanoHTTPD.IHTTPSession session, final DB index) {
        Map<String,String> params = session.getParms();
        Map<String,String> query = decodeQueryString(params.get("q"));

        final String type = query.getOrDefault("type", "urlquery");
        if (!type.equals("urlquery")) {
            return new NanoHTTPD.Response(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "only the urlquery type is implemented");
        }

        final String url = query.get("url");
        final long offset = 0;
        final long limit = 10000;

        System.out.println("xmlquery " + url);
        return new NanoHTTPD.Response(NanoHTTPD.Response.Status.OK, "application/xml;charset=" + DEFAULT_ENCODING, new NanoHTTPD.IStreamer() {
            @Override
            public void stream(OutputStream outputStream) throws IOException {
                DBIterator it = index.iterator();
                try {
                    it.seek(Record.encodeKey(url, 0));
                    XMLOutputFactory factory = XMLOutputFactory.newInstance();
                    XMLStreamWriter out = factory.createXMLStreamWriter(outputStream, DEFAULT_ENCODING);
                    out.writeStartDocument(DEFAULT_ENCODING, "1.0");
                    out.writeStartElement("wayback");

                    Record record = null;
                    if (it.hasNext()) {
                        record = new Record(it.next());
                    }
                    if (record != null && record.urlkey.equals(url)) {
                        out.writeStartElement("request");
                        writeElement(out, "startdate", "19960101000000");
                        writeElement(out, "enddate", Record.arcTimeFormat.format(LocalDateTime.now(ZoneOffset.UTC)));
                        //writeElement(out, "numreturned", 0);
                        writeElement(out, "type", type);
                        writeElement(out, "firstreturned", offset);
                        writeElement(out, "url", url);
                        writeElement(out, "resultsrequested", limit);
                        writeElement(out, "resultstype", "resultstypecapture");
                        out.writeEndElement(); // </request>

                        out.writeStartElement("results");
                        do {
                            writeElement(out, "compressedoffset", record.compressedoffset);
                            writeElement(out, "mimetype", record.mimetype);
                            writeElement(out, "file", record.file);
                            writeElement(out, "redirecturl", record.redirecturl);
                            writeElement(out, "urlkey", record.urlkey);
                            writeElement(out, "digest", record.digest);
                            writeElement(out, "httpresponsecode", record.status);
                            writeElement(out, "robotflags", "-"); // TODO
                            writeElement(out, "url", record.original);
                            writeElement(out, "capturedate", record.timestamp);
                            if (!it.hasNext()) break;
                            record = new Record(it.next());;
                        } while (record.urlkey.equals(url));
                        out.writeEndElement(); // </results>
                    } else {
                        out.writeStartElement("error");
                        writeElement(out, "title", "Resource Not In Archive");
                        writeElement(out, "message", "The Resource you requested is not in this archive.");
                        out.writeEndElement();
                    }
                    out.writeEndElement(); // </wayback>
                    out.writeEndDocument();
                    out.flush();
                } catch (XMLStreamException e) {
                    throw new RuntimeException(e);
                } finally {
                    it.close();
                }
            }
        });
    }
}

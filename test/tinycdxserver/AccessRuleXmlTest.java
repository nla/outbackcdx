package tinycdxserver;

import org.junit.Test;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AccessRuleXmlTest {
    @Test
    public void test() throws IOException, XMLStreamException {
        try (InputStream stream = getClass().getResourceAsStream("rules.xml")) {
            List<String> urls = new ArrayList<>();
            for (AccessRule rule: AccessRuleXml.parseRules(stream)) {
                urls.addAll(rule.urlPatterns);
            }
            assertEquals(Arrays.asList("*", "http://www.nla.gov.au:80/index.htm", "http://www.nla.gov.au:80/foo/*", "*.nla.gov.au"), urls);
        }
    }
}

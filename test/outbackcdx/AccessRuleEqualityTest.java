package outbackcdx;

import org.junit.Test;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AccessRuleEqualityTest {

    private List<AccessRule> getRules() throws IOException, XMLStreamException {
      try (InputStream stream = getClass().getResourceAsStream("rules.xml")) {
          return AccessRuleXml.parseRules(stream);
      }
    }

    @Test
    public void equalityTest() throws IOException, XMLStreamException {
        List<AccessRule> rules1 = getRules();
        assertEquals("Should be 4 rules (1)", 4, rules1.size());

        // We get two completely separate copies to ensure no equality shortcuts are triggered by object references
        List<AccessRule> rules2 = getRules();
        assertEquals("Should be 4 rules (2)", 4, rules2.size());

        for (int i = 0; i < 4; i++) {
            assertTrue("Rules should be equal: i = " + i, rules1.get(i).equals(rules2.get(i)));
        }
    }
}

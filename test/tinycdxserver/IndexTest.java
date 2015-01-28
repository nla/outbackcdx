package tinycdxserver;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.junit.Assert.assertNotNull;

public class IndexTest {
    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    @Test
    public void testRoundTrip() throws IOException {
        Index index = new Index(tempDir.newFolder("index"));
        Record src = RecordTest.dummyRecord();
        index.put(src);
        Record dst = index.get(src.urlkey, src.timestamp);
        assertNotNull(dst);
        RecordTest.assertFieldsEqual(src, dst);
    }
}

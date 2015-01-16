package tinycdxserver;

import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

public class VarIntTest {
    @Test
    public void testVarIntRoundTrip() {
        long testValue = 632662171617822L;
        ByteBuffer bb = ByteBuffer.allocate(32);
        VarInt.encode(bb, testValue);
        assertEquals(VarInt.size(testValue), bb.position());
        bb.flip();
        long x = VarInt.decode(bb);
        assertEquals(testValue, x);
    }

    @Test
    public void testAsciiRoundTrip() {
        String testValue = "hello world";
        ByteBuffer bb = ByteBuffer.allocate(32);
        VarInt.encodeAscii(bb, testValue);
        assertEquals(VarInt.sizeAscii(testValue), bb.position());
        bb.flip();
        String x = VarInt.decodeAscii(bb);
        assertEquals(testValue, x);
    }
}

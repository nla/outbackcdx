package outbackcdx;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;

public class CaptureBenchmark {
    @State(Scope.Benchmark)
    public static class MyState {
        UrlCanonicalizer canonicalizer = new UrlCanonicalizer();
        Capture capture = Capture.fromCdxLine("- 19870102030405 http://example.org/ text/html 200 sha1:M5ORM4XQ5QCEZEDRNZRGSWXPCOGUVASI - 100 test.warc.gz", canonicalizer);
        Capture capture2 = Capture.fromCdxLine("- 20210203115119 {\"url\": \"https://example.org/robots.txt\", " +
                                                    "\"mime\": \"unk\", \"status\": \"400\", \"digest\": \"3I42H3S6NNFQ2MSVX7XZKYAYSCX5QBYJ\", " +
                                                    "\"length\": \"451\", \"offset\": \"90493\", \"filename\": \"example.warc.gz\", \"method\": \"POST\", \"requestBody\": \"x=1&y=2\"}",
                                            canonicalizer);
        byte[] keyV3 = capture2.encodeKey(3);
        byte[] keyV5 = capture2.encodeKey(5);
        byte[] valueV3 = capture2.encodeValue(3);
        byte[] valueV5 = capture2.encodeValue(5);
    }

    @Benchmark
    public Capture parseCdx(MyState state) {
        return Capture.fromCdxLine("org,example)/ 19870102030405 http://example.org/ text/html 200 sha1:M5ORM4XQ5QCEZEDRNZRGSWXPCOGUVASI - 100 test.warc.gz", state.canonicalizer);
    }

    @Benchmark
    public Capture parseCdxInfer(MyState state) {
        return Capture.fromCdxLine("org,example)/?__wb_method=post&__wb_post_data=dGVzdAo= 19870102030405 http://example.org/ text/html 200 sha1:M5ORM4XQ5QCEZEDRNZRGSWXPCOGUVASI - 100 test.warc.gz", state.canonicalizer);
    }

    @Benchmark
    public Capture parseCdxj(MyState state) {
        return Capture.fromCdxLine("org,example)/ 20210203115119 {\"url\": \"https://example.org/\", " +
                        "\"mime\": \"unk\", \"status\": \"400\", \"digest\": \"3I42H3S6NNFQ2MSVX7XZKYAYSCX5QBYJ\", " +
                        "\"length\": \"451\", \"offset\": \"90493\", \"filename\": \"example.warc.gz\", " +
                        "\"non-standard-field\": [\"yes\", 2, 3], \"method\": \"POST\", \"requestBody\": \"x=1&y=2\"}",
                state.canonicalizer);
    }

    @Benchmark
    public Capture parseCdxjInfer(MyState state) {
        return Capture.fromCdxLine("org,example)/?__wb_method=post&__wb_post_data=dGVzdAo= 20210203115119 {\"url\": \"https://example.org/\", " +
                        "\"mime\": \"unk\", \"status\": \"400\", \"digest\": \"3I42H3S6NNFQ2MSVX7XZKYAYSCX5QBYJ\", " +
                        "\"length\": \"451\", \"offset\": \"90493\", \"filename\": \"example.warc.gz\", " +
                        "\"non-standard-field\": [\"yes\", 2, 3]}",
                state.canonicalizer);
    }

    @Benchmark
    public void encodeV3(MyState state, Blackhole blackhole) {
        blackhole.consume(state.capture.encodeKey(3));
        blackhole.consume(state.capture.encodeValue(3));
    }

    @Benchmark
    public void encodeV5(MyState state, Blackhole blackhole) {
        blackhole.consume(state.capture.encodeKey(5));
        blackhole.consume(state.capture.encodeValue(5));
    }

    @Benchmark
    public void decodeV3(MyState state, Blackhole blackhole) {
        blackhole.consume(new Capture(state.keyV3, state.valueV3));
    }

    @Benchmark
    public void decodeV5(MyState state, Blackhole blackhole) {
        blackhole.consume(new Capture(state.keyV5, state.valueV5));
    }

    public static void main(String[] args) throws IOException {
        org.openjdk.jmh.Main.main(args);
    }
}

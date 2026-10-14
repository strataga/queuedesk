package dev.queuedesk.http;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The 16 KiB body limit at both read paths: exactly-at-limit passes, one
 * byte over fails, on bulk reads, single-byte reads, and the character
 * (Reader) path that would otherwise bypass the counter.
 */
class BodyLimitFilterTest {
    private static ServletInputStream stream(byte[] data) {
        var in = new ByteArrayInputStream(data);
        return new ServletInputStream() {
            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {}
        };
    }
    private static void drainBulk(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        while (in.read(buf, 0, buf.length) >= 0) {}
    }
    private static void drainSingle(InputStream in) throws IOException {
        while (in.read() >= 0) {}
    }
    @Test void exactLimitPasses() throws IOException {
        drainBulk(new BodyLimitFilter.CountingStream(stream(new byte[BodyLimitFilter.MAX_BODY_BYTES])));
    }
    @Test void oneByteOverFailsOnBulkReads() {
        assertThrows(BodyLimitFilter.BodyTooLarge.class,
                () -> drainBulk(new BodyLimitFilter.CountingStream(stream(new byte[BodyLimitFilter.MAX_BODY_BYTES + 1]))));
    }
    @Test void oneByteOverFailsOnSingleReads() {
        assertThrows(BodyLimitFilter.BodyTooLarge.class,
                () -> drainSingle(new BodyLimitFilter.CountingStream(stream(new byte[BodyLimitFilter.MAX_BODY_BYTES + 1]))));
    }
    @Test void readerPathCountsToo() throws IOException {
        var mock = new org.springframework.mock.web.MockHttpServletRequest();
        mock.setContent(new byte[BodyLimitFilter.MAX_BODY_BYTES + 1]);
        var reader = new BodyLimitFilter.CountingRequest(mock).getReader();
        assertThrows(BodyLimitFilter.BodyTooLarge.class, () -> { while (reader.read() >= 0) {} });
        var small = new org.springframework.mock.web.MockHttpServletRequest();
        small.setContent(new byte[BodyLimitFilter.MAX_BODY_BYTES]);
        var ok = new BodyLimitFilter.CountingRequest(small).getReader();
        while (ok.read() >= 0) {}
    }
}

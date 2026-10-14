package dev.queuedesk.http;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// W3C traceparent carried on the outbox row lets the detail view link to its
// sampled trace. Malformed or absent parents yield no link, never a 500.
class TraceIdTest {
    @Test void validParentYields32HexTraceId() {
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", InstructionController.parseTraceId(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
    }
    @Test void absentOrMalformedParentsYieldNull() {
        assertNull(InstructionController.parseTraceId(null));
        assertNull(InstructionController.parseTraceId(""));
        assertNull(InstructionController.parseTraceId("not-a-traceparent"));
        assertNull(InstructionController.parseTraceId("00-tooshort-00f067aa0ba902b7-01"));
        assertNull(InstructionController.parseTraceId("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
    }
}

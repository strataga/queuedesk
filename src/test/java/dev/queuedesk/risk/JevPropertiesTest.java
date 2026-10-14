package dev.queuedesk.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jev configuration validation: pinned models only, mandatory credential,
 * HTTPS outside loopback, bounded deadlines, and a credential-redacting
 * toString. Plain JUnit: the holder must fail fast wherever it is built.
 */
class JevPropertiesTest {
    @Test void acceptsAPinnedModelOnHttps() {
        var props = new JevProperties("https://api.typesafe.ai", "jev-1.13.0", "secret", 2000);
        assertEquals("https://api.typesafe.ai/v1/systemone", props.endpoint());
    }

    @Test void allowsLoopbackHttpForTests() {
        var props = new JevProperties("http://127.0.0.1:9", "jev-1.13", "test-key", 2000);
        assertEquals("http://127.0.0.1:9/v1/systemone", props.endpoint());
    }

    @Test void trimsABaseTrailingSlash() {
        var props = new JevProperties("https://api.typesafe.ai/", "jev-1.13.0", "secret", 2000);
        assertEquals("https://api.typesafe.ai/v1/systemone", props.endpoint());
    }

    @Test void rejectsTheMovingAlias() {
        var failed = assertThrows(IllegalArgumentException.class,
                () -> new JevProperties("https://api.typesafe.ai", "jev-latest", "secret", 2000));
        assertTrue(failed.getMessage().contains("jev-latest"));
    }

    @Test void rejectsCleartextOffLoopback() {
        assertThrows(IllegalArgumentException.class,
                () -> new JevProperties("http://api.typesafe.ai", "jev-1.13.0", "secret", 2000));
    }

    @Test void rejectsABlankCredential() {
        assertThrows(IllegalArgumentException.class,
                () -> new JevProperties("https://api.typesafe.ai", "jev-1.13.0", "  ", 2000));
    }

    @Test void rejectsDeadlinesOutsideBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new JevProperties("https://api.typesafe.ai", "jev-1.13.0", "secret", 49));
        assertThrows(IllegalArgumentException.class,
                () -> new JevProperties("https://api.typesafe.ai", "jev-1.13.0", "secret", 30_001));
    }

    @Test void toStringRedactsTheCredential() {
        var props = new JevProperties("https://api.typesafe.ai", "jev-1.13.0", "super-secret", 2000);
        assertFalse(props.toString().contains("super-secret"));
        assertTrue(props.toString().contains("jev-1.13.0"));
    }
}

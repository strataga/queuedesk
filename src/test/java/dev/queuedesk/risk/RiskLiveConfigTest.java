package dev.queuedesk.risk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live-gate wiring: the Jev bean exists only in RULES_PLUS_JEV mode with a
 * credential, and boot fails loudly without one. Hermetic by construction:
 * every run pins the key or key file explicitly, so a developer's real key
 * file can neither rescue the failure case nor leak into the success case.
 */
class RiskLiveConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RiskLiveConfig.class)
            .withBean(RiskAssessmentRepository.class, InMemoryRiskAssessmentRepository::new)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test void jevModeWithKeyBuildsTheLiveProvider() {
        runner.withPropertyValues("queuedesk.risk.mode=RULES_PLUS_JEV",
                        "queuedesk.risk.jev.api-key=test-key",
                        "queuedesk.risk.jev.api-key-file=/nonexistent/key")
                .withBean(RiskProperties.class, () -> RiskProperties.forTest(RiskMode.RULES_PLUS_JEV))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(JevRiskSignalProvider.class,
                            context.getBean(RiskSignalProvider.class));
                });
    }

    @Test void jevModeWithoutACredentialFailsBootLoudly() {
        runner.withPropertyValues("queuedesk.risk.mode=RULES_PLUS_JEV",
                        "queuedesk.risk.jev.api-key-file=/nonexistent/key")
                .withBean(RiskProperties.class, () -> RiskProperties.forTest(RiskMode.RULES_PLUS_JEV))
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(rootMessage(context.getStartupFailure()).contains("needs a credential"),
                            rootMessage(context.getStartupFailure()));
                });
    }

    @Test void otherModesLeaveTheLiveBeanOut() {
        runner.withPropertyValues("queuedesk.risk.mode=RULES_PLUS_MOCK")
                .withBean(RiskProperties.class, () -> RiskProperties.forTest(RiskMode.RULES_PLUS_MOCK))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(JevRiskSignalProvider.class).isEmpty());
                });
    }

    private static String rootMessage(Throwable failure) {
        var messages = new StringBuilder();
        for (var at = failure; at != null; at = at.getCause())
            messages.append(at.getMessage()).append(" | ");
        return messages.toString();
    }
}

package dev.queuedesk.risk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

/**
 * Live Jev wiring. In {@code RULES_PLUS_JEV} mode only, a primary Jev
 * adapter replaces the mock provider bean; in every other mode this
 * configuration is inert and the mock stays the sole provider.
 *
 * <p>The credential resolves from explicit configuration first, then from
 * the gitignored key file. Jev mode with neither fails boot with a named
 * error: never a silent fallback to the mock, and never a live call without
 * a credential. Running this mode requires deliberate human action (setting
 * the mode plus providing the key).
 */
@Configuration
public class RiskLiveConfig {
    /** Builds the live Jev provider (real HTTP transport, budget ledger wired in). */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "queuedesk.risk.mode", havingValue = "RULES_PLUS_JEV")
    public RiskSignalProvider jevProvider(
            @Value("${queuedesk.risk.jev.base-url:https://api.typesafe.ai}") String baseUrl,
            @Value("${queuedesk.risk.jev.model:jev-1.13.0}") String model,
            @Value("${queuedesk.risk.jev.api-key:}") String apiKey,
            @Value("${queuedesk.risk.jev.api-key-file:.cache/jev-secrets/apiKey}") String apiKeyFile,
            @Value("${queuedesk.risk.jev.deadline-ms:2000}") long deadlineMs,
            RiskProperties properties,
            RiskAssessmentRepository assessments,
            ObjectMapper mapper) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) key = readKeyFile(apiKeyFile);
        if (key.isEmpty())
            throw new IllegalStateException("RULES_PLUS_JEV needs a credential: set "
                    + "QUEUEDESK_RISK_JEV_API_KEY or save the key to .cache/jev-secrets/apiKey "
                    + "(chmod 600, gitignored). Refusing to boot a live gate without one.");
        var jev = new JevProperties(baseUrl, model, key, deadlineMs);
        return new JevRiskSignalProvider(jev, new JevHttpTransport(jev), mapper, assessments,
                properties.jevBudgetNanoUsd());
    }

    static String readKeyFile(String path) {
        try {
            String content = Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? "" : content;
        } catch (Exception unreadable) {
            return "";
        }
    }
}

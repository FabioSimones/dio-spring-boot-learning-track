package dio.budgeting.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.PlaceholderResolutionException;

/**
 * Fails startup with a clear, safe message when a required environment
 * variable is missing for {@code dev}/{@code prod} (TASK-012) - neither
 * profile ships an insecure default for these four keys.
 *
 * <p>Left unenforced in the default (no-profile) and {@code test} profiles on
 * purpose: {@code application.properties} has no {@code spring.ai.openai.api-key}
 * at all, and the test profile supplies its own fictitious one - this
 * validator only ever runs where a missing variable would otherwise be a
 * silent, hard-to-diagnose problem (a real deployment or a developer pointing
 * at a real database/API).
 *
 * <p>Why {@link Environment#getProperty} instead of relying on Spring Boot's
 * own {@code @ConfigurationProperties} binding to catch this: verified
 * empirically that an unresolved {@code ${VAR}} placeholder (no default, no
 * env var set) is NOT rejected by the lenient binder Spring AI/Boot's
 * datasource autoconfiguration use internally - it silently resolves to a
 * blank value, deferring the failure to the first real HTTP call/connection
 * attempt. {@code Environment.getProperty} is stricter: it throws {@link
 * PlaceholderResolutionException} for the same input, which this class turns
 * into an immediate, specific {@link IllegalStateException} instead.
 */
@Component
@Profile({"dev", "prod"})
public class EnvironmentConfigurationValidator {

    private final Environment environment;

    public EnvironmentConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        requireResolved("spring.ai.openai.api-key", "OPENAI_API_KEY");
        requireResolved("spring.datasource.url", "DB_URL");
        requireResolved("spring.datasource.username", "DB_USERNAME");
        requireResolved("spring.datasource.password", "DB_PASSWORD");
    }

    private void requireResolved(String propertyKey, String variableName) {
        String value;
        try {
            value = environment.getProperty(propertyKey);
        }
        catch (PlaceholderResolutionException unresolved) {
            value = null;
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("A variável " + variableName
                    + " é obrigatória para o perfil ativo (" + String.join(",", environment.getActiveProfiles()) + ").");
        }
    }
}

package dio.budgeting;

import dio.budgeting.infrastructure.config.EnvironmentConfigurationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-012: proves EnvironmentConfigurationValidator's actual effect - a
 * missing OPENAI_API_KEY/DB_URL/DB_USERNAME/DB_PASSWORD fails startup with a
 * clear message under "dev"/"prod", but the validator is never even
 * registered under the default (no active profile) or "test" profile
 * (@Profile({"dev","prod"})). Uses ApplicationContextRunner - no real
 * database, no real OpenAI call, no application-prod.properties file is ever
 * read (that file is ".example" and is never loaded automatically).
 */
class EnvironmentConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(EnvironmentConfigurationValidator.class);

    @Test
    void devProfile_missingOpenAiKey_failsWithClearMessage() {
        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "spring.datasource.url=jdbc:mysql://localhost:3306/budgeting",
                        "spring.datasource.username=root",
                        "spring.datasource.password=whatever")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("OPENAI_API_KEY");
                });
    }

    @Test
    void devProfile_missingDbPassword_failsWithClearMessage() {
        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "spring.ai.openai.api-key=sk-fake-not-real",
                        "spring.datasource.url=jdbc:mysql://localhost:3306/budgeting",
                        "spring.datasource.username=root")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("DB_PASSWORD");
                });
    }

    @Test
    void devProfile_withAllRequiredValues_startsSuccessfully() {
        runner.withPropertyValues(
                        "spring.profiles.active=dev",
                        "spring.ai.openai.api-key=sk-fake-not-real",
                        "spring.datasource.url=jdbc:mysql://localhost:3306/budgeting",
                        "spring.datasource.username=root",
                        "spring.datasource.password=whatever")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodProfile_missingAnyRequiredValue_failsWithClearMessage() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void defaultProfile_neverActivatesValidator_evenWithNothingConfigured() {
        // No active profile -> @Profile({"dev","prod"}) excludes this bean from
        // the context entirely (Spring honors the annotation regardless of
        // whether the bean was classpath-scanned or registered via withBean),
        // so @PostConstruct never runs and a completely unconfigured
        // environment still starts successfully.
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void testProfile_neverActivatesValidator_evenWithNothingConfigured() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).hasNotFailed());
    }
}

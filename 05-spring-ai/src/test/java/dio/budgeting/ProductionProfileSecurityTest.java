package dio.budgeting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-012: proves the production posture documented in
 * application-prod.properties.example without ever loading a real "prod"
 * profile file (that file is intentionally ".example", never auto-loaded) or
 * opening a real connection - the exact property values from that file are
 * fed directly to an ApplicationContextRunner, then asserted on. No real
 * database, no real OpenAI call, no Docker.
 */
class ProductionProfileSecurityTest {

    private static final String[] PROD_LIKE_PROPERTIES = {
            "management.endpoints.web.exposure.include=health",
            "springdoc.api-docs.enabled=false",
            "springdoc.swagger-ui.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.show-sql=false",
            "logging.level.root=INFO"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(WebEndpointProperties.class)
    static class TestConfig {
    }

    @Test
    void prodLikeConfiguration_exposesOnlyHealth_neverMetrics() {
        runner.withPropertyValues(PROD_LIKE_PROPERTIES)
                .run(context -> {
                    var properties = context.getBean(WebEndpointProperties.class);
                    assertThat(properties.getExposure().getInclude()).containsExactly("health");
                    assertThat(properties.getExposure().getInclude()).doesNotContain("metrics", "*", "env", "beans");
                });
    }

    @Test
    void devLikeConfiguration_exposesHealthAndMetrics_forComparison() {
        runner
                .withPropertyValues("management.endpoints.web.exposure.include=health,metrics")
                .run(context -> {
                    var properties = context.getBean(WebEndpointProperties.class);
                    assertThat(properties.getExposure().getInclude()).containsExactlyInAnyOrder("health", "metrics");
                });
    }

    @Test
    void prodLikeConfiguration_disablesSwaggerProperties() {
        runner.withPropertyValues(PROD_LIKE_PROPERTIES)
                .run(context -> {
                    var env = context.getEnvironment();
                    assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
                    assertThat(env.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
                });
    }

    @Test
    void prodLikeConfiguration_neverExposesWildcardOrSensitiveEndpoints() {
        runner.withPropertyValues(PROD_LIKE_PROPERTIES)
                .run(context -> {
                    var properties = context.getBean(WebEndpointProperties.class);
                    var included = properties.getExposure().getInclude();
                    assertThat(included).doesNotContain("*", "env", "beans", "configprops", "heapdump", "threaddump", "mappings", "logfile");
                });
    }

    @Test
    void prodLikeConfiguration_usesValidateDdlAuto_neverUpdateOrCreateDrop() {
        runner.withPropertyValues(PROD_LIKE_PROPERTIES)
                .run(context -> {
                    var value = context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto");
                    assertThat(value).isEqualTo("validate");
                    assertThat(value).isNotIn("update", "create", "create-drop");
                });
    }
}

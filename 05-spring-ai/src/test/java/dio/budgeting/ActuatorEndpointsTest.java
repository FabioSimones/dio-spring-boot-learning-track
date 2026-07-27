package dio.budgeting;

import dio.budgeting.infrastructure.observability.AiObservability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Confirms the Actuator exposure contract (TASK-011): only health and metrics
 * are reachable - never env, beans, configprops, heapdump, or "*" - matching
 * management.endpoints.web.exposure.include=health,metrics in
 * application.properties. Uses MockMvc only, no real server, no OpenAI call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorEndpointsTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AiObservability observability;

    @Test
    void health_isExposed_andReturnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void health_neverShowsComponentDetails() throws Exception {
        var body = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"components\"").doesNotContain("\"details\"");
    }

    @Test
    void metrics_isExposed() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
    }

    @Test
    void customMetric_appearsAfterBeingIncremented() throws Exception {
        observability.recordUploadRejection("empty");

        mockMvc.perform(get("/actuator/metrics/budgeting.ai.upload.rejections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("budgeting.ai.upload.rejections"));
    }

    @Test
    void env_isNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test
    void beans_isNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }

    @Test
    void configprops_isNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/configprops")).andExpect(status().isNotFound());
    }

    @Test
    void heapdump_isNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/heapdump")).andExpect(status().isNotFound());
    }

    @Test
    void noApiKeyLeaksThroughAnyExposedEndpoint() throws Exception {
        var health = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getContentAsString();
        var metrics = mockMvc.perform(get("/actuator/metrics")).andReturn().getResponse().getContentAsString();

        assertThat(health).doesNotContain("test-key-not-a-real-credential").doesNotContain("OPENAI_API_KEY");
        assertThat(metrics).doesNotContain("test-key-not-a-real-credential").doesNotContain("OPENAI_API_KEY");
    }
}

package dio.budgeting;

import dio.budgeting.infrastructure.ai.AiTransactionProcessor;
import dio.budgeting.infrastructure.ai.AiTransactionResult;
import dio.budgeting.infrastructure.http.CorrelationIdFilter;
import dio.budgeting.infrastructure.http.audio.AudioFileValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end metrics tests for POST /transactions/ai (TASK-011): real
 * TransactionController/AudioCommandIdempotencyService/JPA-H2, real
 * MeterRegistry from the Spring context (Micrometer's default SimpleMeterRegistry,
 * auto-configured by spring-boot-starter-actuator). AiTransactionProcessor and
 * AudioFileValidator are mocked as black boxes - their own metrics are covered
 * by AiTransactionProcessorTest/AudioFileValidatorTest. No real OpenAI call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiMetricsIntegrationTest {

    @MockitoBean
    AudioFileValidator audioFileValidator;

    @MockitoBean
    AiTransactionProcessor aiTransactionProcessor;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    MockMvc mockMvc;

    private static MockMultipartFile audioFile(byte[] content) {
        return new MockMultipartFile("file", "audio.mp3", "audio/mpeg", content);
    }

    @Test
    void newOperation_recordsTotalRequestSuccess_withReplayedFalse() throws Exception {
        doNothing().when(audioFileValidator).validate(any());
        when(aiTransactionProcessor.process(any(), any()))
                .thenReturn(new AiTransactionResult(new byte[]{1, 2, 3}, "Transação registrada.", null));

        mockMvc.perform(multipart("/transactions/ai").file(audioFile(new byte[]{1}))
                        .header("Idempotency-Key", "metrics-new-001"))
                .andExpect(status().isOk());

        var timer = meterRegistry.find("budgeting.ai.requests")
                .tag("result", "success").tag("replayed", "false").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void replayedOperation_recordsTotalRequest_withReplayedTrue() throws Exception {
        doNothing().when(audioFileValidator).validate(any());
        when(aiTransactionProcessor.process(any(), any()))
                .thenReturn(new AiTransactionResult(new byte[]{1, 2, 3}, "Transação registrada.", null));
        when(aiTransactionProcessor.regenerateAudio("Transação registrada.")).thenReturn(new byte[]{9});

        byte[] content = new byte[]{7, 7, 7};
        mockMvc.perform(multipart("/transactions/ai").file(audioFile(content))
                        .header("Idempotency-Key", "metrics-replay-001"))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/transactions/ai").file(audioFile(content))
                        .header("Idempotency-Key", "metrics-replay-001"))
                .andExpect(status().isOk());

        var replayTimer = meterRegistry.find("budgeting.ai.requests")
                .tag("result", "success").tag("replayed", "true").timer();
        assertThat(replayTimer).isNotNull();
        assertThat(replayTimer.count()).isGreaterThanOrEqualTo(1);

        var replayCounter = meterRegistry.find("budgeting.ai.idempotency.replays").counter();
        assertThat(replayCounter).isNotNull();
        assertThat(replayCounter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void payloadConflict_recordsIdempotencyConflictMetric_withoutKeyOrFingerprintTag() throws Exception {
        doNothing().when(audioFileValidator).validate(any());
        when(aiTransactionProcessor.process(any(), any()))
                .thenReturn(new AiTransactionResult(new byte[]{1}, "Transação registrada.", null));

        mockMvc.perform(multipart("/transactions/ai").file(audioFile(new byte[]{1, 1, 1}))
                        .header("Idempotency-Key", "metrics-conflict-001"))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/transactions/ai").file(audioFile(new byte[]{2, 2, 2}))
                        .header("Idempotency-Key", "metrics-conflict-001"))
                .andExpect(status().isConflict());

        var counter = meterRegistry.find("budgeting.ai.idempotency.conflicts")
                .tag("reason", "payload_conflict").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
        assertThat(counter.getId().getTags()).hasSize(1);
    }

    @Test
    void correlationHeader_isReturned_onSuccessAndOnError() throws Exception {
        doNothing().when(audioFileValidator).validate(any());
        when(aiTransactionProcessor.process(any(), any()))
                .thenReturn(new AiTransactionResult(new byte[]{1}, "Transação registrada.", null));

        mockMvc.perform(multipart("/transactions/ai").file(audioFile(new byte[]{1}))
                        .header("Idempotency-Key", "metrics-corr-success")
                        .header(CorrelationIdFilter.HEADER, "my-correlation-001"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "my-correlation-001"));

        // Missing Idempotency-Key -> 400, still must carry a correlation header.
        mockMvc.perform(multipart("/transactions/ai").file(audioFile(new byte[]{1})))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
    }
}

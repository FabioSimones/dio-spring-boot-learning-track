package dio.budgeting;

import dio.budgeting.application.ListTransactionsByCategoryUseCase;
import dio.budgeting.application.PersistTransactionUseCase;
import dio.budgeting.domain.InvalidTransactionException;
import dio.budgeting.infrastructure.ai.AiIntegrationException;
import dio.budgeting.infrastructure.ai.AiTransactionProcessor;
import dio.budgeting.infrastructure.ai.OpenAiClientException;
import dio.budgeting.infrastructure.ai.OpenAiServerException;
import dio.budgeting.infrastructure.observability.AiObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AI integration boundary (TASK-008). TranscriptionModel,
 * ChatClient and TextToSpeechModel are mocked - no real OpenAI call happens.
 */
class AiTransactionProcessorTest {

    private TranscriptionModel transcriptionModel;
    private TextToSpeechModel textToSpeechModel;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponseSpec;
    private AiTransactionProcessor processor;
    private SimpleMeterRegistry meterRegistry;

    private static final Resource AUDIO = mock(Resource.class);

    @BeforeEach
    void setUp() throws Exception {
        transcriptionModel = mock(TranscriptionModel.class);
        textToSpeechModel = mock(TextToSpeechModel.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_SELF);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(chatClient);

        Resource systemPrompt = new ClassPathResource("prompts/system-message.st");
        meterRegistry = new SimpleMeterRegistry();
        processor = new AiTransactionProcessor(transcriptionModel, systemPrompt, builder, textToSpeechModel,
                mock(PersistTransactionUseCase.class), mock(ListTransactionsByCategoryUseCase.class),
                new AiObservability(meterRegistry));
    }

    // --- Success ---

    @Test
    void success_fullPipeline_returnsAudio_inOrder() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenReturn("Transação registrada com sucesso.");
        when(textToSpeechModel.call(anyString())).thenReturn(new byte[]{1, 2, 3});

        var result = processor.process(AUDIO);

        assertThat(result.audio()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(result.responseText()).isEqualTo("Transação registrada com sucesso.");
        assertThat(result.transactionId()).isNull();
        InOrder order = inOrder(transcriptionModel, chatClient, textToSpeechModel);
        order.verify(transcriptionModel).transcribe(any());
        order.verify(chatClient).prompt();
        order.verify(textToSpeechModel).call("Transação registrada com sucesso.");
    }

    // --- Transcription failures ---

    @Test
    void transcription_timeout_isClassifiedAsTimeout() {
        when(transcriptionModel.transcribe(any()))
                .thenThrow(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.TRANSCRIPTION,
                AiIntegrationException.Reason.TIMEOUT);
        verifyNoInteractions(textToSpeechModel);
    }

    @Test
    void transcription_connectionFailure_isClassifiedAsProviderUnavailable() {
        when(transcriptionModel.transcribe(any()))
                .thenThrow(new ResourceAccessException("I/O error", new ConnectException("Connection refused")));

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.TRANSCRIPTION,
                AiIntegrationException.Reason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void transcription_serverError_isClassifiedAsProviderUnavailable() {
        // Simulates OpenAiAudioTranscriptionModel's own wrapping: generic RuntimeException
        // two levels above the real classified cause.
        when(transcriptionModel.transcribe(any())).thenThrow(new RuntimeException("Error calling OpenAI transcription API",
                new RuntimeException("retry exhausted", new OpenAiServerException(503))));

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.TRANSCRIPTION,
                AiIntegrationException.Reason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void transcription_rateLimited_isClassifiedAsRateLimited() {
        when(transcriptionModel.transcribe(any())).thenThrow(new OpenAiClientException(429));

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.TRANSCRIPTION,
                AiIntegrationException.Reason.RATE_LIMITED);
    }

    @Test
    void transcription_invalidCredential_isClassifiedAsConfigurationError() {
        when(transcriptionModel.transcribe(any())).thenThrow(new OpenAiClientException(401));

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.TRANSCRIPTION,
                AiIntegrationException.Reason.CONFIGURATION_ERROR);
    }

    @Test
    void transcription_nullResult_isClassifiedAsTranscriptionEmpty() {
        when(transcriptionModel.transcribe(any())).thenReturn(null);

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.TRANSCRIPTION,
                AiIntegrationException.Reason.TRANSCRIPTION_EMPTY);
        verifyNoInteractions(chatClient);
        verifyNoInteractions(textToSpeechModel);
    }

    @Test
    void transcription_blankResult_isClassifiedAsTranscriptionEmpty() {
        when(transcriptionModel.transcribe(any())).thenReturn("   ");

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.TRANSCRIPTION,
                AiIntegrationException.Reason.TRANSCRIPTION_EMPTY);
        verifyNoInteractions(chatClient);
    }

    // --- ChatClient failures ---

    @Test
    void chat_timeout_isClassifiedAsTimeout() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content())
                .thenThrow(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.CHAT,
                AiIntegrationException.Reason.TIMEOUT);
        verifyNoInteractions(textToSpeechModel);
    }

    @Test
    void chat_providerUnavailable_isClassifiedAsProviderUnavailable() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenThrow(new OpenAiServerException(503));

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.CHAT,
                AiIntegrationException.Reason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void chat_nullContent_isClassifiedAsInvalidProviderResponse() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenReturn(null);

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.CHAT,
                AiIntegrationException.Reason.INVALID_PROVIDER_RESPONSE);
        verifyNoInteractions(textToSpeechModel);
    }

    @Test
    void chat_blankContent_isClassifiedAsInvalidProviderResponse() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenReturn("   ");

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.CHAT,
                AiIntegrationException.Reason.INVALID_PROVIDER_RESPONSE);
    }

    @Test
    void chat_domainException_propagatesWithoutBeingConvertedToAiFailure() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei -50 reais no mercado");
        when(callResponseSpec.content())
                .thenThrow(new InvalidTransactionException("O valor da transação deve ser maior que zero."));

        assertThatThrownBy(() -> processor.process(AUDIO))
                .isInstanceOf(InvalidTransactionException.class)
                .isNotInstanceOf(AiIntegrationException.class);
        verifyNoInteractions(textToSpeechModel);
    }

    // --- TTS failures ---

    @Test
    void speech_timeout_isClassifiedAsTimeout() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenReturn("Transação registrada.");
        when(textToSpeechModel.call(anyString()))
                .thenThrow(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.SPEECH,
                AiIntegrationException.Reason.TIMEOUT);
    }

    @Test
    void speech_providerUnavailable_isClassifiedAsProviderUnavailable() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenReturn("Transação registrada.");
        when(textToSpeechModel.call(anyString())).thenThrow(new OpenAiServerException(500));

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.SPEECH,
                AiIntegrationException.Reason.PROVIDER_UNAVAILABLE);
    }

    @Test
    void speech_nullAudio_isClassifiedAsInvalidProviderResponse() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenReturn("Transação registrada.");
        when(textToSpeechModel.call(anyString())).thenReturn(null);

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.SPEECH,
                AiIntegrationException.Reason.INVALID_PROVIDER_RESPONSE);
    }

    @Test
    void speech_emptyAudio_isClassifiedAsInvalidProviderResponse() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenReturn("Transação registrada.");
        when(textToSpeechModel.call(anyString())).thenReturn(new byte[0]);

        assertFailure(() -> processor.process(AUDIO), AiIntegrationException.Stage.SPEECH,
                AiIntegrationException.Reason.INVALID_PROVIDER_RESPONSE);
    }

    private static void assertFailure(Runnable action, AiIntegrationException.Stage stage,
                                       AiIntegrationException.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AiIntegrationException.class)
                .satisfies(ex -> {
                    var aiEx = (AiIntegrationException) ex;
                    assertThat(aiEx.getStage()).isEqualTo(stage);
                    assertThat(aiEx.getReason()).isEqualTo(reason);
                });
    }

    // --- TASK-011: stage metrics (SimpleMeterRegistry, no Spring context) ---

    @Test
    void success_recordsAllThreeStageTimers_asSuccess_noFailureCounted() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenReturn("Transação registrada com sucesso.");
        when(textToSpeechModel.call(anyString())).thenReturn(new byte[]{1, 2, 3});

        processor.process(AUDIO);

        assertStageTimerCount("transcription", "success", 1);
        assertStageTimerCount("chat", "success", 1);
        assertStageTimerCount("speech", "success", 1);
        assertThat(meterRegistry.find("budgeting.ai.failures").counters()).isEmpty();
    }

    @Test
    void transcriptionFailure_recordsTranscriptionFailureTimerAndCounter_neverMeasuresChatOrSpeech() {
        when(transcriptionModel.transcribe(any()))
                .thenThrow(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> processor.process(AUDIO)).isInstanceOf(AiIntegrationException.class);

        assertStageTimerCount("transcription", "failure", 1);
        assertThat(meterRegistry.find("budgeting.ai.stage.duration").tag("stage", "chat").timers()).isEmpty();
        assertThat(meterRegistry.find("budgeting.ai.stage.duration").tag("stage", "speech").timers()).isEmpty();
        assertFailureCounter("transcription", "timeout", 1);
    }

    @Test
    void chatFailure_transcriptionSucceeds_chatFails_speechNeverMeasured() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenThrow(new OpenAiServerException(503));

        assertThatThrownBy(() -> processor.process(AUDIO)).isInstanceOf(AiIntegrationException.class);

        assertStageTimerCount("transcription", "success", 1);
        assertStageTimerCount("chat", "failure", 1);
        assertThat(meterRegistry.find("budgeting.ai.stage.duration").tag("stage", "speech").timers()).isEmpty();
        assertFailureCounter("chat", "provider_unavailable", 1);
    }

    @Test
    void speechFailure_transcriptionAndChatSucceed_speechFails() {
        when(transcriptionModel.transcribe(any())).thenReturn("Gastei 50 reais no mercado");
        when(callResponseSpec.content()).thenReturn("Transação registrada.");
        when(textToSpeechModel.call(anyString())).thenThrow(new OpenAiServerException(500));

        assertThatThrownBy(() -> processor.process(AUDIO)).isInstanceOf(AiIntegrationException.class);

        assertStageTimerCount("transcription", "success", 1);
        assertStageTimerCount("chat", "success", 1);
        assertStageTimerCount("speech", "failure", 1);
        assertFailureCounter("speech", "provider_unavailable", 1);
    }

    @Test
    void emptyTranscription_recordsTranscriptionEmptyReason_noDynamicMessageTag() {
        when(transcriptionModel.transcribe(any())).thenReturn("   ");

        assertThatThrownBy(() -> processor.process(AUDIO)).isInstanceOf(AiIntegrationException.class);

        assertFailureCounter("transcription", "transcription_empty", 1);
        // Only the controlled stage/reason tags exist - no message/exception-derived tag leaked in.
        var counter = meterRegistry.find("budgeting.ai.failures")
                .tag("stage", "transcription").tag("reason", "transcription_empty").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.getId().getTags()).hasSize(2);
    }

    private void assertStageTimerCount(String stage, String result, int expectedCount) {
        var timer = meterRegistry.find("budgeting.ai.stage.duration").tag("stage", stage).tag("result", result).timer();
        assertThat(timer).as("timer for stage=%s result=%s", stage, result).isNotNull();
        assertThat(timer.count()).isEqualTo(expectedCount);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThanOrEqualTo(0);
    }

    private void assertFailureCounter(String stage, String reason, double expectedCount) {
        var counter = meterRegistry.find("budgeting.ai.failures").tag("stage", stage).tag("reason", reason).counter();
        assertThat(counter).as("failure counter for stage=%s reason=%s", stage, reason).isNotNull();
        assertThat(counter.count()).isEqualTo(expectedCount);
    }
}

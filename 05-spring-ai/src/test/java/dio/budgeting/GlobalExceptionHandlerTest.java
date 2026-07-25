package dio.budgeting;

import dio.budgeting.application.PersistTransactionUseCase;
import dio.budgeting.application.ListTransactionsByCategoryUseCase;
import dio.budgeting.domain.InvalidTransactionException;
import dio.budgeting.domain.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the standardized ProblemDetail contract produced by
 * {@link dio.budgeting.infrastructure.http.error.GlobalExceptionHandler}.
 * Same isolation strategy as the other MockMvc tests in this module: no
 * MySQL/Docker, TransactionRepository mocked, placeholder OpenAI API key,
 * no real call to TranscriptionModel/ChatClient/TextToSpeechModel.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.ai.openai.api-key=test-key-not-a-real-credential",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
        }
)
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @MockitoBean
    TransactionRepository transactionRepository;

    @MockitoBean
    PersistTransactionUseCase persistTransactionUseCase;

    @MockitoBean
    ListTransactionsByCategoryUseCase listTransactionsByCategoryUseCase;

    @MockitoBean
    TranscriptionModel transcriptionModel;

    @MockitoBean
    TextToSpeechModel textToSpeechModel;

    @Autowired
    MockMvc mockMvc;

    // --- InvalidTransactionException (domain) ---

    @Test
    void invalidTransactionException_isTranslatedTo400WithStandardContract() throws Exception {
        when(persistTransactionUseCase.execute(any()))
                .thenThrow(new InvalidTransactionException("O valor da transação deve ser maior que zero."));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Combustível",
                                  "amount": 80.90,
                                  "category": "AUTO"
                                }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Transação inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("O valor da transação deve ser maior que zero."))
                .andExpect(jsonPath("$.instance").value("/transactions"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void errorResponseBody_neverContainsStackTraceOrClassNames() throws Exception {
        when(persistTransactionUseCase.execute(any()))
                .thenThrow(new InvalidTransactionException("A categoria da transação é obrigatória."));

        var body = mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Combustível",
                                  "amount": 80.90,
                                  "category": "AUTO"
                                }"""))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("at dio.budgeting")
                .doesNotContain("InvalidTransactionException");
    }

    // --- Bean Validation ---

    @Test
    void beanValidationFailure_returnsAllFieldErrors_sortedByField() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "",
                                  "amount": 0,
                                  "category": null
                                }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.detail").value("Um ou mais campos possuem valores inválidos."))
                .andExpect(jsonPath("$.instance").value("/transactions"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[1].field").value("category"))
                .andExpect(jsonPath("$.errors[2].field").value("description"));

        verifyNoInteractions(persistTransactionUseCase);
    }

    // --- JSON / enum ---

    @Test
    void malformedJson_returns400WithGenericMessage() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Combustível",
                                  "amount": 80.90,
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.detail").value("O corpo da requisição está ausente ou possui formato inválido."));

        verifyNoInteractions(persistTransactionUseCase);
    }

    @Test
    void nonNumericAmount_returns400WithGenericMessage() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Combustível",
                                  "amount": "oitenta reais",
                                  "category": "AUTO"
                                }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requisição inválida"));

        verifyNoInteractions(persistTransactionUseCase);
    }

    @Test
    void invalidCategoryInBody_returns400WithAcceptedValues() throws Exception {
        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Combustível",
                                  "amount": 80.90,
                                  "category": "FOOD"
                                }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.detail").value("Categoria inválida. Valores aceitos: GROCERIES, PHARMA, AUTO."));

        verifyNoInteractions(persistTransactionUseCase);
    }

    @Test
    void invalidCategoryInRoute_returns400WithAcceptedValues() throws Exception {
        mockMvc.perform(get("/transactions/FOOD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Parâmetro inválido"))
                .andExpect(jsonPath("$.detail").value(
                        "O valor 'FOOD' não é válido para o parâmetro 'category'. Valores aceitos: GROCERIES, PHARMA, AUTO."))
                .andExpect(jsonPath("$.instance").value("/transactions/FOOD"));

        verifyNoInteractions(listTransactionsByCategoryUseCase);
    }

    // --- Multipart ---

    @Test
    void missingAudioFile_returns400_andNeverCallsAiPipeline() throws Exception {
        mockMvc.perform(multipart("/transactions/ai"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Arquivo obrigatório"))
                .andExpect(jsonPath("$.detail").value("O arquivo de áudio é obrigatório."))
                .andExpect(jsonPath("$.instance").value("/transactions/ai"));

        verifyNoInteractions(transcriptionModel);
        verifyNoInteractions(textToSpeechModel);
    }

    // --- Unexpected error ---

    @Test
    void unexpectedException_returns500_withoutLeakingInternalDetails() throws Exception {
        when(persistTransactionUseCase.execute(any()))
                .thenThrow(new RuntimeException("detalhe interno sensível"));

        var body = mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Combustível",
                                  "amount": 80.90,
                                  "category": "AUTO"
                                }"""))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Erro interno"))
                .andExpect(jsonPath("$.detail").value("Ocorreu um erro inesperado ao processar a requisição."))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("detalhe interno sensível")
                .doesNotContain("RuntimeException");
    }
}

package dio.budgeting.infrastructure.http;

import dio.budgeting.application.ListTransactionsByCategoryUseCase;
import dio.budgeting.application.PersistTransactionUseCase;
import dio.budgeting.domain.Category;
import dio.budgeting.infrastructure.http.audio.AudioFileValidator;
import dio.budgeting.infrastructure.http.error.ApiErrorResponse;
import dio.budgeting.infrastructure.http.request.TransactionRequest;
import dio.budgeting.infrastructure.http.response.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

@RestController
@RequestMapping("/transactions")
@Tag(name = "Transactions", description = "Cadastro e consulta de transações financeiras, via REST tradicional ou comando de voz (transcrição + IA + Tool Calling + texto-para-voz)")
public class TransactionController {
    private final PersistTransactionUseCase persistTransactionUseCase;
    private final ListTransactionsByCategoryUseCase listTransactionsByCategoryUseCase;

    private final TranscriptionModel transcriptionModel;
    private final ChatClient chatClient;
    private final TextToSpeechModel textToSpeechModel;
    private final AudioFileValidator audioFileValidator;

    public TransactionController(PersistTransactionUseCase persistTransactionUseCase,
                                 ListTransactionsByCategoryUseCase listTransactionsByCategoryUseCase,
                                 TranscriptionModel transcriptionModel,
                                 @Value("classpath:prompts/system-message.st") Resource systemPrompt,
                                 ChatClient.Builder chatClientBuilder,
                                 TextToSpeechModel textToSpeechModel,
                                 AudioFileValidator audioFileValidator) throws IOException {
        this.persistTransactionUseCase = persistTransactionUseCase;
        this.listTransactionsByCategoryUseCase = listTransactionsByCategoryUseCase;
        this.transcriptionModel = transcriptionModel;
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt.getContentAsString(Charset.defaultCharset()))
                .defaultTools(persistTransactionUseCase, listTransactionsByCategoryUseCase)
                .build();
        this.textToSpeechModel = textToSpeechModel;
        this.audioFileValidator = audioFileValidator;
    }

    @Operation(
            summary = "Cadastra uma transação financeira",
            description = "Persiste uma nova transação a partir de um payload JSON, sem transcrição de áudio nem IA."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Transação criada com sucesso",
                    content = @Content(
                            schema = @Schema(implementation = TransactionResponse.class),
                            examples = @ExampleObject(
                                    name = "Combustível",
                                    value = """
                                            {
                                              "id": "3f1c9e2a-2b8b-4e0f-8b7a-1f2e3d4c5b6a",
                                              "category": "AUTO",
                                              "description": "Combustível",
                                              "amount": 80.90
                                            }"""
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Payload inválido: descrição vazia, valor nulo/zero/negativo, categoria nula ou "
                            + "inexistente, ou JSON malformado.",
                    content = @Content(
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "Dados inválidos",
                                    value = """
                                            {
                                              "type": "about:blank",
                                              "title": "Dados inválidos",
                                              "status": 400,
                                              "detail": "Um ou mais campos possuem valores inválidos.",
                                              "instance": "/transactions",
                                              "timestamp": "2026-07-24T18:00:00-03:00",
                                              "errors": [
                                                {
                                                  "field": "amount",
                                                  "message": "O valor da transação deve ser maior que zero."
                                                }
                                              ]
                                            }"""
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Erro inesperado ao processar a requisição.",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(@Valid @RequestBody TransactionRequest request) {
        var transaction = persistTransactionUseCase.execute(request.toInput());
        return TransactionResponse.from(transaction);
    }

    @Operation(
            summary = "Lista transações por categoria",
            description = "Retorna todas as transações persistidas para a categoria informada. Retorna lista vazia quando não há registros."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Lista de transações da categoria (pode ser vazia)",
                    content = @Content(schema = @Schema(implementation = TransactionResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Categoria inexistente no enum atual.",
                    content = @Content(
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "Parâmetro inválido",
                                    value = """
                                            {
                                              "type": "about:blank",
                                              "title": "Parâmetro inválido",
                                              "status": 400,
                                              "detail": "O valor 'FOOD' não é válido para o parâmetro 'category'. Valores aceitos: GROCERIES, PHARMA, AUTO.",
                                              "instance": "/transactions/FOOD",
                                              "timestamp": "2026-07-24T18:00:00-03:00"
                                            }"""
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Erro inesperado ao processar a requisição.",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    @GetMapping("/{category}")
    public List<TransactionResponse> readTransactions(
            @Parameter(description = "Categoria da transação, conforme o enum atual do domínio", required = true)
            @PathVariable Category category) {
        return listTransactionsByCategoryUseCase.execute(category).stream().map(TransactionResponse::from).toList();
    }

    @Operation(
            summary = "Registra uma transação por comando de voz",
            description = """
                    Fluxo completo de IA: recebe um áudio, transcreve com o modelo de transcrição da OpenAI (whisper-1), \
                    envia o texto transcrito ao ChatClient (gpt-4o-mini), que pode acionar as ferramentas \
                    "persist-transaction" ou "list-transactions-by-category" via Tool Calling, e converte a resposta \
                    final em áudio MP3 (gpt-4o-mini-tts) para retorno ao cliente.

                    Requer a variável de ambiente OPENAI_API_KEY configurada no servidor. Cada chamada a este \
                    endpoint realiza 3 chamadas reais e potencialmente pagas à API da OpenAI (transcrição, chat e \
                    geração de voz).

                    O arquivo é validado localmente (tamanho máximo de 10 MB e content type entre os aceitos) antes \
                    de qualquer chamada à OpenAI: um arquivo inválido nunca gera custo."""
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Áudio MP3 com a resposta do assistente financeiro",
                    content = @Content(mediaType = "audio/mp3", schema = @Schema(type = "string", format = "binary"))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Parte 'file' ausente, requisição não multipart, arquivo vazio ou content type "
                            + "não permitido.",
                    content = @Content(
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "Arquivo de áudio inválido",
                                    value = """
                                            {
                                              "type": "about:blank",
                                              "title": "Arquivo de áudio inválido",
                                              "status": 400,
                                              "detail": "O tipo do arquivo de áudio não é permitido.",
                                              "instance": "/transactions/ai",
                                              "timestamp": "2026-07-24T18:00:00-03:00"
                                            }"""
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "413",
                    description = "Arquivo acima do tamanho máximo permitido (10 MB).",
                    content = @Content(
                            schema = @Schema(implementation = ApiErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "Arquivo muito grande",
                                    value = """
                                            {
                                              "type": "about:blank",
                                              "title": "Arquivo muito grande",
                                              "status": 413,
                                              "detail": "O arquivo enviado excede o tamanho máximo permitido.",
                                              "instance": "/transactions/ai",
                                              "timestamp": "2026-07-24T18:00:00-03:00"
                                            }"""
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Erro inesperado ao processar a requisição (transcrição, chat ou geração de voz).",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
            )
    })
    @PostMapping(value = "/ai", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "audio/mp3")
    ResponseEntity<Resource> transcribe(
            @Parameter(
                    description = "Arquivo de áudio com o comando financeiro falado. Obrigatório, até 10 MB, "
                            + "content type entre: audio/mpeg, audio/mp3, audio/mp4, audio/m4a, audio/x-m4a, "
                            + "audio/wav, audio/x-wav, audio/webm. Validado localmente antes de qualquer chamada à "
                            + "OpenAI. Os arquivos de teste do projeto usam .m4a; a validação é feita pelo header "
                            + "content type declarado pelo cliente, não pela inspeção do conteúdo binário.",
                    required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE)
            )
            @RequestParam("file") MultipartFile file) {
        audioFileValidator.validate(file);

        var userMessage = transcriptionModel.transcribe(file.getResource());
        var result = chatClient.prompt().user(userMessage).call().content();

        byte[] audio = textToSpeechModel.call(result);
        var resource = new ByteArrayResource(audio);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("audio.mp3")
                                .build()
                                .toString())
                .body(resource);
    }
}

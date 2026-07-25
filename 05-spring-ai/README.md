# DIO Spring Boot - Final Project 05: Spring AI (budgeting)

## Introduction

This final module applies Spring AI in a budgeting API while preserving the same layered architecture used across the track.

The goal is to integrate AI capabilities without bypassing domain and use case boundaries.

## Code Context

The project processes voice commands to create and query financial transactions.

Primary flow:

1. Client uploads an audio file.
2. Audio is transcribed into text.
3. The model selects an application tool/use case.
4. The use case persists or queries transaction data.
5. The final response is converted to audio.

## Project Structure

- `src/main/java/dio/budgeting/domain`
  - Domain model and repository contract.
- `src/main/java/dio/budgeting/application`
  - Use cases used by both REST and AI tool calling.
- `src/main/java/dio/budgeting/infrastructure`
  - HTTP adapters, JPA adapters, and integration glue.

## Module-Specific Topics

### Speech-to-text

- Uses `TranscriptionModel` for audio transcription.
- Model settings are configured in `application.properties`.

### Tool calling

- `ChatClient` registers use-case tools.
- `@Tool` methods expose business capabilities to the model.

### Text-to-speech

- `TextToSpeechModel` produces MP3 output from final text.
- AI endpoint returns generated audio.

## Spring AI Documentation

- Spring AI Reference: https://docs.spring.io/spring-ai/reference/index.html
- ChatModel API: https://docs.spring.io/spring-ai/reference/api/chatmodel.html
- ChatClient API: https://docs.spring.io/spring-ai/reference/api/chatclient.html
- Tools API: https://docs.spring.io/spring-ai/reference/api/tools.html
- Audio Transcriptions API: https://docs.spring.io/spring-ai/reference/api/audio/transcriptions.html
- Audio Speech API: https://docs.spring.io/spring-ai/reference/api/audio/speech.html

## Shared Architecture References

Common architecture concepts are documented in the root README:

- [DDD layers](../README.md#ddd-layered-architecture)
- [Class vs record](../README.md#java-class-vs-java-record-in-domain-modeling)
- [Strong typed identifiers](../README.md#strong-typed-identifiers)
- [Repository pattern](../README.md#repository-pattern)
- [Use cases and Clean Architecture](../README.md#use-cases-and-clean-architecture)
- [Docker Compose support](../README.md#docker-compose-support-in-development)

## How to Run

Set your OpenAI API key:

```bash
export OPENAI_API_KEY="your_api_key_here"
```

Run the application and tests:

```bash
./gradlew bootRun
./gradlew test
```

## Documentação da API

O projeto expõe documentação interativa via [springdoc-openapi](https://springdoc.org/) (OpenAPI 3 + Swagger UI).

- Finalidade: visualizar e testar manualmente os três endpoints existentes (`POST /transactions`, `GET /transactions/{category}`, `POST /transactions/ai`) direto pelo navegador, sem precisar de um cliente HTTP externo.
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- JSON OpenAPI: `http://localhost:8080/v3/api-docs`

Para iniciar a aplicação (necessário para acessar o Swagger UI):

```bash
export OPENAI_API_KEY="your_api_key_here"
./gradlew bootRun
```

Descrição resumida dos endpoints:

- `POST /transactions` — cadastra uma transação a partir de um payload JSON (sem IA).
- `GET /transactions/{category}` — lista transações de uma categoria (`GROCERIES`, `PHARMA`, `AUTO`).
- `POST /transactions/ai` — recebe um áudio (`multipart/form-data`), transcreve, processa via ChatClient/Tool Calling e retorna a resposta como áudio MP3.

**Atenção**:

- O endpoint `/transactions/ai` exige a variável de ambiente `OPENAI_API_KEY` configurada no servidor.
- Cada chamada a esse endpoint realiza chamadas reais à API da OpenAI (transcrição, chat e geração de voz) e **pode gerar custo**. Não dispare esse endpoint pelo Swagger UI apenas para "testar a interface".
- Iniciar a aplicação (`bootRun`) sozinho **não** chama a OpenAI — as chamadas só ocorrem quando `/transactions/ai` é invocado.

Exemplo de uso do Swagger UI:

1. Acesse `http://localhost:8080/swagger-ui/index.html`.
2. Expanda `POST /transactions`, clique em "Try it out" e envie o exemplo de payload já preenchido.
3. Expanda `GET /transactions/{category}` e selecione uma categoria no seletor do enum.
4. Para `POST /transactions/ai`, o Swagger UI exibe um seletor de arquivo — evite enviar um áudio real a menos que você aceite o custo de uma chamada real à OpenAI.

## Upload de áudio

`POST /transactions/ai` recebe o comando de voz como `multipart/form-data`, na parte obrigatória `file`.

Antes de qualquer chamada à OpenAI (transcrição, ChatClient, geração de voz), o arquivo é validado localmente por `AudioFileValidator` (`dio.budgeting.infrastructure.http.audio`):

1. arquivo presente e não vazio;
2. tamanho dentro do limite (`app.audio.max-size`, padrão **10 MB**);
3. `content type` presente;
4. `content type` entre os aceitos: `audio/mpeg`, `audio/mp3`, `audio/mp4`, `audio/m4a`, `audio/x-m4a`, `audio/wav`, `audio/x-wav`, `audio/webm`.

Um arquivo que falhe em qualquer uma dessas regras é rejeitado **sem gerar custo**: nenhuma chamada a `TranscriptionModel`, `ChatClient` ou `TextToSpeechModel` ocorre para um upload inválido.

Exemplo de requisição válida (adaptar caminho do arquivo):

```bash
curl -X POST \
  http://localhost:8080/transactions/ai \
  -H "Content-Type: multipart/form-data" \
  -F "file=@comando.mp3;type=audio/mpeg"
```

**Erros possíveis**:

| Situação | Status | Título |
| -------- | -----: | ------ |
| Arquivo vazio, sem `content type` ou tipo não permitido | 400 | Arquivo de áudio inválido |
| Parte `file` ausente | 400 | Arquivo obrigatório |
| Requisição não multipart / multipart malformado | 400 | Requisição inválida |
| Arquivo acima de 10 MB (validado localmente ou rejeitado pelo servidor) | 413 | Arquivo muito grande |

**Atenção**: esta validação verifica apenas o `content type` declarado pelo cliente, não o conteúdo binário do arquivo — um arquivo renomeado (ex.: `.exe` enviado como `audio/mpeg`) não é detectado. Uploads que passam na validação ainda geram chamadas reais e potencialmente pagas à API da OpenAI.

## Monetary Values

- `amount` is represented as `BigDecimal`, in **reais**, always normalized to **two decimal places** (`RoundingMode.HALF_UP`), from the domain (`Transaction`) through persistence (`DECIMAL(19,2)`) and both REST/Tool Calling responses.
- Example request: `{"description": "Combustível", "amount": 80.90, "category": "AUTO"}` → response: `{"id": "...", "category": "AUTO", "description": "Combustível", "amount": 80.90}`.
- Values are rounded, not truncated, when they carry more than two decimal places (e.g. `80.905` becomes `80.91`); the Swagger schema documents `amount` as a decimal number.
- **Local database note**: if a local MySQL instance was already running with the previous `BIGINT` column, the schema won't be auto-migrated safely by Hibernate (`ddl-auto=update` does not convert `BIGINT` to `DECIMAL`). Recreate the local dev database (or manually `ALTER TABLE`) before running against a pre-existing schema.

## Validations

Business invariants are enforced centrally in the domain (`Transaction`), so both REST and Tool Calling go through the same protection — the rules do not depend on controller/DTO annotations alone:

- **Description**: required, cannot be null/empty/blank; leading and trailing spaces are stripped (`"  Combustível  "` is stored as `"Combustível"`).
- **Amount**: required, must be greater than zero **after** normalization to two decimals (`HALF_UP`) — e.g. `0.004` rounds to `0.00` and is rejected, while `0.005` rounds to `0.01` and is accepted.
- **Category**: required, restricted to the current enum values (`GROCERIES`, `PHARMA`, `AUTO`).

Invalid input throws `InvalidTransactionException` (a domain exception, no HTTP concept involved) before any repository call. Bean Validation annotations (`@NotBlank`, `@NotNull`, `@DecimalMin`) were added to `TransactionRequest` as a first HTTP-level barrier, causing `POST /transactions` to reject invalid payloads with `400 Bad Request` — but this is a convenience layer, not the source of truth.

Valid request example:
```json
{"description": "Combustível", "amount": 80.90, "category": "AUTO"}
```
Invalid request examples (all rejected):
```json
{"description": "", "amount": 80.90, "category": "AUTO"}
{"description": "Combustível", "amount": 0.00, "category": "AUTO"}
{"description": "Combustível", "amount": -10.00, "category": "AUTO"}
```

## Tratamento de erros

Todos os endpoints REST retornam erros em um contrato único e previsível, baseado no padrão nativo do Spring (`ProblemDetail`, RFC 9457), implementado por um `@RestControllerAdvice` (`GlobalExceptionHandler`) na camada de infraestrutura HTTP. A exceção de domínio (`InvalidTransactionException`) não conhece HTTP: quem traduz negócio em status code é sempre o handler global.

**Campos comuns**: `type`, `title`, `status`, `detail`, `instance` (caminho da requisição), `timestamp` (ISO 8601). A propriedade `errors` (lista de `{field, message}`) só aparece quando há falhas de validação de campo.

### Erros de validação (Bean Validation)

`POST /transactions` com payload inválido retorna todos os erros de campo, ordenados por nome do campo:

```json
{
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos possuem valores inválidos.",
  "instance": "/transactions",
  "errors": [
    {
      "field": "amount",
      "message": "O valor da transação deve ser maior que zero."
    }
  ]
}
```

### Categoria inválida

- No corpo (`POST /transactions`), quando `category` não corresponde a um valor do enum, ou na rota (`GET /transactions/{category}`), a resposta indica os valores aceitos, por exemplo: `"Categoria inválida. Valores aceitos: GROCERIES, PHARMA, AUTO."`.

### JSON inválido

- JSON malformado ou tipos incompatíveis (ex.: `amount` como texto) retornam `400` com título `Requisição inválida` e uma mensagem genérica e segura — sem nomes de classes internas, caminhos ou detalhes do Jackson.

### Upload de áudio (`POST /transactions/ai`)

- Parte `file` ausente retorna `400` com título `Arquivo obrigatório`.
- Requisição não multipart ou malformada retorna `400` com título `Requisição inválida`.
- Arquivo vazio, sem `content type` ou com tipo não permitido retorna `400` com título `Arquivo de áudio inválido` (ver [seção de upload de áudio](#upload-de-áudio) para a lista completa de regras).
- Arquivo acima do tamanho máximo (validado localmente ou rejeitado pelo servidor via `MaxUploadSizeExceededException`) retorna `413` com título `Arquivo muito grande`.
- Em todos os casos acima, nenhuma chamada a transcrição, ChatClient ou geração de voz ocorre.

### Erro interno

- Falhas inesperadas retornam `500` com título `Erro interno` e mensagem genérica; o erro completo é registrado no servidor via SLF4J, nunca exposto ao cliente. Stack traces e mensagens técnicas nunca aparecem no corpo da resposta.

## Notes

- Educational final project focused on AI plus architectural discipline.
- External provider integration tests may require active credentials.

package dio.budgeting.infrastructure.http.error;

import tools.jackson.databind.exc.InvalidFormatException;
import dio.budgeting.domain.Category;
import dio.budgeting.domain.InvalidTransactionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidTransactionException.class)
    public ProblemDetail handleInvalidTransaction(InvalidTransactionException ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.BAD_REQUEST, "Transação inválida", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldValidationError(fieldError.getField(), fieldError.getDefaultMessage()))
                .distinct()
                .sorted(Comparator.comparing(FieldValidationError::field))
                .toList();

        var problem = problemDetail(HttpStatus.BAD_REQUEST, "Dados inválidos",
                "Um ou mais campos possuem valores inválidos.", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        var detail = "O corpo da requisição está ausente ou possui formato inválido.";
        if (invalidCategoryCause(ex) != null) {
            detail = "Categoria inválida. Valores aceitos: %s.".formatted(acceptedCategories());
        }
        return problemDetail(HttpStatus.BAD_REQUEST, "Requisição inválida", detail, request);
    }

    private static InvalidFormatException invalidCategoryCause(Throwable ex) {
        for (var cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidFormatException invalidFormat && invalidFormat.getTargetType() == Category.class) {
                return invalidFormat;
            }
        }
        return null;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String detail;
        if (ex.getRequiredType() == Category.class) {
            detail = "O valor '%s' não é válido para o parâmetro '%s'. Valores aceitos: %s."
                    .formatted(ex.getValue(), ex.getName(), acceptedCategories());
        } else {
            detail = "O parâmetro '%s' possui um valor inválido.".formatted(ex.getName());
        }
        return problemDetail(HttpStatus.BAD_REQUEST, "Parâmetro inválido", detail, request);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ProblemDetail handleMissingFile(Exception ex, HttpServletRequest request) {
        return problemDetail(HttpStatus.BAD_REQUEST, "Arquivo obrigatório",
                "O arquivo de áudio é obrigatório.", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Erro inesperado ao processar requisição {}", request.getRequestURI(), ex);
        return problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado ao processar a requisição.", request);
    }

    private static String acceptedCategories() {
        return Arrays.stream(Category.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    private static ProblemDetail problemDetail(HttpStatus status, String title, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", OffsetDateTime.now());
        return problem;
    }
}

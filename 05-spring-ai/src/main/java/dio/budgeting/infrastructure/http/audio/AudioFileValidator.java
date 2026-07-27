package dio.budgeting.infrastructure.http.audio;

import dio.budgeting.infrastructure.observability.AiObservability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/**
 * Rejects unusable audio uploads locally, before any call to
 * TranscriptionModel/ChatClient/TextToSpeechModel. Content-type matching is a
 * declared-header check, not content inspection: a renamed file (e.g.
 * {@code arquivo.exe} sent as {@code audio/mpeg}) is not detected here.
 *
 * <p>Each rejection is counted once here via {@link AiObservability} (TASK-011)
 * - {@code GlobalExceptionHandler} counts the two upload-rejection reasons that
 * never reach this class (missing multipart part, malformed multipart), so no
 * reason is ever counted twice.
 */
@Component
public class AudioFileValidator {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "audio/mpeg", "audio/mp3",
            "audio/mp4",
            "audio/m4a", "audio/x-m4a",
            "audio/wav", "audio/x-wav",
            "audio/webm"
    );

    private final DataSize maxSize;
    private final AiObservability observability;

    public AudioFileValidator(@Value("${app.audio.max-size}") String maxSize, AiObservability observability) {
        this.maxSize = DataSize.parse(maxSize);
        this.observability = observability;
    }

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            observability.recordUploadRejection("empty");
            throw new InvalidAudioFileException(InvalidAudioFileException.Reason.EMPTY,
                    "O arquivo de áudio não pode estar vazio.");
        }
        if (file.getSize() > maxSize.toBytes()) {
            observability.recordUploadRejection("too_large");
            throw new InvalidAudioFileException(InvalidAudioFileException.Reason.TOO_LARGE,
                    "O arquivo de áudio excede o tamanho máximo permitido.");
        }
        var contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            observability.recordUploadRejection("missing_content_type");
            throw new InvalidAudioFileException(InvalidAudioFileException.Reason.MISSING_CONTENT_TYPE,
                    "Não foi possível identificar o tipo do arquivo de áudio.");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            observability.recordUploadRejection("unsupported_type");
            throw new InvalidAudioFileException(InvalidAudioFileException.Reason.UNSUPPORTED_TYPE,
                    "O tipo do arquivo de áudio não é permitido.");
        }
    }
}

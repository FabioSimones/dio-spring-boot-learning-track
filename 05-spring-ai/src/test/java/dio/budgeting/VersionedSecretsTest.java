package dio.budgeting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-012: a lightweight static check (not a full security scanner) over the
 * module's own versioned configuration/documentation files, confirming no
 * real-looking secret ever made it into git. Known-safe placeholders/fictitious
 * values (documented as such) are the only accepted matches.
 */
class VersionedSecretsTest {

    private static final Path MODULE_ROOT = Path.of("").toAbsolutePath();

    private static final List<Path> SCANNED_FILES = List.of(
            MODULE_ROOT.resolve("src/main/resources/application.properties"),
            MODULE_ROOT.resolve("src/main/resources/application-dev.properties"),
            MODULE_ROOT.resolve("src/main/resources/application-prod.properties.example"),
            MODULE_ROOT.resolve("src/test/resources/application-test.properties"),
            MODULE_ROOT.resolve(".env.example"),
            MODULE_ROOT.resolve("README.md")
    );

    // A real OpenAI secret key shape - "sk-" followed by 20+ alphanumeric chars.
    // Deliberately does NOT match short illustrative strings like "sk-fake-not-real".
    private static final Pattern REAL_LOOKING_OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9]{20,}");

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void allExpectedFilesExist() {
        SCANNED_FILES.forEach(path -> assertThat(path).as("expected file to exist: %s", path).exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/resources/application.properties",
            "src/main/resources/application-dev.properties",
            "src/main/resources/application-prod.properties.example",
            "src/test/resources/application-test.properties",
            ".env.example"
    })
    void configurationFile_neverContainsARealLookingOpenAiKey(String relativePath) {
        String content = readFile(MODULE_ROOT.resolve(relativePath));
        assertThat(REAL_LOOKING_OPENAI_KEY.matcher(content).find())
                .as("file %s must never contain a real-looking OpenAI key", relativePath)
                .isFalse();
    }

    @Test
    void applicationProperties_hasNoApiKeyAndNoDatasourceCredential() {
        String content = readFile(MODULE_ROOT.resolve("src/main/resources/application.properties"));

        assertThat(content).doesNotContain("spring.ai.openai.api-key=");
        assertThat(content).doesNotContain("spring.datasource.password=");
        assertThat(content).doesNotContain("spring.profiles.active=");
    }

    @Test
    void devProperties_apiKeyAndPasswordAreBarePlaceholders_noDefaultValue() {
        String content = readFile(MODULE_ROOT.resolve("src/main/resources/application-dev.properties"));

        assertThat(content).contains("spring.ai.openai.api-key=${OPENAI_API_KEY}");
        assertThat(content).contains("spring.datasource.password=${DB_PASSWORD}");
        // No fallback default value after a ':' for either - both are mandatory.
        assertThat(content).doesNotContain("OPENAI_API_KEY:");
        assertThat(content).doesNotContain("DB_PASSWORD:");
    }

    @Test
    void prodExampleFile_everyCredentialIsABarePlaceholder_noDefaultValue() {
        String content = readFile(MODULE_ROOT.resolve("src/main/resources/application-prod.properties.example"));

        assertThat(content).contains("spring.datasource.url=${DB_URL}");
        assertThat(content).contains("spring.datasource.username=${DB_USERNAME}");
        assertThat(content).contains("spring.datasource.password=${DB_PASSWORD}");
        assertThat(content).contains("spring.ai.openai.api-key=${OPENAI_API_KEY}");
    }

    @Test
    void testProperties_usesOnlyTheDocumentedFictitiousKey() {
        String content = readFile(MODULE_ROOT.resolve("src/test/resources/application-test.properties"));

        assertThat(content).contains("spring.ai.openai.api-key=test-key-not-a-real-credential");
    }

    @Test
    void envExample_containsNoRealValue_onlyPlaceholders() {
        String content = readFile(MODULE_ROOT.resolve(".env.example"));

        assertThat(content).contains("OPENAI_API_KEY=configure-sua-chave");
        assertThat(content).contains("DB_PASSWORD=troque-este-valor");
        assertThat(content).doesNotContain("sk-");
    }

    @Test
    void gitignore_protectsRealSecretFiles_withoutHidingDocumentationOrRequiredFiles() throws IOException {
        Path gitignore = MODULE_ROOT.resolve("../.gitignore");
        List<String> lines = Files.readAllLines(gitignore).stream().map(String::trim).toList();

        assertThat(lines).contains(".env");
        assertThat(lines).contains("application-prod.properties");
        // Must not accidentally blanket-ignore files this project actually needs
        // versioned - checked as exact ignore-pattern lines, not substrings,
        // since both filenames are legitimately mentioned in explanatory comments.
        assertThat(lines).doesNotContain("application-test.properties", "application-dev.properties", "*.properties");
    }

    @Test
    void noVersionedFile_containsAnUnexplainedBearerTokenOrGenericSecretAssignment() throws IOException {
        try (Stream<Path> files = SCANNED_FILES.stream().filter(Files::exists).map(p -> p)) {
            files.forEach(path -> {
                String content = readFile(path);
                assertThat(content.toLowerCase(java.util.Locale.ROOT))
                        .as("file %s must not contain an 'Authorization: Bearer' header", path)
                        .doesNotContain("authorization: bearer ");
            });
        }
    }
}

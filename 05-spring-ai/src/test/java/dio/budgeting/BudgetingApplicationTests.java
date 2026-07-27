package dio.budgeting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * TASK-012: application.properties alone (no active profile) is not a
 * runnable configuration by design - it has no spring.ai.openai.api-key, and
 * Spring AI's own autoconfiguration now correctly rejects a missing key at
 * bean-creation time ("OpenAI API key must be set") instead of silently
 * accepting the leftover literal text of an unresolved ${OPENAI_API_KEY}
 * placeholder, as it effectively did before this key was removed from the
 * common file. The full context therefore needs the "test" profile (H2,
 * fictitious key, scheduler disabled) to load here, exactly like every other
 * full-context test in this module.
 */
@SpringBootTest
@ActiveProfiles("test")
class BudgetingApplicationTests {

    @Test
    void contextLoads() {
    }

}

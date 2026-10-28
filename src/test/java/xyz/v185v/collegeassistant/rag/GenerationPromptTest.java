package xyz.v185v.collegeassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GenerationPromptTest {

    private static final String INVALID_PROMPT_MESSAGE =
            "Generation prompt is invalid";

    @Test
    void rejectsNullSystemPolicy() {
        assertInvalid(null, "user data");
    }

    @Test
    void rejectsNullUserData() {
        assertInvalid("system policy", null);
    }

    @Test
    void rejectsBlankSystemPolicy() {
        assertInvalid(" \t\r\n", "user data");
    }

    @Test
    void rejectsBlankUserData() {
        assertInvalid("system policy", " \t\r\n");
    }

    @Test
    void acceptsExactlyThirtyThousandCombinedCodePointsWithoutRewriting() {
        String systemPolicy = "s";
        String userData = "u".repeat(29_999);

        GenerationPrompt prompt = new GenerationPrompt(systemPolicy, userData);

        assertThat(prompt.systemPolicy()).isSameAs(systemPolicy);
        assertThat(prompt.userData()).isSameAs(userData);
    }

    @Test
    void rejectsThirtyThousandAndOneCombinedCodePoints() {
        assertInvalid("s", "u".repeat(30_000));
    }

    @Test
    void acceptsExactlySixtyFiveThousandFiveHundredThirtySixUtf8Bytes() {
        String emoji = "\uD83D\uDE03";
        String systemPolicy = emoji.repeat(8_192);
        String userData = emoji.repeat(8_192);

        GenerationPrompt prompt = new GenerationPrompt(systemPolicy, userData);

        assertThat(prompt.systemPolicy()).isSameAs(systemPolicy);
        assertThat(prompt.userData()).isSameAs(userData);
    }

    @Test
    void rejectsOneAsciiByteOverCombinedUtf8Limit() {
        String emoji = "\uD83D\uDE03";
        assertInvalid(emoji.repeat(8_192), emoji.repeat(8_192) + "a");
    }

    private void assertInvalid(String systemPolicy, String userData) {
        assertThatThrownBy(() -> new GenerationPrompt(systemPolicy, userData))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_PROMPT_MESSAGE);
    }
}

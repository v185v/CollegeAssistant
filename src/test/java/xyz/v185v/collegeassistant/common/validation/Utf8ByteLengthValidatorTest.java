package xyz.v185v.collegeassistant.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class Utf8ByteLengthValidatorTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private static final String CHINESE_CHARACTER = "\u4e2d";
    private static final String EMOJI = "\uD83D\uDE00";

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void acceptsNull() {
        assertValid(null);
    }

    @Test
    void acceptsSeventyTwoAsciiBytes() {
        assertValid("a".repeat(72));
    }

    @Test
    void rejectsSeventyThreeAsciiBytes() {
        assertInvalid("a".repeat(73));
    }

    @Test
    void acceptsTwentyFourChineseCharacters() {
        assertValid(CHINESE_CHARACTER.repeat(24));
    }

    @Test
    void rejectsTwentyFiveChineseCharacters() {
        assertInvalid(CHINESE_CHARACTER.repeat(25));
    }

    @Test
    void acceptsEighteenFourByteEmoji() {
        assertValid(EMOJI.repeat(18));
    }

    @Test
    void rejectsNineteenFourByteEmoji() {
        assertInvalid(EMOJI.repeat(19));
    }

    private void assertValid(String value) {
        assertThat(violationsFor(value)).isEmpty();
    }

    private void assertInvalid(String value) {
        assertThat(violationsFor(value))
                .singleElement()
                .satisfies(violation -> assertThat(violation.getMessage())
                        .isEqualTo("must not exceed 72 UTF-8 bytes"));
    }

    private Set<ConstraintViolation<PasswordHolder>> violationsFor(String value) {
        return VALIDATOR.validate(new PasswordHolder(value));
    }

    private record PasswordHolder(
            @Utf8ByteLength(max = 72)
            String password
    ) {
    }
}

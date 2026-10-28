package xyz.v185v.collegeassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AnswerBudgetTest {

    @Test
    void acceptsExactlyTwentyThousandAsciiCodePointsAndRejectsOneMore() {
        String exact = "a".repeat(20_000);
        AnswerBudget deltaBudget = new AnswerBudget();

        assertThat(deltaBudget.tryAdd(exact)).isTrue();
        assertThat(deltaBudget.tryAdd("a")).isFalse();

        AnswerBudget finalBudget = new AnswerBudget();
        assertThat(finalBudget.requireValidFinal(exact)).isSameAs(exact);
        assertInvalidFinal(finalBudget, exact + "a");
    }

    @Test
    void acceptsExactlySixtyFiveThousandFiveHundredThirtySixUtf8Bytes() {
        String emoji = "\uD83D\uDE03";
        String half = emoji.repeat(8_192);
        String exact = half + half;
        AnswerBudget deltaBudget = new AnswerBudget();

        assertThat(deltaBudget.tryAdd(half)).isTrue();
        assertThat(deltaBudget.tryAdd(half)).isTrue();
        assertThat(deltaBudget.tryAdd("a")).isFalse();

        AnswerBudget finalBudget = new AnswerBudget();
        assertThat(finalBudget.requireValidFinal(exact)).isSameAs(exact);
        assertInvalidFinal(finalBudget, exact + "a");
    }

    @Test
    void tracksTheCodePointBudgetAcrossDeltaChunks() {
        AnswerBudget budget = new AnswerBudget();

        assertThat(budget.tryAdd("a".repeat(10_000))).isTrue();
        assertThat(budget.tryAdd("b".repeat(9_999))).isTrue();
        assertThat(budget.tryAdd("c")).isTrue();
        assertThat(budget.tryAdd("d")).isFalse();
    }

    @Test
    void rejectsNullAndEmptyDeltasWithoutConsumingBudget() {
        AnswerBudget budget = new AnswerBudget();

        assertThat(budget.tryAdd(null)).isFalse();
        assertThat(budget.tryAdd("")).isFalse();
        assertThat(budget.tryAdd("a".repeat(20_000))).isTrue();
    }

    @Test
    void rejectsNullOrBlankFinalsAndReturnsValidContentUnchanged() {
        AnswerBudget budget = new AnswerBudget();
        String answer = new String("  valid answer  ");

        assertInvalidFinal(budget, null);
        assertInvalidFinal(budget, " \t\r\n");
        assertThat(budget.requireValidFinal(answer)).isSameAs(answer);
    }

    private void assertInvalidFinal(AnswerBudget budget, String answer) {
        assertThatThrownBy(() -> budget.requireValidFinal(answer))
                .isInstanceOf(
                        AnswerBudget.InvalidFinalAnswerException.class)
                .hasMessage("Final answer is invalid");
    }
}

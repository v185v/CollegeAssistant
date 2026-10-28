package xyz.v185v.collegeassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RuleBasedIntentClassifierTest {

    private final IntentClassifier classifier = new RuleBasedIntentClassifier();

    @Test
    void policyQuestionIsOfficial() {
        IntentDto result = classifier.classify("绩点怎么算");

        assertThat(result.type()).isEqualTo(IntentType.OFFICIAL);
        assertThat(result.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void recommendationQuestionIsExperiential() {
        IntentDto result = classifier.classify("哪个老师好拿分，求避坑经验");

        assertThat(result.type()).isEqualTo(IntentType.EXPERIENTIAL);
        assertThat(result.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void policyAndExperienceQuestionIsMixed() {
        IntentDto result = classifier.classify("转专业政策和学长学姐经验");

        assertThat(result.type()).isEqualTo(IntentType.MIXED);
        assertThat(result.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void unknownQuestionHasLowConfidence() {
        IntentDto result = classifier.classify("明天会发生什么");

        assertThat(result.type()).isEqualTo(IntentType.UNKNOWN);
        assertThat(result.confidence()).isBetween(0.0, 0.3);
    }

    @Test
    void matchingIsDeterministicAndRobustToCaseAndWhitespace() {
        IntentDto normalized = classifier.classify("gpa policy");

        assertThat(classifier.classify("  GPA   POLICY  ")).isEqualTo(normalized);
        assertThat(classifier.classify("转 专业 规 定").type())
                .isEqualTo(IntentType.OFFICIAL);
    }

    @Test
    void nullAndBlankQuestionsAreRejectedExplicitly() {
        assertThatThrownBy(() -> classifier.classify(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("question must not be blank");
        assertThatThrownBy(() -> classifier.classify(" \t\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("question must not be blank");
    }

    @Test
    void intentConfidenceMustBeFiniteAndBounded() {
        assertThatThrownBy(() -> new IntentDto(IntentType.OFFICIAL, -0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentDto(IntentType.OFFICIAL, 1.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentDto(IntentType.OFFICIAL, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentDto(null, 0.5))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntentDto(IntentType.OFFICIAL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("confidence");
    }
}

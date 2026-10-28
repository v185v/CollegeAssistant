package xyz.v185v.collegeassistant.rag;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * 基于中英文关键词的轻量意图分类器。
 *
 * <p>同时命中政策与经验关键词时输出混合意图；未命中时以低置信度输出未知意图。</p>
 */
@Component
public final class RuleBasedIntentClassifier implements IntentClassifier {

    private static final List<String> OFFICIAL_KEYWORDS = List.of(
            "政策", "规定", "办法", "通知", "绩点", "学分", "转专业", "毕业要求",
            "选课规则", "考试规则", "policy", "regulation", "rule", "gpa", "credit");
    private static final List<String> EXPERIENTIAL_KEYWORDS = List.of(
            "老师", "好拿分", "推荐", "避坑", "宿舍", "食堂", "学长", "学姐", "经验",
            "teacher", "recommend", "dorm", "canteen", "experience");
    private static final double UNKNOWN_CONFIDENCE = 0.2;

    /** 按规范化后的关键词命中数量计算意图和置信度。 */
    @Override
    public IntentDto classify(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        String normalized = normalize(question);
        int officialMatches = countMatches(normalized, OFFICIAL_KEYWORDS);
        int experientialMatches = countMatches(normalized, EXPERIENTIAL_KEYWORDS);

        if (officialMatches > 0 && experientialMatches > 0) {
            return new IntentDto(
                    IntentType.MIXED,
                    Math.min(0.95, 0.8 + 0.03 * (officialMatches + experientialMatches)));
        }
        if (officialMatches > 0) {
            return new IntentDto(
                    IntentType.OFFICIAL,
                    Math.min(0.95, 0.75 + 0.05 * officialMatches));
        }
        if (experientialMatches > 0) {
            return new IntentDto(
                    IntentType.EXPERIENTIAL,
                    Math.min(0.95, 0.75 + 0.05 * experientialMatches));
        }
        return new IntentDto(IntentType.UNKNOWN, UNKNOWN_CONFIDENCE);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private static int countMatches(String question, List<String> keywords) {
        int matches = 0;
        for (String keyword : keywords) {
            if (question.contains(normalize(keyword))) {
                matches++;
            }
        }
        return matches;
    }
}

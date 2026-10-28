package xyz.v185v.collegeassistant.rag;

/** 将用户问题分类为政策、经验、混合或未知意图。 */
@FunctionalInterface
public interface IntentClassifier {

    /**
     * @param question 非空用户问题
     * @return 意图类型及置信度
     */
    IntentDto classify(String question);
}

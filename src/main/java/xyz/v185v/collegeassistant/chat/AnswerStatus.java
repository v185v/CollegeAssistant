package xyz.v185v.collegeassistant.chat;

/** 一次 RAG 问答的终态分类。 */
public enum AnswerStatus {
    ANSWERED,
    NO_EVIDENCE,
    NEED_PROFILE,
    FAILED
}

package xyz.v185v.collegeassistant.rag;

import java.util.List;

import xyz.v185v.collegeassistant.chat.SourceCitationDto;

/** 已按来源分组且与客户端引用一一对应的证据集合。 */
record EvidenceSet(
        List<RetrievedChunk> officialChunks,
        List<RetrievedChunk> ugcChunks,
        List<SourceCitationDto> citations
) {

    EvidenceSet {
        officialChunks = List.copyOf(officialChunks);
        ugcChunks = List.copyOf(ugcChunks);
        citations = List.copyOf(citations);
    }

    /** @return 是否没有任何可安全暴露的引用 */
    boolean isEmpty() {
        return citations.isEmpty();
    }
}

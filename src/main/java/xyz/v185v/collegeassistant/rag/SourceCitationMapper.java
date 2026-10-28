package xyz.v185v.collegeassistant.rag;

import java.util.ArrayList;
import java.util.List;

import xyz.v185v.collegeassistant.chat.ChatConstraints;
import xyz.v185v.collegeassistant.chat.SourceCitationDto;
import xyz.v185v.collegeassistant.chat.SourceType;

/** 将内部检索分块转换为经过字段约束的客户端引用和提示词证据。 */
final class SourceCitationMapper {

    /**
     * 过滤无效来源元数据，按官方优先顺序截取证据。
     *
     * @param chunks  检索分块
     * @param maximum 最大引用数量
     * @return 引用与证据正文保持一致的证据集合
     */
    EvidenceSet map(List<RetrievedChunk> chunks, int maximum) {
        List<MappedEvidence> official = new ArrayList<>();
        List<MappedEvidence> ugc = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            try {
                SourceCitationDto citation = toCitation(chunk);
                MappedEvidence evidence = new MappedEvidence(chunk, citation);
                if (citation.sourceType() == SourceType.OFFICIAL) {
                    official.add(evidence);
                } else {
                    ugc.add(evidence);
                }
            } catch (IllegalArgumentException ignored) {
                // 外部来源元数据不合法时，不向下游暴露该条引用。
            }
        }

        List<MappedEvidence> ordered = new ArrayList<>(
                official.size() + ugc.size());
        ordered.addAll(official);
        ordered.addAll(ugc);
        int size = Math.min(maximum, ordered.size());
        List<RetrievedChunk> officialChunks = new ArrayList<>();
        List<RetrievedChunk> ugcChunks = new ArrayList<>();
        List<SourceCitationDto> citations = new ArrayList<>(size);
        for (MappedEvidence evidence : ordered.subList(0, size)) {
            citations.add(evidence.citation());
            if (evidence.citation().sourceType() == SourceType.OFFICIAL) {
                officialChunks.add(evidence.chunk());
            } else {
                ugcChunks.add(evidence.chunk());
            }
        }
        return new EvidenceSet(officialChunks, ugcChunks, citations);
    }

    private SourceCitationDto toCitation(RetrievedChunk chunk) {
        return new SourceCitationDto(
                chunk.chunkId(),
                SourceType.valueOf(chunk.sourceType()),
                chunk.title(),
                chunk.sourceUrl(),
                chunk.sourceOrg(),
                chunk.publishedAt(),
                chunk.score(),
                truncateQuote(chunk.chunkText()),
                chunk.referenceType(),
                chunk.documentId(),
                chunk.externalSourceUrl());
    }

    private String truncateQuote(String value) {
        int maximum = ChatConstraints.MAX_QUOTE_CODE_POINTS;
        if (value.codePointCount(0, value.length()) <= maximum) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private record MappedEvidence(
            RetrievedChunk chunk,
            SourceCitationDto citation
    ) {
    }
}

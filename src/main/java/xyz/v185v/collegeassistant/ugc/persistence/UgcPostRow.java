package xyz.v185v.collegeassistant.ugc.persistence;

import java.time.OffsetDateTime;

public record UgcPostRow(
        Long id,
        Long authorId,
        String authorName,
        String title,
        String content,
        String tagsJson,
        String relatedCourse,
        String relatedLocation,
        String status,
        Long likes,
        Long reports,
        String rejectReason,
        OffsetDateTime createdAt,
        OffsetDateTime reviewedAt,
        OffsetDateTime updatedAt
) {
}

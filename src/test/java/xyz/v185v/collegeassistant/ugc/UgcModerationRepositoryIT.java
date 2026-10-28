package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class UgcModerationRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private UgcRepository ugcRepository;

    @Test
    void approvePersistsReviewFields() {
        Long authorId = insertStudent("ugc-approve-author");
        Long reviewerId = insertAdmin("ugc-approve-admin");
        UgcPostDto pending = createPending(authorId, "Approve this post");

        UgcPostDto approved = ugcRepository
                .review(pending.id(), reviewerId, ReviewDecision.APPROVE, null)
                .orElseThrow();

        assertThat(approved.status()).isEqualTo(UgcStatus.APPROVED);
        assertThat(approved.reviewedAt()).isNotNull();
        assertThat(approved.rejectReason()).isNull();
        assertThat(reviewedBy(approved.id())).isEqualTo(reviewerId);
        assertThat(ugcRepository.findById(approved.id())).contains(approved);
    }

    @Test
    void rejectPersistsReasonAndReviewFields() {
        Long authorId = insertStudent("ugc-reject-author");
        Long reviewerId = insertAdmin("ugc-reject-admin");
        UgcPostDto pending = createPending(authorId, "Reject this post");

        UgcPostDto rejected = ugcRepository
                .review(pending.id(), reviewerId, ReviewDecision.REJECT, "Contains private data")
                .orElseThrow();

        assertThat(rejected.status()).isEqualTo(UgcStatus.REJECTED);
        assertThat(rejected.reviewedAt()).isNotNull();
        assertThat(rejected.rejectReason()).isEqualTo("Contains private data");
        assertThat(reviewedBy(rejected.id())).isEqualTo(reviewerId);
        assertThat(ugcRepository.findById(rejected.id())).contains(rejected);
    }

    @Test
    void duplicateReviewReturnsEmptyOnSecondAttempt() {
        Long authorId = insertStudent("ugc-duplicate-author");
        Long reviewerId = insertAdmin("ugc-duplicate-admin");
        UgcPostDto pending = createPending(authorId, "Review only once");

        assertThat(ugcRepository.review(pending.id(), reviewerId, ReviewDecision.APPROVE, null))
                .isPresent();

        assertThat(ugcRepository.review(
                pending.id(), reviewerId, ReviewDecision.REJECT, "Too late"))
                .isEmpty();
        assertThat(ugcRepository.findById(pending.id()))
                .get()
                .extracting(UgcPostDto::status, UgcPostDto::rejectReason)
                .containsExactly(UgcStatus.APPROVED, null);
    }

    @Test
    void duplicateLikeAndReportAreIdempotentAndRetainFirstReason() {
        Long authorId = insertStudent("ugc-signals-author");
        Long reviewerId = insertAdmin("ugc-signals-admin");
        Long feedbackUserId = insertStudent("ugc-signals-user");
        UgcPostDto pending = createPending(authorId, "Moderation signals post");

        assertThat(ugcRepository.ensureLike(pending.id(), feedbackUserId)).isFalse();
        assertThat(ugcRepository.ensureReport(pending.id(), feedbackUserId, "Pending")).isFalse();

        assertThat(ugcRepository.review(pending.id(), reviewerId, ReviewDecision.APPROVE, null))
                .isPresent();

        assertThat(ugcRepository.ensureLike(pending.id(), feedbackUserId)).isTrue();
        assertThat(ugcRepository.ensureLike(pending.id(), feedbackUserId)).isFalse();
        assertThat(ugcRepository.ensureReport(pending.id(), feedbackUserId, "First reason")).isTrue();
        assertThat(ugcRepository.ensureReport(pending.id(), feedbackUserId, "Replacement reason")).isFalse();
        UgcPostDto stored = ugcRepository.findById(pending.id()).orElseThrow();

        assertThat(stored.likes()).isEqualTo(1);
        assertThat(stored.reports()).isEqualTo(1);
        assertThat(reportReason(pending.id(), feedbackUserId)).isEqualTo("First reason");
        assertAggregateMatchesDetails(pending.id());
    }

    @Test
    void differentUsersContributeDistinctLikesAndReports() {
        Long authorId = insertStudent("ugc-distinct-author");
        Long reviewerId = insertAdmin("ugc-distinct-admin");
        Long firstUserId = insertStudent("ugc-distinct-first");
        Long secondUserId = insertStudent("ugc-distinct-second");
        UgcPostDto approved = approve(createPending(authorId, "Distinct feedback"), reviewerId);

        assertThat(ugcRepository.ensureLike(approved.id(), firstUserId)).isTrue();
        assertThat(ugcRepository.ensureLike(approved.id(), secondUserId)).isTrue();
        assertThat(ugcRepository.ensureReport(approved.id(), firstUserId, "First")).isTrue();
        assertThat(ugcRepository.ensureReport(approved.id(), secondUserId, "Second")).isTrue();

        assertThat(ugcRepository.findById(approved.id()).orElseThrow())
                .extracting(UgcPostDto::likes, UgcPostDto::reports)
                .containsExactly(2L, 2L);
        assertAggregateMatchesDetails(approved.id());
    }

    @Test
    void nonexistentPostRejectsFeedbackWithoutCreatingDetails() {
        Long feedbackUserId = insertStudent("ugc-missing-user");

        assertThat(ugcRepository.ensureLike(999_999L, feedbackUserId)).isFalse();
        assertThat(ugcRepository.ensureReport(999_999L, feedbackUserId, "Missing")).isFalse();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM ugc_like")
                .query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM ugc_report")
                .query(Long.class).single()).isZero();
    }

    @Test
    void reportPaginationUsesStableNewestFirstOrdering() {
        Long authorId = insertStudent("ugc-report-page-author");
        Long reviewerId = insertAdmin("ugc-report-page-admin");
        UgcPostDto approved = approve(createPending(authorId, "Report pagination"), reviewerId);
        Long first = insertStudent("ugc-report-page-first");
        Long second = insertStudent("ugc-report-page-second");
        Long third = insertStudent("ugc-report-page-third");
        ugcRepository.ensureReport(approved.id(), first, "First");
        ugcRepository.ensureReport(approved.id(), second, "Second");
        ugcRepository.ensureReport(approved.id(), third, "Third");

        var page = ugcRepository.findReports(approved.id(), 1, 2);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.pages()).isEqualTo(2);
        assertThat(page.items()).extracting(UgcReportDto::reporterId)
                .containsExactly(third, second);
    }

    @Test
    void concurrentDuplicateSubmissionsCreateOneDetailRowEach() throws Exception {
        Long authorId = insertStudent("ugc-concurrent-author");
        Long reviewerId = insertAdmin("ugc-concurrent-admin");
        Long feedbackUserId = insertStudent("ugc-concurrent-user");
        UgcPostDto approved = approve(createPending(authorId, "Concurrent feedback"), reviewerId);
        int attempts = 20;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger insertedLikes = new AtomicInteger();
        AtomicInteger insertedReports = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    if (ugcRepository.ensureLike(approved.id(), feedbackUserId)) {
                        insertedLikes.incrementAndGet();
                    }
                    if (ugcRepository.ensureReport(approved.id(), feedbackUserId, "Concurrent reason")) {
                        insertedReports.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(insertedLikes).hasValue(1);
        assertThat(insertedReports).hasValue(1);
        assertAggregateMatchesDetails(approved.id());
        assertThat(ugcRepository.findById(approved.id()).orElseThrow())
                .extracting(UgcPostDto::likes, UgcPostDto::reports)
                .containsExactly(1L, 1L);
    }

    @Test
    void deletingReporterCascadesFeedbackAndDecrementsAggregates() {
        Long authorId = insertStudent("ugc-cascade-author");
        Long reviewerId = insertAdmin("ugc-cascade-admin");
        Long feedbackUserId = insertStudent("ugc-cascade-user");
        UgcPostDto approved = approve(createPending(authorId, "Cascade feedback"), reviewerId);
        ugcRepository.ensureLike(approved.id(), feedbackUserId);
        ugcRepository.ensureReport(approved.id(), feedbackUserId, "Cascade reason");

        jdbcClient.sql("DELETE FROM users WHERE id = :userId")
                .param("userId", feedbackUserId)
                .update();

        assertAggregateMatchesDetails(approved.id());
        assertThat(ugcRepository.findById(approved.id()).orElseThrow())
                .extracting(UgcPostDto::likes, UgcPostDto::reports)
                .containsExactly(0L, 0L);
    }

    private UgcPostDto createPending(Long authorId, String title) {
        return ugcRepository.create(authorId, new CreateUgcPostRequest(
                title,
                "This is detailed moderation test content with enough characters.",
                List.of("moderation"),
                "CS101",
                "West Campus"));
    }

    private UgcPostDto approve(UgcPostDto pending, Long reviewerId) {
        return ugcRepository.review(
                        pending.id(), reviewerId, ReviewDecision.APPROVE, null)
                .orElseThrow();
    }

    private Long insertStudent(String username) {
        return insertUser(username, "STUDENT");
    }

    private Long insertAdmin(String username) {
        return insertUser(username, "ADMIN");
    }

    private Long insertUser(String username, String role) {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES (:username, :passwordHash, :displayName, :role)
                        RETURNING id
                        """)
                .param("username", username)
                .param("passwordHash", "test-password-hash")
                .param("displayName", username)
                .param("role", role)
                .query((rs, rowNum) -> rs.getLong("id"))
                .single();
    }

    private Long reviewedBy(Long postId) {
        return jdbcClient.sql("SELECT reviewed_by FROM ugc_post WHERE id = :postId")
                .param("postId", postId)
                .query(Long.class)
                .single();
    }

    private String reportReason(Long postId, Long reporterId) {
        return jdbcClient.sql("""
                        SELECT reason
                        FROM ugc_report
                        WHERE post_id = :postId AND reporter_id = :reporterId
                        """)
                .param("postId", postId)
                .param("reporterId", reporterId)
                .query(String.class)
                .single();
    }

    private void assertAggregateMatchesDetails(Long postId) {
        long likeDetails = jdbcClient.sql("SELECT COUNT(*) FROM ugc_like WHERE post_id = :postId")
                .param("postId", postId)
                .query(Long.class)
                .single();
        long reportDetails = jdbcClient.sql("SELECT COUNT(*) FROM ugc_report WHERE post_id = :postId")
                .param("postId", postId)
                .query(Long.class)
                .single();
        UgcPostDto post = ugcRepository.findById(postId).orElseThrow();

        assertThat(post.likes()).isEqualTo(likeDetails);
        assertThat(post.reports()).isEqualTo(reportDetails);
    }
}

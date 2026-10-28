package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import xyz.v185v.collegeassistant.common.PageResult;
import xyz.v185v.collegeassistant.testsupport.DatabaseTestSupport;

class UgcRepositoryIT extends DatabaseTestSupport {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private UgcRepository ugcRepository;

    @Test
    void createAndFindByIdRoundTripJsonbTags() {
        Long authorId = insertUser("ugc-author", "UGC Author");
        CreateUgcPostRequest request = request("First UGC post", List.of("course", "campus"));

        UgcPostDto created = ugcRepository.create(authorId, request);

        assertThat(created.id()).isNotNull();
        assertThat(created.authorId()).isEqualTo(authorId);
        assertThat(created.authorName()).isEqualTo("UGC Author");
        assertThat(created.tags()).containsExactly("course", "campus");
        assertThat(created.status()).isEqualTo(UgcStatus.PENDING);
        assertThat(created.likes()).isZero();
        assertThat(created.reports()).isZero();
        assertThat(created.createdAt()).isNotNull();
        assertThat(ugcRepository.findById(created.id())).contains(created);
    }

    @Test
    void findPageSupportsCountPagingAndCreatedAtDescendingSort() {
        Long aliceId = insertUser("ugc-page-alice", "Alice");
        Long bobId = insertUser("ugc-page-bob", "Bob");
        UgcPostDto aliceApproved = ugcRepository.create(
                aliceId, request("Alice approved post", List.of("course")));
        UgcPostDto bobApproved = ugcRepository.create(
                bobId, request("Bob approved post", List.of("course", "campus")));
        approve(aliceApproved.id());
        approve(bobApproved.id());
        setCreatedAt(aliceApproved.id(), "2026-07-13T09:00:00+08:00");
        setCreatedAt(bobApproved.id(), "2026-07-13T10:00:00+08:00");

        PageResult<UgcPostDto> approvedFirstPage = ugcRepository.findPage(
                null, UgcStatus.APPROVED, null, 1, 1);

        assertThat(approvedFirstPage.total()).isEqualTo(2);
        assertThat(approvedFirstPage.pages()).isEqualTo(2);
        assertThat(approvedFirstPage.items()).extracting(UgcPostDto::id).containsExactly(bobApproved.id());
    }

    @Test
    void findPageUsesIdDescendingAsStableTieBreaker() {
        Long authorId = insertUser("ugc-stable-page", "Stable Page Author");
        UgcPostDto first = ugcRepository.create(
                authorId, request("First same-time post", List.of("course")));
        UgcPostDto second = ugcRepository.create(
                authorId, request("Second same-time post", List.of("course")));
        approve(first.id());
        approve(second.id());
        setCreatedAt(first.id(), "2026-07-13T10:00:00+08:00");
        setCreatedAt(second.id(), "2026-07-13T10:00:00+08:00");

        PageResult<UgcPostDto> firstPage = ugcRepository.findPage(
                null, UgcStatus.APPROVED, null, 1, 1);
        PageResult<UgcPostDto> secondPage = ugcRepository.findPage(
                null, UgcStatus.APPROVED, null, 2, 1);

        assertThat(second.id()).isGreaterThan(first.id());
        assertThat(firstPage.items()).extracting(UgcPostDto::id).containsExactly(second.id());
        assertThat(secondPage.items()).extracting(UgcPostDto::id).containsExactly(first.id());
        assertThat(List.of(firstPage.items().getFirst().id(), secondPage.items().getFirst().id()))
                .containsExactlyInAnyOrder(first.id(), second.id());
    }

    @Test
    void findPageSupportsAuthorTagAndStatusFilters() {
        Long aliceId = insertUser("ugc-alice", "Alice");
        Long bobId = insertUser("ugc-bob", "Bob");
        UgcPostDto alicePending = ugcRepository.create(
                aliceId, request("Alice pending post", List.of("campus")));
        UgcPostDto aliceApproved = ugcRepository.create(
                aliceId, request("Alice approved post", List.of("course")));
        UgcPostDto bobApproved = ugcRepository.create(
                bobId, request("Bob approved post", List.of("course", "campus")));
        approve(aliceApproved.id());
        approve(bobApproved.id());
        setCreatedAt(alicePending.id(), "2026-07-13T08:00:00+08:00");
        setCreatedAt(aliceApproved.id(), "2026-07-13T09:00:00+08:00");
        setCreatedAt(bobApproved.id(), "2026-07-13T10:00:00+08:00");

        PageResult<UgcPostDto> alicePosts = ugcRepository.findPage(aliceId, null, null, 1, 20);
        PageResult<UgcPostDto> coursePosts = ugcRepository.findPage(
                null, UgcStatus.APPROVED, "course", 1, 20);
        PageResult<UgcPostDto> pendingPosts = ugcRepository.findPage(
                null, UgcStatus.PENDING, null, 1, 20);

        assertThat(alicePosts.total()).isEqualTo(2);
        assertThat(alicePosts.items()).extracting(UgcPostDto::authorId).containsOnly(aliceId);
        assertThat(coursePosts.total()).isEqualTo(2);
        assertThat(coursePosts.items()).extracting(UgcPostDto::id)
                .containsExactly(bobApproved.id(), aliceApproved.id());
        assertThat(pendingPosts.total()).isEqualTo(1);
        assertThat(pendingPosts.items()).extracting(UgcPostDto::id).containsExactly(alicePending.id());
    }

    @Test
    void removePersistsTerminalStatus() {
        Long authorId = insertUser("ugc-remove-author", "Remove Author");
        UgcPostDto created = ugcRepository.create(
                authorId, request("Remove this post", List.of("moderation")));

        UgcPostDto removed = ugcRepository.remove(created.id()).orElseThrow();

        assertThat(removed.status()).isEqualTo(UgcStatus.REMOVED);
        assertThat(ugcRepository.findById(created.id())).contains(removed);
    }

    @Test
    void reportResolutionPersistsReviewerAndResolutionTime() {
        Long authorId = insertUser("ugc-resolution-author", "Resolution Author");
        Long reporterId = insertUser("ugc-resolution-reporter", "Resolution Reporter");
        Long reviewerId = jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES ('ugc-resolution-admin', 'test-password-hash', 'Resolution Admin', 'ADMIN')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        UgcPostDto post = ugcRepository.create(
                authorId, request("Resolve this report", List.of("moderation")));
        approve(post.id());
        assertThat(ugcRepository.ensureReport(post.id(), reporterId, "Needs moderator review"))
                .isTrue();
        Long reportId = jdbcClient.sql("""
                        SELECT id FROM ugc_report
                        WHERE post_id = :postId AND reporter_id = :reporterId
                        """)
                .param("postId", post.id())
                .param("reporterId", reporterId)
                .query(Long.class)
                .single();

        UgcReportDto resolved = ugcRepository.resolveReport(
                post.id(), reportId, reviewerId, UgcReportStatus.RESOLVED).orElseThrow();

        assertThat(resolved.status()).isEqualTo(UgcReportStatus.RESOLVED);
        assertThat(jdbcClient.sql("""
                        SELECT reviewer_id = :reviewerId AND resolved_at IS NOT NULL
                        FROM ugc_report
                        WHERE id = :reportId
                        """)
                .param("reviewerId", reviewerId)
                .param("reportId", reportId)
                .query(Boolean.class)
                .single()).isTrue();
        assertThat(ugcRepository.resolveReport(
                post.id(), reportId, reviewerId, UgcReportStatus.DISMISSED)).isEmpty();
    }

    private CreateUgcPostRequest request(String title, List<String> tags) {
        return new CreateUgcPostRequest(
                title,
                "This is detailed UGC content with enough characters for validation.",
                tags,
                "CS101",
                "West Campus");
    }

    private Long insertUser(String username, String displayName) {
        return jdbcClient.sql("""
                        INSERT INTO users (username, password_hash, display_name, role)
                        VALUES (:username, :passwordHash, :displayName, :role)
                        RETURNING id
                        """)
                .param("username", username)
                .param("passwordHash", "test-password-hash")
                .param("displayName", displayName)
                .param("role", "STUDENT")
                .query((rs, rowNum) -> rs.getLong("id"))
                .single();
    }

    private void approve(Long id) {
        jdbcClient.sql("""
                        UPDATE ugc_post
                        SET status = 'APPROVED', reviewed_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    private void setCreatedAt(Long id, String createdAt) {
        jdbcClient.sql("""
                        UPDATE ugc_post
                        SET created_at = CAST(:createdAt AS timestamptz)
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("createdAt", createdAt)
                .update();
    }
}

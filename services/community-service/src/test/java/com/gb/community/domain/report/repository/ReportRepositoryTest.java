package com.gb.community.domain.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.report.entity.Report;
import com.gb.community.domain.report.entity.ReportReason;
import com.gb.community.domain.report.entity.ReportStatus;
import com.gb.community.domain.report.entity.ReportTargetType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ReportRepository 통합 테스트 — H2 MySQL 호환 모드.
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>중복 신고 체크 ({@code existsByReporterPublicIdAndTargetTypeAndTargetId})</li>
 *   <li>status/reason 필터 조회 — 필터에 걸리는 것/걸리지 않는 것 모두 확인</li>
 *   <li>상태 일괄 변경 ({@code updateStatusByTarget})</li>
 *   <li>상태별 카운트 ({@code countByStatus})</li>
 * </ul>
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ReportRepositoryTest {

    @Autowired
    ReportRepository reportRepository;

    @Autowired
    PostRepository postRepository;

    private Post post1;
    private Post post2;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        postRepository.deleteAll();

        post1 = postRepository.save(Post.of("user-A", PostCategory.FREE, "ko", "제목1", "내용1"));
        post2 = postRepository.save(Post.of("user-B", PostCategory.JOB, "ko", "제목2", "내용2"));
    }

    @Test
    @DisplayName("중복 신고 체크 — 동일 (reporter, type, targetId) 존재 시 true")
    void existsByReporterAndTarget_duplicate() {
        reportRepository.save(
                Report.of("reporter-X", ReportTargetType.POST, post1.getId(), ReportReason.SPAM, null));

        assertThat(reportRepository.existsByReporterPublicIdAndTargetTypeAndTargetId(
                "reporter-X", ReportTargetType.POST, post1.getId())).isTrue();
    }

    @Test
    @DisplayName("중복 신고 체크 — 다른 reporter이면 false")
    void existsByReporterAndTarget_differentReporter() {
        reportRepository.save(
                Report.of("reporter-X", ReportTargetType.POST, post1.getId(), ReportReason.SPAM, null));

        assertThat(reportRepository.existsByReporterPublicIdAndTargetTypeAndTargetId(
                "reporter-Y", ReportTargetType.POST, post1.getId())).isFalse();
    }

    @Test
    @DisplayName("중복 신고 체크 — 같은 reporter가 다른 게시글 신고는 false")
    void existsByReporterAndTarget_differentTarget() {
        reportRepository.save(
                Report.of("reporter-X", ReportTargetType.POST, post1.getId(), ReportReason.SPAM, null));

        assertThat(reportRepository.existsByReporterPublicIdAndTargetTypeAndTargetId(
                "reporter-X", ReportTargetType.POST, post2.getId())).isFalse();
    }

    @Test
    @DisplayName("status 필터 — PENDING만 조회 시 RESOLVED_DELETED는 제외")
    void findByStatusAndReason_statusFilter() {
        Report r1 = reportRepository.save(
                Report.of("u1", ReportTargetType.POST, post1.getId(), ReportReason.SPAM, null));
        Report r2 = reportRepository.save(
                Report.of("u2", ReportTargetType.POST, post2.getId(), ReportReason.ABUSE, null));
        r2.resolve();
        reportRepository.save(r2);

        List<Report> pending = reportRepository.findByStatusAndReason(ReportStatus.PENDING, null);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getPublicId()).isEqualTo(r1.getPublicId());
    }

    @Test
    @DisplayName("reason 필터 — SPAM 신고만 반환, ABUSE는 제외")
    void findByStatusAndReason_reasonFilter() {
        reportRepository.save(
                Report.of("u1", ReportTargetType.POST, post1.getId(), ReportReason.SPAM, null));
        reportRepository.save(
                Report.of("u2", ReportTargetType.POST, post2.getId(), ReportReason.ABUSE, null));

        List<Report> spamReports = reportRepository.findByStatusAndReason(null, ReportReason.SPAM);

        assertThat(spamReports).hasSize(1);
        assertThat(spamReports.get(0).getReason()).isEqualTo(ReportReason.SPAM);
    }

    @Test
    @DisplayName("null 필터 — status/reason 모두 null이면 전체 반환")
    void findByStatusAndReason_noFilter() {
        reportRepository.save(
                Report.of("u1", ReportTargetType.POST, post1.getId(), ReportReason.SPAM, null));
        reportRepository.save(
                Report.of("u2", ReportTargetType.POST, post2.getId(), ReportReason.FRAUD, null));

        List<Report> all = reportRepository.findByStatusAndReason(null, null);

        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("updateStatusByTarget — 대상의 모든 신고를 RESOLVED_DELETED로 변경")
    void updateStatusByTarget() {
        reportRepository.save(
                Report.of("u1", ReportTargetType.POST, post1.getId(), ReportReason.SPAM, null));
        reportRepository.save(
                Report.of("u2", ReportTargetType.POST, post1.getId(), ReportReason.ABUSE, null));
        // post2 신고는 변경되면 안 됨
        reportRepository.save(
                Report.of("u3", ReportTargetType.POST, post2.getId(), ReportReason.SPAM, null));

        reportRepository.updateStatusByTarget(
                ReportTargetType.POST, post1.getId(), ReportStatus.RESOLVED_DELETED);

        long resolvedCount = reportRepository.findByStatusAndReason(ReportStatus.RESOLVED_DELETED, null)
                .stream().filter(r -> r.getTargetId().equals(post1.getId())).count();
        long post2PendingCount = reportRepository.findByStatusAndReason(ReportStatus.PENDING, null)
                .stream().filter(r -> r.getTargetId().equals(post2.getId())).count();

        assertThat(resolvedCount).isEqualTo(2);
        assertThat(post2PendingCount).isEqualTo(1);
    }

    @Test
    @DisplayName("countByStatus — PENDING 카운트 정확성")
    void countByStatus() {
        reportRepository.save(
                Report.of("u1", ReportTargetType.POST, post1.getId(), ReportReason.SPAM, null));
        reportRepository.save(
                Report.of("u2", ReportTargetType.POST, post2.getId(), ReportReason.ABUSE, null));

        assertThat(reportRepository.countByStatus(ReportStatus.PENDING)).isEqualTo(2);
        assertThat(reportRepository.countByStatus(ReportStatus.RESOLVED_DELETED)).isEqualTo(0);
    }
}

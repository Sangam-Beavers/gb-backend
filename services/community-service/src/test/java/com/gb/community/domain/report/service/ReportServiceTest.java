package com.gb.community.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gb.common.exception.BusinessException;
import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.repository.CommentRepository;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.report.dto.request.CreateReportRequest;
import com.gb.community.domain.report.dto.response.ReportResponse;
import com.gb.community.domain.report.entity.Report;
import com.gb.community.domain.report.entity.ReportReason;
import com.gb.community.domain.report.entity.ReportStatus;
import com.gb.community.domain.report.entity.ReportTargetType;
import com.gb.community.domain.report.repository.ReportRepository;
import com.gb.community.domain.report.service.impl.ReportServiceImpl;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * ReportServiceImpl 단위 테스트.
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>게시글/댓글 신고 정상 흐름 (201 응답용 DTO 반환)</li>
 *   <li>중복 신고 시 COMMUNITY4006 예외</li>
 *   <li>잘못된 reason 값 시 COMMUNITY4007 예외 — DB 조회 없이 빠르게 실패</li>
 *   <li>게시글 없음 시 COMMUNITY4001 예외 — reportRepository 호출 없음</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock PostRepository postRepository;
    @Mock CommentRepository commentRepository;
    @Mock ReportRepository reportRepository;
    @InjectMocks ReportServiceImpl reportService;

    // ─── 게시글 신고 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("게시글 신고 정상 — 201 응답 DTO 반환")
    void reportPost_success() {
        Post post = makePost(1L, "post-pub-1", "user-A");
        given(postRepository.findByPublicIdAndDeletedAtIsNull("post-pub-1"))
                .willReturn(Optional.of(post));
        given(reportRepository.existsByReporterPublicIdAndTargetTypeAndTargetId(
                "reporter-1", ReportTargetType.POST, 1L)).willReturn(false);

        Report savedReport = Report.of("reporter-1", ReportTargetType.POST, 1L,
                ReportReason.SPAM, null);
        given(reportRepository.save(any(Report.class))).willReturn(savedReport);

        ReportResponse resp = reportService.reportPost("reporter-1", "post-pub-1",
                new CreateReportRequest("SPAM", null));

        assertThat(resp.reason()).isEqualTo("SPAM");
        assertThat(resp.status()).isEqualTo("PENDING");
        verify(reportRepository).save(any(Report.class));
    }

    @Test
    @DisplayName("게시글 신고 — 없는 게시글이면 COMMUNITY4001, reportRepository 미호출")
    void reportPost_postNotFound() {
        given(postRepository.findByPublicIdAndDeletedAtIsNull("no-such"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() ->
                reportService.reportPost("reporter-1", "no-such",
                        new CreateReportRequest("SPAM", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommunityErrorCode.POST_NOT_FOUND));

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("게시글 신고 — 중복 신고이면 COMMUNITY4006")
    void reportPost_duplicate() {
        Post post = makePost(1L, "post-pub-1", "user-A");
        given(postRepository.findByPublicIdAndDeletedAtIsNull("post-pub-1"))
                .willReturn(Optional.of(post));
        given(reportRepository.existsByReporterPublicIdAndTargetTypeAndTargetId(
                "reporter-1", ReportTargetType.POST, 1L)).willReturn(true);

        assertThatThrownBy(() ->
                reportService.reportPost("reporter-1", "post-pub-1",
                        new CreateReportRequest("SPAM", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommunityErrorCode.DUPLICATE_REPORT));

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("게시글 신고 — 잘못된 reason이면 COMMUNITY4007, DB 저장 미호출")
    void reportPost_invalidReason() {
        Post post = makePost(1L, "post-pub-1", "user-A");
        given(postRepository.findByPublicIdAndDeletedAtIsNull("post-pub-1"))
                .willReturn(Optional.of(post));

        assertThatThrownBy(() ->
                reportService.reportPost("reporter-1", "post-pub-1",
                        new CreateReportRequest("INVALID_REASON", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommunityErrorCode.UNSUPPORTED_REPORT_REASON));

        verify(reportRepository, never()).save(any());
    }

    @Test
    @DisplayName("게시글 신고 — reason null이면 COMMUNITY4007")
    void reportPost_nullReason() {
        Post post = makePost(1L, "post-pub-1", "user-A");
        given(postRepository.findByPublicIdAndDeletedAtIsNull("post-pub-1"))
                .willReturn(Optional.of(post));

        assertThatThrownBy(() ->
                reportService.reportPost("reporter-1", "post-pub-1",
                        new CreateReportRequest(null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommunityErrorCode.UNSUPPORTED_REPORT_REASON));
    }

    // ─── 댓글 신고 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("댓글 신고 정상")
    void reportComment_success() {
        Post post = makePost(1L, "post-pub-1", "user-A");
        Comment comment = makeComment(10L, "comment-pub-1", post, "user-B");
        given(postRepository.findByPublicIdAndDeletedAtIsNull("post-pub-1"))
                .willReturn(Optional.of(post));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull("comment-pub-1"))
                .willReturn(Optional.of(comment));
        given(reportRepository.existsByReporterPublicIdAndTargetTypeAndTargetId(
                "reporter-1", ReportTargetType.COMMENT, 10L)).willReturn(false);

        Report savedReport = Report.of("reporter-1", ReportTargetType.COMMENT, 10L,
                ReportReason.ABUSE, "욕설");
        given(reportRepository.save(any(Report.class))).willReturn(savedReport);

        ReportResponse resp = reportService.reportComment("reporter-1", "post-pub-1",
                "comment-pub-1", new CreateReportRequest("ABUSE", "욕설"));

        assertThat(resp.reason()).isEqualTo("ABUSE");
        verify(reportRepository).save(any(Report.class));
    }

    @Test
    @DisplayName("댓글 신고 — 댓글이 해당 게시글 소속이 아니면 COMMUNITY4002")
    void reportComment_commentNotBelongToPost() {
        Post post1 = makePost(1L, "post-pub-1", "user-A");
        Post post2 = makePost(2L, "post-pub-2", "user-B");
        // comment는 post2에 달린 댓글
        Comment comment = makeComment(10L, "comment-pub-1", post2, "user-C");

        given(postRepository.findByPublicIdAndDeletedAtIsNull("post-pub-1"))
                .willReturn(Optional.of(post1));
        given(commentRepository.findByPublicIdAndDeletedAtIsNull("comment-pub-1"))
                .willReturn(Optional.of(comment));

        assertThatThrownBy(() ->
                reportService.reportComment("reporter-1", "post-pub-1",
                        "comment-pub-1", new CreateReportRequest("SPAM", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommunityErrorCode.COMMENT_NOT_FOUND));
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private Post makePost(Long id, String publicId, String userPublicId) {
        Post post = Post.of(userPublicId, PostCategory.FREE, "ko", "title", "content");
        ReflectionTestUtils.setField(post, "id", id);
        ReflectionTestUtils.setField(post, "publicId", publicId);
        return post;
    }

    private Comment makeComment(Long id, String publicId, Post post, String userPublicId) {
        Comment comment = Comment.builder()
                .publicId(publicId)
                .post(post)
                .userPublicId(userPublicId)
                .content("댓글 내용")
                .build();
        ReflectionTestUtils.setField(comment, "id", id);
        return comment;
    }
}

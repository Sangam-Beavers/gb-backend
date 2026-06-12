package com.gb.community.domain.report.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.repository.CommentRepository;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.report.dto.request.CreateReportRequest;
import com.gb.community.domain.report.dto.response.ReportResponse;
import com.gb.community.domain.report.entity.Report;
import com.gb.community.domain.report.entity.ReportReason;
import com.gb.community.domain.report.entity.ReportTargetType;
import com.gb.community.domain.report.repository.ReportRepository;
import com.gb.community.domain.report.service.ReportService;
import com.gb.community.global.exception.code.CommunityErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final ReportRepository reportRepository;

    @Override
    @Transactional
    public ReportResponse reportPost(String reporterPublicId, String postPublicId,
                                      CreateReportRequest request) {
        Post post = postRepository.findByPublicIdAndDeletedAtIsNull(postPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        ReportReason reason = parseReason(request.reason());
        checkDuplicate(reporterPublicId, ReportTargetType.POST, post.getId());

        Report report = Report.of(reporterPublicId, ReportTargetType.POST, post.getId(),
                reason, request.detail());
        return ReportResponse.from(reportRepository.save(report));
    }

    @Override
    @Transactional
    public ReportResponse reportComment(String reporterPublicId, String postPublicId,
                                         String commentPublicId, CreateReportRequest request) {
        // 게시글 존재 확인 (URL path 상 postId 일치 검증 포함)
        Post post = postRepository.findByPublicIdAndDeletedAtIsNull(postPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));

        Comment comment = commentRepository
                .findByPublicIdAndDeletedAtIsNull(commentPublicId)
                .filter(c -> c.getPost().getId().equals(post.getId()))
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.COMMENT_NOT_FOUND));

        ReportReason reason = parseReason(request.reason());
        checkDuplicate(reporterPublicId, ReportTargetType.COMMENT, comment.getId());

        Report report = Report.of(reporterPublicId, ReportTargetType.COMMENT, comment.getId(),
                reason, request.detail());
        return ReportResponse.from(reportRepository.save(report));
    }

    // ─── private helpers ────────────────────────────────────────────────────

    /**
     * reason 문자열 → ReportReason enum 변환.
     * valueOf 실패 시 COMMON4001 대신 도메인 코드 throw (CLAUDE.md §6).
     */
    private ReportReason parseReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(CommunityErrorCode.UNSUPPORTED_REPORT_REASON);
        }
        try {
            return ReportReason.valueOf(reason.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommunityErrorCode.UNSUPPORTED_REPORT_REASON);
        }
    }

    /** 중복 신고 확인. */
    private void checkDuplicate(String reporterPublicId, ReportTargetType targetType, Long targetId) {
        if (reportRepository.existsByReporterPublicIdAndTargetTypeAndTargetId(
                reporterPublicId, targetType, targetId)) {
            throw new BusinessException(CommunityErrorCode.DUPLICATE_REPORT);
        }
    }
}

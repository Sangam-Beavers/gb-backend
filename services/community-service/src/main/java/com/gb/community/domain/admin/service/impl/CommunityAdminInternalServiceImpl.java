package com.gb.community.domain.admin.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.admin.dto.response.AdminReportPageResponse;
import com.gb.community.domain.admin.dto.response.AdminReportView;
import com.gb.community.domain.admin.dto.response.AdminReportedAuthorDetailResponse;
import com.gb.community.domain.admin.dto.response.AdminReportedAuthorView;
import com.gb.community.domain.admin.dto.response.AdminUserActivityResponse;
import com.gb.community.domain.admin.dto.response.AdminUserCommentView;
import com.gb.community.domain.admin.dto.response.AdminUserPostView;
import com.gb.community.domain.admin.dto.response.ReportStatsResponse;
import com.gb.community.domain.admin.service.CommunityAdminInternalService;
import com.gb.community.domain.comment.entity.Comment;
import com.gb.community.domain.comment.repository.CommentRepository;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.report.entity.Report;
import com.gb.community.domain.report.entity.ReportReason;
import com.gb.community.domain.report.entity.ReportStatus;
import com.gb.community.domain.report.entity.ReportTargetType;
import com.gb.community.domain.report.repository.ReportRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommunityAdminInternalServiceImpl implements CommunityAdminInternalService {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final ReportRepository reportRepository;

    // ─── 신고 목록 (실제 reports 집계) ──────────────────────────────────────────

    @Override
    public AdminReportPageResponse reports(String statusStr, String reasonStr, int page, int size) {
        ReportStatus status = parseStatusOrNull(statusStr);
        ReportReason reason = parseReasonOrNull(reasonStr);

        List<Report> all = reportRepository.findByStatusAndReason(status, reason);

        // (targetType, targetId) 단위로 묶어서 집계 — Java-level grouping (demo scale)
        // LinkedHashMap: MAX(createdAt) DESC 정렬 후 순서 유지
        record TargetKey(ReportTargetType type, Long id) {}

        Map<TargetKey, List<Report>> grouped = all.stream()
                .collect(Collectors.groupingBy(
                        r -> new TargetKey(r.getTargetType(), r.getTargetId()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // 각 그룹을 lastReportedAt DESC로 정렬
        List<Map.Entry<TargetKey, List<Report>>> sorted = grouped.entrySet().stream()
                .sorted(Comparator.comparing(
                        e -> e.getValue().stream()
                                .map(Report::getCreatedAt)
                                .max(Comparator.naturalOrder())
                                .orElse(LocalDateTime.MIN),
                        Comparator.reverseOrder()))
                .collect(Collectors.toList());

        long total = sorted.size();
        int normalSize = Math.max(1, Math.min(size, 200));
        int normalPage = Math.max(0, page);
        int from = normalPage * normalSize;
        List<Map.Entry<TargetKey, List<Report>>> pageSlice = from >= sorted.size()
                ? List.of()
                : sorted.subList(from, Math.min(from + normalSize, sorted.size()));

        // POST/COMMENT 내용 배치 조회
        List<Long> postIds = pageSlice.stream()
                .filter(e -> e.getKey().type() == ReportTargetType.POST)
                .map(e -> e.getKey().id()).collect(Collectors.toList());
        List<Long> commentIds = pageSlice.stream()
                .filter(e -> e.getKey().type() == ReportTargetType.COMMENT)
                .map(e -> e.getKey().id()).collect(Collectors.toList());

        Map<Long, Post> postMap = postRepository.findAllById(postIds).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        Map<Long, Comment> commentMap = commentRepository.findAllById(commentIds).stream()
                .collect(Collectors.toMap(Comment::getId, Function.identity()));

        List<AdminReportView> views = pageSlice.stream()
                .map(e -> toAdminReportView(e.getKey().type(), e.getKey().id(),
                        e.getValue(), postMap, commentMap))
                .filter(v -> v != null)
                .collect(Collectors.toList());

        return AdminReportPageResponse.of(views, normalPage, normalSize, total);
    }

    // ─── 게시글 삭제/숨김 ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void hidePost(String publicId) {
        Post post = postRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        post.softDelete();
        reportRepository.updateStatusByTarget(
                ReportTargetType.POST, post.getId(), ReportStatus.RESOLVED_DELETED);
        log.info("[Admin] hidePost: post={}", publicId);
    }

    @Override
    @Transactional
    public void deletePost(String publicId) {
        Post post = postRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        post.softDelete();
        reportRepository.updateStatusByTarget(
                ReportTargetType.POST, post.getId(), ReportStatus.RESOLVED_DELETED);
    }

    @Override
    @Transactional
    public void dismissPostReports(String publicId) {
        // 신고 거부(기각): 게시글은 그대로 두고, 연관 신고만 DISMISSED로. (운영자가 "문제없음" 판단)
        Post post = postRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        reportRepository.updateStatusByTarget(
                ReportTargetType.POST, post.getId(), ReportStatus.DISMISSED);
        log.info("[Admin] dismissPostReports: post={}", publicId);
    }

    @Override
    @Transactional
    public void deleteComment(String commentPublicId) {
        Comment comment = commentRepository.findByPublicIdAndDeletedAtIsNull(commentPublicId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        comment.softDelete();
        reportRepository.updateStatusByTarget(
                ReportTargetType.COMMENT, comment.getId(), ReportStatus.RESOLVED_DELETED);
    }

    // ─── 신고 통계 (실제 PENDING 카운트) ────────────────────────────────────────

    @Override
    public ReportStatsResponse stats() {
        long pending = reportRepository.countByStatus(ReportStatus.PENDING);
        return new ReportStatsResponse(pending);
    }

    // ─── 신고당한 작성자 집계 ────────────────────────────────────────────────────

    @Override
    public List<AdminReportedAuthorView> reportedAuthors() {
        List<Report> all = reportRepository.findByStatusAndReason(null, null);

        // POST 신고 → post.userPublicId 매핑
        List<Long> postIds = all.stream()
                .filter(r -> r.getTargetType() == ReportTargetType.POST)
                .map(Report::getTargetId).distinct().collect(Collectors.toList());
        List<Long> commentIds = all.stream()
                .filter(r -> r.getTargetType() == ReportTargetType.COMMENT)
                .map(Report::getTargetId).distinct().collect(Collectors.toList());

        Map<Long, String> postAuthorMap = postRepository.findAllById(postIds).stream()
                .collect(Collectors.toMap(Post::getId, Post::getUserPublicId));
        Map<Long, String> commentAuthorMap = commentRepository.findAllById(commentIds).stream()
                .collect(Collectors.toMap(Comment::getId, Comment::getUserPublicId));

        // authorPublicId → (totalReports, distinctContentIds) 집계
        record AuthorStats(long reportCount, long contentCount) {}
        Map<String, List<Report>> byAuthor = new LinkedHashMap<>();
        Map<String, Long> contentCountMap = new LinkedHashMap<>();

        for (Report r : all) {
            String author = r.getTargetType() == ReportTargetType.POST
                    ? postAuthorMap.get(r.getTargetId())
                    : commentAuthorMap.get(r.getTargetId());
            if (author == null) continue;
            byAuthor.computeIfAbsent(author, k -> new ArrayList<>()).add(r);
        }

        // distinct content count per author
        Map<String, Long> distinctContentPerAuthor = byAuthor.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(r -> r.getTargetType().name() + ":" + r.getTargetId())
                                .distinct().count()
                ));

        return byAuthor.entrySet().stream()
                .map(e -> new AdminReportedAuthorView(
                        e.getKey(),
                        e.getValue().size(),
                        distinctContentPerAuthor.getOrDefault(e.getKey(), 0L)))
                .sorted(Comparator.comparingLong(AdminReportedAuthorView::totalReportCount).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public AdminReportedAuthorDetailResponse reportedAuthorDetail(String authorPublicId) {
        // 이 작성자의 글/댓글 조회 (최대 1000건 — demo 범위)
        Pageable large = PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<Post> posts = postRepository.findByUserPublicIdAndDeletedAtIsNull(
                authorPublicId, large).getContent();
        List<Comment> comments = commentRepository.findByUserPublicIdAndDeletedAtIsNull(
                authorPublicId, large).getContent();

        List<Long> postIds = posts.stream().map(Post::getId).collect(Collectors.toList());
        List<Long> commentIds = comments.stream().map(Comment::getId).collect(Collectors.toList());

        List<Report> postReports = postIds.isEmpty() ? List.of()
                : reportRepository.findByTargetTypeAndTargetIdIn(ReportTargetType.POST, postIds);
        List<Report> commentReports = commentIds.isEmpty() ? List.of()
                : reportRepository.findByTargetTypeAndTargetIdIn(ReportTargetType.COMMENT, commentIds);

        Map<Long, Post> postMap = posts.stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        Map<Long, Comment> commentMap = comments.stream()
                .collect(Collectors.toMap(Comment::getId, Function.identity()));

        // (targetType, targetId) 단위로 묶어서 AdminReportView 생성
        List<Report> allReports = new ArrayList<>(postReports);
        allReports.addAll(commentReports);

        Map<String, List<Report>> grouped = allReports.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getTargetType().name() + ":" + r.getTargetId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<AdminReportView> views = grouped.values().stream()
                .map(group -> {
                    Report rep = group.get(0);
                    return toAdminReportView(rep.getTargetType(), rep.getTargetId(),
                            group, postMap, commentMap);
                })
                .filter(v -> v != null)
                .collect(Collectors.toList());

        return new AdminReportedAuthorDetailResponse(authorPublicId, views);
    }

    // ─── 회원 활동 ────────────────────────────────────────────────────────────────

    @Override
    public AdminUserActivityResponse getUserActivity(String userPublicId,
                                                      int postPage, int commentPage, int size) {
        int normalSize = normalizeSize(size);
        Pageable postPageable = PageRequest.of(Math.max(postPage, 0), normalSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Pageable commentPageable = PageRequest.of(Math.max(commentPage, 0), normalSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Post> posts = postRepository.findByUserPublicIdAndDeletedAtIsNull(
                userPublicId, postPageable);
        Page<Comment> comments = commentRepository.findByUserPublicIdAndDeletedAtIsNull(
                userPublicId, commentPageable);

        return new AdminUserActivityResponse(
                posts.getContent().stream().map(AdminUserPostView::from).collect(Collectors.toList()),
                posts.getNumber(), posts.getSize(), posts.getTotalElements(),
                comments.getContent().stream().map(AdminUserCommentView::from).collect(Collectors.toList()),
                comments.getNumber(), comments.getSize(), comments.getTotalElements()
        );
    }

    // ─── private helpers ─────────────────────────────────────────────────────────

    /**
     * Report 그룹 → AdminReportView 변환.
     * 대표 reason = 최다 빈도 reason(동률이면 첫 번째).
     * status = 그룹 내 PENDING 존재 시 PENDING, 아니면 최신 report의 status.
     */
    private AdminReportView toAdminReportView(ReportTargetType targetType, Long targetId,
                                               List<Report> group,
                                               Map<Long, Post> postMap,
                                               Map<Long, Comment> commentMap) {
        if (group.isEmpty()) return null;

        // 대표 reason: 최다 빈도
        String dominantReason = group.stream()
                .collect(Collectors.groupingBy(Report::getReason, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey().name())
                .orElse(null);

        // 그룹 status
        boolean hasPending = group.stream().anyMatch(r -> r.getStatus() == ReportStatus.PENDING);
        String groupStatus = hasPending ? ReportStatus.PENDING.name()
                : group.get(0).getStatus().name();

        long reportCount = group.size();
        LocalDateTime lastReportedAt = group.stream()
                .map(Report::getCreatedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);

        if (targetType == ReportTargetType.POST) {
            Post post = postMap.get(targetId);
            if (post == null) return null; // 이미 삭제된 게시글
            return new AdminReportView(
                    post.getPublicId(),
                    post.getTitle(),
                    post.getUserPublicId(),
                    ReportTargetType.POST.name(),
                    dominantReason,
                    reportCount,
                    groupStatus,
                    lastReportedAt);
        } else {
            Comment comment = commentMap.get(targetId);
            if (comment == null) return null;
            // 댓글은 제목 없음 → 내용 앞 100자 요약
            String excerpt = comment.getContent().length() > 100
                    ? comment.getContent().substring(0, 100) + "..."
                    : comment.getContent();
            return new AdminReportView(
                    comment.getPublicId(),
                    excerpt,
                    comment.getUserPublicId(),
                    ReportTargetType.COMMENT.name(),
                    dominantReason,
                    reportCount,
                    groupStatus,
                    lastReportedAt);
        }
    }

    private static ReportStatus parseStatusOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return ReportStatus.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static ReportReason parseReasonOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return ReportReason.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static int normalizeSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, 200);
    }
}

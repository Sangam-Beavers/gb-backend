package com.gb.appadmin.global.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gb.appadmin.domain.member.dto.response.UserActivityResponse;
import com.gb.appadmin.domain.member.dto.response.UserCommentView;
import com.gb.appadmin.domain.member.dto.response.UserPostView;
import com.gb.appadmin.domain.report.dto.response.MemberReportItemResponse;
import com.gb.appadmin.domain.report.dto.response.ReportedAuthorSummary;
import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * community-service /api/v1/internal/admin/* 호출 실 클라이언트.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RealCommunityAdminClient implements CommunityAdminClient {

    @Qualifier("communityRestClient")
    private final RestClient communityRestClient;

    @Override
    public UserActivityResponse getUserActivity(String userPublicId, int postPage, int commentPage, int size) {
        try {
            String uri = UriComponentsBuilder
                    .fromPath("/api/v1/internal/admin/members/{id}/activity")
                    .queryParam("post_page", postPage)
                    .queryParam("comment_page", commentPage)
                    .queryParam("size", size)
                    .buildAndExpand(userPublicId)
                    .toUriString();

            Envelope<ActivityPayload> env = communityRestClient.get().uri(uri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            ActivityPayload payload = env == null ? null : env.data();
            if (payload == null) {
                return empty(size);
            }

            List<UserPostView> posts = payload.posts() == null ? List.of()
                    : payload.posts().stream().map(p -> new UserPostView(
                            p.publicId(), p.category(), p.title(), p.commentCount(), p.likeCount(), p.createdAt()))
                    .toList();
            List<UserCommentView> comments = payload.comments() == null ? List.of()
                    : payload.comments().stream().map(c -> new UserCommentView(
                            c.publicId(), c.postPublicId(), c.content(), c.likeCount(), c.createdAt()))
                    .toList();

            return new UserActivityResponse(
                    posts, payload.postPage(), payload.postSize(), payload.postTotal(),
                    comments, payload.commentPage(), payload.commentSize(), payload.commentTotal()
            );
        } catch (RuntimeException e) {
            log.error("[RealCommunityAdminClient] getUserActivity 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public List<ReportedAuthorSummary> getReportedAuthors(int page, int size) {
        try {
            String uri = UriComponentsBuilder
                    .fromPath("/api/v1/internal/admin/reports/by-author")
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .build().toUriString();

            Envelope<ByAuthorPagePayload> env = communityRestClient.get().uri(uri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            ByAuthorPagePayload payload = env == null ? null : env.data();
            if (payload == null || payload.authors() == null) {
                return List.of();
            }
            return payload.authors().stream()
                    .map(a -> new ReportedAuthorSummary(
                            a.authorPublicId(), null, null,
                            a.totalReportCount(), a.reportedContentCount()))
                    .toList();
        } catch (RuntimeException e) {
            log.error("[RealCommunityAdminClient] getReportedAuthors 실패: {}", e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public List<MemberReportItemResponse> getMemberReports(String authorPublicId) {
        try {
            Envelope<List<AdminReportViewWire>> env = communityRestClient.get()
                    .uri("/api/v1/internal/admin/reports/by-author/{id}", authorPublicId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            List<AdminReportViewWire> items = env == null ? null : env.data();
            if (items == null) {
                return List.of();
            }
            return items.stream()
                    .map(v -> new MemberReportItemResponse(
                            v.postPublicId(), v.postTitle(), v.authorPublicId(),
                            v.targetType(), v.category(),
                            v.reportCount(), v.status(), v.lastReportedAt()))
                    .toList();
        } catch (RuntimeException e) {
            log.error("[RealCommunityAdminClient] getMemberReports 실패 authorPublicId={}: {}",
                    authorPublicId, e.getMessage());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static UserActivityResponse empty(int size) {
        return new UserActivityResponse(List.of(), 0, size, 0, List.of(), 0, size, 0);
    }

    // ── wire DTOs ─────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope<T>(@JsonProperty("data") T data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ActivityPayload(
            @JsonProperty("posts") List<PostWire> posts,
            @JsonProperty("post_page") int postPage,
            @JsonProperty("post_size") int postSize,
            @JsonProperty("post_total") long postTotal,
            @JsonProperty("comments") List<CommentWire> comments,
            @JsonProperty("comment_page") int commentPage,
            @JsonProperty("comment_size") int commentSize,
            @JsonProperty("comment_total") long commentTotal) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PostWire(
            @JsonProperty("public_id") String publicId,
            @JsonProperty("category") String category,
            @JsonProperty("title") String title,
            @JsonProperty("comment_count") int commentCount,
            @JsonProperty("like_count") int likeCount,
            @JsonProperty("created_at") String createdAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentWire(
            @JsonProperty("public_id") String publicId,
            @JsonProperty("post_public_id") String postPublicId,
            @JsonProperty("content") String content,
            @JsonProperty("like_count") int likeCount,
            @JsonProperty("created_at") String createdAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ByAuthorPagePayload(
            @JsonProperty("authors") List<AuthorAggWire> authors,
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorAggWire(
            @JsonProperty("author_public_id") String authorPublicId,
            @JsonProperty("total_report_count") long totalReportCount,
            @JsonProperty("reported_content_count") long reportedContentCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdminReportViewWire(
            @JsonProperty("post_public_id") String postPublicId,
            @JsonProperty("post_title") String postTitle,
            @JsonProperty("author_public_id") String authorPublicId,
            @JsonProperty("target_type") String targetType,
            @JsonProperty("category") String category,
            @JsonProperty("report_count") int reportCount,
            @JsonProperty("status") String status,
            @JsonProperty("last_reported_at") String lastReportedAt) {}
}

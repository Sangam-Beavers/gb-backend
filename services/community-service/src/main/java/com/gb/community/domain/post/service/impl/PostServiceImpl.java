package com.gb.community.domain.post.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.post.dto.request.PostCreateRequest;
import com.gb.community.domain.post.dto.request.PostUpdateRequest;
import com.gb.community.domain.post.dto.response.PostDetailResponse;
import com.gb.community.domain.post.dto.response.PostListResponse;
import com.gb.community.domain.post.dto.response.PostSummaryResponse;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.post.service.PostService;
import com.gb.community.global.client.MemberClient;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final MemberClient memberClient;

    @Override
    public PostListResponse getPosts(String category, String keyword, String sort, int page, int size) {
        PostCategory categoryFilter = parseCategory(category);   // 잘못된 값 → COMMON4001
        String keywordFilter = escapeLikeKeyword(nullIfBlank(keyword)); // 빈 키워드면 null(전체), 아니면 LIKE 메타문자 이스케이프
        Pageable pageable = buildPageable(sort, page, size);     // 잘못된 sort → COMMON4001

        Page<Post> result = postRepository.search(categoryFilter, keywordFilter, pageable);
        List<Post> posts = result.getContent();

        // 작성자 표시 정보를 배치로 1회 조회한다(N+1 회피). 같은 페이지 안의 중복 작성자는 distinct로 1회만.
        // getMembers는 요청한 모든 id를 키로 포함(누락=fallback)하므로 아래 .get(id)는 null이 되지 않는다.
        List<String> authorIds = posts.stream().map(Post::getUserPublicId).distinct().toList();
        Map<String, MemberInfo> authorsByPublicId =
                authorIds.isEmpty() ? Map.of() : memberClient.getMembers(authorIds);

        List<PostSummaryResponse> items = posts.stream()
                .map(post -> PostSummaryResponse.from(post, authorsByPublicId.get(post.getUserPublicId())))
                .toList();

        return PostListResponse.of(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Override
    public PostDetailResponse getPost(String postPublicId) {
        Post post = getActivePostOrThrow(postPublicId);
        return PostDetailResponse.from(post, memberClient.getMember(post.getUserPublicId()));
    }

    @Override
    @Transactional
    public PostDetailResponse createPost(String requesterUserPublicId, PostCreateRequest request) {
        PostCategory category = parseCategory(request.getCategory());
        if (category == null) {
            // @NotBlank가 1차로 막지만, 방어적으로 한 번 더 — 카테고리는 작성 시 필수.
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        // language는 "ko" 고정(Post.of).
        Post saved = postRepository.save(
                Post.of(requesterUserPublicId, category, request.getTitle(), request.getContent()));
        return PostDetailResponse.from(saved, memberClient.getMember(requesterUserPublicId));
    }

    @Override
    @Transactional
    public PostDetailResponse updatePost(String requesterUserPublicId, String postPublicId,
                                         PostUpdateRequest request) {
        Post post = getActivePostOrThrow(postPublicId);
        verifyOwner(post, requesterUserPublicId);

        // category는 보냈을 때만 파싱(잘못된 값 → COMMON4001). title/content는 blank를 "변경 없음"으로 정규화.
        PostCategory newCategory = parseCategory(request.getCategory());
        post.update(newCategory, nullIfBlank(request.getTitle()), nullIfBlank(request.getContent()));
        // 변경은 영속성 컨텍스트 dirty checking으로 커밋 시 반영(별도 save 불필요).

        return PostDetailResponse.from(post, memberClient.getMember(post.getUserPublicId()));
    }

    @Override
    @Transactional
    public void deletePost(String requesterUserPublicId, String postPublicId) {
        Post post = getActivePostOrThrow(postPublicId);
        verifyOwner(post, requesterUserPublicId);
        post.softDelete(); // deleted_at 세팅 — dirty checking으로 반영
    }

    // ----- helpers -----

    /** 활성(미삭제) 게시글 조회. 없거나 삭제됐으면 COMMUNITY4001. */
    private Post getActivePostOrThrow(String postPublicId) {
        return postRepository.findByPublicIdAndDeletedAtIsNull(postPublicId)
                .orElseThrow(() -> new BusinessException(CommunityErrorCode.POST_NOT_FOUND));
    }

    /** 본인 글 여부 검증. 타인 글이면 COMMON4031(권한 없음). */
    private void verifyOwner(Post post, String requesterUserPublicId) {
        if (!post.getUserPublicId().equals(requesterUserPublicId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    /**
     * category 문자열을 enum으로 파싱한다. null/blank면 "필터 없음/변경 없음"을 뜻하는 null을 반환하고,
     * 값이 있는데 enum에 없으면 COMMON4001로 던진다(잘못된 카테고리). 명세상 enum은 SCREAMING_SNAKE_CASE.
     */
    private PostCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PostCategory.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    /**
     * sort 파라미터를 정렬 규칙으로 변환해 Pageable을 만든다.
     * <ul>
     *   <li>latest(기본) — 최신순(createdAt desc)</li>
     *   <li>popular — 좋아요순(likeCount desc), 동률은 최신순</li>
     *   <li>accuracy — 키워드 검색 정확도순. 전문검색/관련도 랭킹은 도입하지 않기로 확정(폐지)했으므로
     *       키워드 유무와 무관하게 최신순(latest)과 동일하게 정렬한다.</li>
     * </ul>
     * 그 외 값은 COMMON4001. id를 마지막 tie-breaker로 둬 정렬을 결정적으로 만든다(노출 X, 정렬 키로만 사용).
     */
    private Pageable buildPageable(String sortRaw, int page, int size) {
        String sort = (sortRaw == null || sortRaw.isBlank()) ? "latest" : sortRaw.trim().toLowerCase();
        Sort order = switch (sort) {
            case "latest", "accuracy" -> Sort.by(Sort.Direction.DESC, "createdAt", "id");
            case "popular" -> Sort.by(Sort.Direction.DESC, "likeCount", "createdAt", "id");
            default -> throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        };
        return PageRequest.of(page, size, order);
    }

    /** 값이 null이거나 공백뿐이면 null, 아니면 원본 그대로. PATCH의 "변경 없음" 정규화에 사용. */
    private String nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * LIKE 검색용 키워드 이스케이프. 사용자가 입력한 {@code |, %, _}를 와일드카드가 아닌 literal로
     * 매칭하도록 이스케이프한다({@code | → ||}, {@code % → |%}, {@code _ → |_}).
     * 이스케이프 문자(|)를 가장 먼저 치환해야 뒤에서 붙인 이스케이프 파이프가 다시 중복 처리되지 않는다.
     * Repository JPQL의 {@code LIKE ... ESCAPE '|'}와 짝을 이룬다(파이프를 쓰는 이유는 그쪽 주석 참고).
     * null이면 그대로 null(검색 안 함).
     */
    private String escapeLikeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword
                .replace("|", "||")
                .replace("%", "|%")
                .replace("_", "|_");
    }
}
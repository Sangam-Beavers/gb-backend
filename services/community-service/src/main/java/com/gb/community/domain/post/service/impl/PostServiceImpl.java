package com.gb.community.domain.post.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.like.entity.LikeTargetType;
import com.gb.community.domain.like.repository.LikeRepository;
import com.gb.community.domain.post.dto.request.PostCreateRequest;
import com.gb.community.domain.post.dto.request.PostUpdateRequest;
import com.gb.community.domain.post.dto.response.PostDetailResponse;
import com.gb.community.domain.post.dto.response.PostListResponse;
import com.gb.community.domain.post.dto.response.PostSummaryResponse;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.post.repository.PostTranslationRepository;
import com.gb.community.domain.post.service.PostService;
import com.gb.community.global.client.MemberClient;
import com.gb.community.global.client.MemberInfo;
import com.gb.community.global.event.MilestoneAchieved;
import com.gb.community.global.event.MilestoneType;
import com.gb.community.global.exception.code.CommunityErrorCode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    // 단건/수정 응답의 is_liked(요청자의 좋아요 여부) 계산용 — 좋아요 저장의 중복(409) 판정과 동일한
    // EXISTS 조회를 재사용한다. 같은 서비스 내 도메인 간 repository 주입은 기존 관행(Comment→PostRepository).
    private final LikeRepository likeRepository;
    private final MemberClient memberClient;
    // #161 — 게시글 본문/제목 수정 시 모든 언어 번역 캐시 무효화.
    // 카테고리만 바뀌는 PATCH는 무효화 대상 아님(요구사항 §6).
    private final PostTranslationRepository postTranslationRepository;
    // Phase 3(BE-8) — 작성 성공 시 마일스톤 내부 이벤트 발행용(Kafka 전송은 AFTER_COMMIT 리스너).
    private final ApplicationEventPublisher eventPublisher;

    /** self-injection: createPostTx/updatePostTx의 @Transactional 프록시 적용 위함(wallet 동일 패턴). */
    @Autowired
    @Lazy
    private PostService self;

    // MemberClient(외부 HTTP) 호출은 트랜잭션/커넥션을 보유한 채 하지 않는다
    // (tx 안에서 호출하면 네트워크 대기 동안 커넥션 점유 → 풀 고갈).
    // 읽기 경로는 NOT_SUPPORTED(단일 SELECT는 트랜잭션 불요 — repo 호출이 각자 짧은 readOnly tx),
    // 쓰기 경로는 DB 본문을 self-proxy tx 메서드로 묶고 회원 조회는 커밋 후 응답 조립에서 한다.
    // 응답 DTO는 스칼라 컬럼만 읽으므로(LAZY 연관 미탐색) tx 밖 접근이 안전하다.

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PostListResponse getPosts(String requesterUserPublicId, String category, String keyword,
                                     String sort, int page, int size) {
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
                .map(post -> PostSummaryResponse.from(
                        post, authorsByPublicId.get(post.getUserPublicId()), requesterUserPublicId))
                .toList();

        return PostListResponse.of(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PostDetailResponse getPost(String requesterUserPublicId, String postPublicId) {
        Post post = getActivePostOrThrow(postPublicId);
        // is_liked: 요청자의 좋아요 여부 — DB 조회(EXISTS)를 외부 HTTP(getMember)보다 먼저 끝낸다.
        boolean isLiked = isLikedBy(requesterUserPublicId, post);
        // 단건 SELECT 후 외부 호출 — 트랜잭션 불요(NOT_SUPPORTED로 클래스 readOnly tx 차단).
        return PostDetailResponse.from(post, memberClient.getMember(post.getUserPublicId()),
                requesterUserPublicId, isLiked);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PostDetailResponse createPost(String requesterUserPublicId, PostCreateRequest request) {
        // 커뮤니티 활동 제한 회원 차단 — member-service HTTP 호출(fail-fast). DB 쓰기 전에 검증한다.
        if (memberClient.isCommunityBanned(requesterUserPublicId)) {
            throw new BusinessException(CommunityErrorCode.COMMUNITY_BANNED);
        }
        // DB 본문(INSERT)은 self-proxy 쓰기 트랜잭션으로, 작성자 표시 정보 조회는 커밋 후 tx 밖에서.
        Post saved = self.createPostTx(requesterUserPublicId, request);
        // is_author: 작성 응답은 요청자가 곧 작성자 — 항상 true.
        // is_liked: 방금 INSERT된 글이라 좋아요 행이 존재할 수 없다 — 항상 false(EXISTS 조회 생략, 명세 §2).
        return PostDetailResponse.from(saved, memberClient.getMember(requesterUserPublicId),
                requesterUserPublicId, false);
    }

    @Override
    @Transactional
    public Post createPostTx(String requesterUserPublicId, PostCreateRequest request) {
        PostCategory category = parseCategory(request.getCategory());
        if (category == null) {
            // @NotBlank가 1차로 막지만, 방어적으로 한 번 더 — 카테고리는 작성 시 필수.
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        String language = resolveLanguage(request.getLanguage());
        Post saved = postRepository.save(
                Post.of(requesterUserPublicId, category, language, request.getTitle(), request.getContent()));
        // Phase 3(BE-8) — 커뮤니티 데뷔 마일스톤. 내부 이벤트만 발행하고, Kafka 전송은 본 tx "커밋 후"
        // MilestoneEventPublisher(AFTER_COMMIT)가 수행한다(롤백 시 미발행). 첫 글인지 판단하지 않고
        // 매번 발행 — 수신측(member)이 (user, milestone) UNIQUE로 자연 멱등 스킵(스파이크 결정 3).
        eventPublisher.publishEvent(
                new MilestoneAchieved(requesterUserPublicId, MilestoneType.COMMUNITY_ACTIVE));
        return saved;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PostDetailResponse updatePost(String requesterUserPublicId, String postPublicId,
                                         PostUpdateRequest request) {
        // DB 본문(조회→본인검증→dirty checking 변경)은 self-proxy 쓰기 트랜잭션으로, 회원 조회는 커밋 후.
        Post post = self.updatePostTx(requesterUserPublicId, postPublicId, request);
        // is_author: 수정은 본인 검증을 통과한 흐름 — 항상 true.
        // is_liked: 본인 글도 본인이 좋아요했을 수 있다(self-like 제한 없음) — 실제 EXISTS 값. 커밋 후 조회.
        boolean isLiked = isLikedBy(requesterUserPublicId, post);
        return PostDetailResponse.from(post, memberClient.getMember(post.getUserPublicId()),
                requesterUserPublicId, isLiked);
    }

    @Override
    @Transactional
    public Post updatePostTx(String requesterUserPublicId, String postPublicId,
                             PostUpdateRequest request) {
        Post post = getActivePostOrThrow(postPublicId);
        verifyOwner(post, requesterUserPublicId);

        // category는 보냈을 때만 파싱(잘못된 값 → COMMON4001). title/content는 blank를 "변경 없음"으로 정규화.
        PostCategory newCategory = parseCategory(request.getCategory());
        String newTitle = nullIfBlank(request.getTitle());
        String newContent = nullIfBlank(request.getContent());
        // 세 값이 모두 없으면 변경할 내용이 없는 빈 PATCH → 조용한 no-op 대신 명시적으로 거절(COMMON4001).
        if (newCategory == null && newTitle == null && newContent == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        post.update(newCategory, newTitle, newContent);
        // 본문/제목 변경 시 해당 글의 모든 언어 번역 캐시 삭제 — translation.md §5 무효화 정책.
        // 카테고리만 바뀐 PATCH(newTitle == null && newContent == null)는 본문 동일이라 무효화 불요.
        // 같은 트랜잭션에서 DELETE → (커밋 후) 사용자가 번역 보기 재요청 시 미스 → Lambda 재호출 → 재INSERT.
        if (newTitle != null || newContent != null) {
            postTranslationRepository.deleteByPostId(post.getId());
        }
        // 변경은 영속성 컨텍스트 dirty checking으로 커밋 시 반영(별도 save 불필요).
        return post;
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
     * 요청자가 이 글을 좋아요했는지(is_liked). 좋아요 저장({@code LikeServiceImpl.like})의 중복(COMMON4091)
     * 판정과 동일한 (user_public_id, POST, target_id) EXISTS 조회 — 복합 UNIQUE 인덱스를 타는 단건 조회라
     * NOT_SUPPORTED 경로에서 자체 짧은 tx 1건이 추가될 뿐이다.
     */
    private boolean isLikedBy(String requesterUserPublicId, Post post) {
        return likeRepository.existsByUserPublicIdAndTargetTypeAndTargetId(
                requesterUserPublicId, LikeTargetType.POST, post.getId());
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

    /**
     * 요청 언어 코드를 정규화한다. null/blank이면 "ko" 기본값. 지원 언어 외 값이면 COMMUNITY4003.
     * 화이트리스트 로직은 PostTranslationServiceImpl과 동일하나, source lang 맥락이므로 서비스에서 재검증.
     */
    private String resolveLanguage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "ko";
        }
        String normalized = raw.trim().toLowerCase();
        if (!PostTranslationServiceImpl.SUPPORTED_LANGUAGES.contains(normalized)) {
            throw new BusinessException(CommunityErrorCode.UNSUPPORTED_LANGUAGE);
        }
        return normalized;
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
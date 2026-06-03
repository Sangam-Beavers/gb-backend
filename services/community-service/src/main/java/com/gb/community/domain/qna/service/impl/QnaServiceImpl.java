package com.gb.community.domain.qna.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.community.domain.post.entity.Post;
import com.gb.community.domain.post.entity.PostCategory;
import com.gb.community.domain.post.repository.PostRepository;
import com.gb.community.domain.qna.dto.response.QnaListResponse;
import com.gb.community.domain.qna.dto.response.QnaPostResponse;
import com.gb.community.domain.qna.service.QnaService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주요 QnA 목록 서비스 — Post 엔티티의 read-only 조회만 한다. 별도 도메인 패키지({@code qna})로 둔
 * 이유: 화면/엔드포인트가 게시글 CRUD(§1·§3)와 분리되고({@code /community/qna}), 응답 DTO도
 * 다르기 때문(작성자 정보·페이지 메타 없는 Top N).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QnaServiceImpl implements QnaService {

    private final PostRepository postRepository;

    @Override
    public QnaListResponse getQnaPosts(String categoryRaw, int size) {
        // category 미입력 → QUESTION 카테고리만 (명세 §8 "category = QUESTION 필터" 기본 동작).
        // 입력 → enum 변환 후 그 카테고리만. 잘못된 값은 COMMON4001.
        PostCategory category = parseCategoryOrDefault(categoryRaw);

        // 정렬은 Repository @Query의 ORDER BY가 결정한다(commentCount DESC, id DESC).
        // Pageable은 size 제한 용도로만 쓴다(page=0 고정 — Top N 목록이라 페이지네이션 없음).
        // Sort.unsorted() 명시: 호출 측에서 Sort를 안 싣는다는 의도를 분명히 — JPQL ORDER BY와 충돌 없음.
        List<Post> posts = postRepository.findTopByCategoryOrderByCommentCountDesc(
                category, PageRequest.of(0, size));

        List<QnaPostResponse> items = posts.stream()
                .map(QnaPostResponse::from)
                .toList();

        return QnaListResponse.of(items);
    }

    /**
     * category 문자열을 enum으로 파싱. null/blank면 기본값 QUESTION, 값이 있는데 enum에 없으면 COMMON4001.
     *
     * <p>PostService.parseCategory와 시그니처가 다르다(거기는 null 반환 = "필터 없음"). QnA는 항상
     * 단일 카테고리 필터를 적용하므로 null 반환 대신 기본값 QUESTION으로 폴백한다.
     */
    private PostCategory parseCategoryOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return PostCategory.QUESTION;
        }
        try {
            return PostCategory.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }
}

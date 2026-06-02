package com.gb.community.domain.qna.service;

import com.gb.community.domain.qna.dto.response.QnaListResponse;

/**
 * 주요 QnA 목록 비즈니스 로직 (api-spec §8).
 *
 * <p>특정 카테고리의 활성 게시글을 답변(댓글) 수 내림차순으로 상위 N건 반환한다. 페이지네이션은 없다
 * (Top N 고정 목록). 본문/작성자 정보는 포함하지 않고 단건 조회 API로 별도 조회한다.
 */
public interface QnaService {

    /**
     * 주요 QnA 목록 조회.
     *
     * <p>{@code category} 동작 (api-spec §8 명세 모호성 해석안 A):
     * <ul>
     *   <li>null/blank → {@code QUESTION} 카테고리만 반환 (명세 "category = QUESTION 필터")</li>
     *   <li>값 있음 (LIFE_INFO/JOB/VISA/COUNTRY/RESIDENCE/QUESTION) → 해당 카테고리만 반환
     *       (명세 "상위 카테고리 필터")</li>
     * </ul>
     * 잘못된 카테고리 값은 {@code COMMON4001}. {@code size}는 컨트롤러가 1~100 가드한다.
     */
    QnaListResponse getQnaPosts(String category, int size);
}

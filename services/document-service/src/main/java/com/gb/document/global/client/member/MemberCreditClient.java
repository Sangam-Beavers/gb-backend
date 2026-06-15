package com.gb.document.global.client.member;

/**
 * member-service 크레딧 차감 클라이언트.
 *
 * <p>document-service가 분석 요청 시 member-service 내부 API
 * {@code PATCH /api/v1/internal/members/{userPublicId}/credit/use}를 호출해
 * 크레딧을 1 차감한다.
 *
 * <ul>
 *   <li>크레딧 부족(422 MEMBER4007) → {@link com.gb.common.exception.BusinessException}(DOCUMENT4002)
 *   <li>그 외 오류 → {@link com.gb.common.exception.BusinessException}(COMMON5000)
 * </ul>
 */
public interface MemberCreditClient {

    /**
     * 회원의 서류 분석 크레딧을 1 차감한다.
     *
     * @param userPublicId 회원 public_id(UUID)
     * @throws com.gb.common.exception.BusinessException DOCUMENT4002(크레딧 부족) / COMMON5000(서버 오류)
     */
    void useCredit(String userPublicId);
}

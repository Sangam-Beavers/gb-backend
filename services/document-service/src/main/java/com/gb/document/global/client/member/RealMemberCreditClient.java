package com.gb.document.global.client.member;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.document.global.exception.code.DocumentErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * member-service 크레딧 차감 실제 클라이언트.
 *
 * <p>{@code PATCH /api/v1/internal/members/{userPublicId}/credit/use}를 호출해
 * 크레딧을 1 차감한다.
 *
 * <ul>
 *   <li>422 MEMBER4007 → DOCUMENT4002(크레딧 부족)
 *   <li>404 MEMBER4001 → COMMON5000(정상 플로우에서 발생하지 않아야 함)
 *   <li>네트워크·서버 오류 → COMMON5000
 * </ul>
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RealMemberCreditClient implements MemberCreditClient {

    private final RestClient memberCreditRestClient;

    @Value("${member.api.base-url:http://member-service:8081}")
    private String baseUrl;

    @Override
    public void useCredit(String userPublicId) {
        try {
            memberCreditRestClient.patch()
                    .uri(baseUrl + "/api/v1/internal/members/{id}/credit/use", userPublicId)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(),
                            (req, res) -> {
                                if (res.getStatusCode().value() == 422) {
                                    throw new BusinessException(DocumentErrorCode.INSUFFICIENT_CREDIT);
                                }
                                log.warn("크레딧 차감 4xx 응답: status={}, publicId={}",
                                        res.getStatusCode(), userPublicId);
                                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
                            })
                    .toBodilessEntity();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("크레딧 차감 API 호출 실패: publicId={}, error={}", userPublicId, e.getMessage());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}

package com.gb.wallet.domain.transaction.scheduled.service;

import com.gb.wallet.domain.transaction.scheduled.dto.request.CreateScheduledTransferRequest;
import com.gb.wallet.domain.transaction.scheduled.dto.response.ScheduledTransferHistoryResponse;
import com.gb.wallet.domain.transaction.scheduled.dto.response.ScheduledTransferListResponse;
import com.gb.wallet.domain.transaction.scheduled.dto.response.ScheduledTransferResponse;

/**
 * 정기 송금(scheduled transfer) 도메인 서비스. 이번 사이클은 {@link #create}만 노출.
 *
 * <p>다음 사이클 추가 예정 메서드:
 * <ul>
 *   <li>{@code list(userPublicId)} — 내역 조회</li>
 *   <li>{@code cancel(userPublicId, publicId)} — 사용자 취소</li>
 *   <li>(시스템) 스케줄러 → {@code TransferService.execute} 호출 (별도 Runner 컴포넌트)</li>
 * </ul>
 */
public interface ScheduledTransferService {

    /**
     * 정기 송금 설정 — 설정 즉시 ACTIVE 상태로 INSERT + 다음 실행 예정일 계산.
     *
     * <p>송금 실행 API와 동일하게 {@code transferType}으로 INTERNAL/REMITTANCE 분기.
     * - INTERNAL_TRANSFER → {@code receiverPublicId} 필수. 수신자 wallet 존재 + 자기송금 차단 + receiver_name snapshot(MemberClient).
     * - REMITTANCE → {@code bankAccountPublicId} 필수. 본인 소유 + 활성 + 인증 토큰 검증 + receiver_name snapshot(bankAccount.holderName).
     *
     * <p>입력값 자체의 의미 위반(same-currency 위반·schedule_day 범위 초과 등)은 {@code COMMON4221}(422),
     * 형식 오류·계좌 미존재는 도메인 에러(400/403/404).
     */
    ScheduledTransferResponse create(String userPublicId, CreateScheduledTransferRequest request);

    /**
     * 본인 정기 송금 목록 조회 (페이지). {@code status}로 선택적 필터링.
     *
     * <p>정렬은 {@code created_at DESC} (최신 설정 우선). 페이지 메타(page/size/total_elements/total_pages)는
     * 응답에 함께 반환된다. 잘못된 {@code status} 값(허용 enum 외)은 {@code COMMON4001}.
     *
     * @param userPublicId 요청자(JWT public_id)
     * @param statusFilter ACTIVE / PAUSED / CANCELLED 또는 null(전체)
     * @param page         0-base 페이지 번호
     * @param size         페이지 크기
     */
    ScheduledTransferListResponse list(String userPublicId, String statusFilter, int page, int size);

    /**
     * 특정 정기 송금의 회차별 실행 이력(transactions) 페이지 조회.
     *
     * <p>회차 거래는 스케줄러가 {@code idempotency_key = "scheduled:{publicId}:{today}"} 형태로 INSERT한
     * transactions 행이다. {@code idempotency_key} prefix({@code "scheduled:{publicId}:"})로 검색한다.
     *
     * <p>본인 검증: 정기 송금이 미존재이거나 요청자 소유가 아니면 동일 {@code TRANSFER4001}로 모호 매핑
     * (정보 누설 방지). 회차가 0건이면 빈 배열 + total_elements=0으로 200 응답.
     *
     * @param userPublicId      요청자(JWT public_id)
     * @param transferPublicId  정기 송금 식별자(UUID)
     * @param page              0-base 페이지 번호
     * @param size              페이지 크기
     */
    ScheduledTransferHistoryResponse getHistory(
            String userPublicId, String transferPublicId, int page, int size);
}

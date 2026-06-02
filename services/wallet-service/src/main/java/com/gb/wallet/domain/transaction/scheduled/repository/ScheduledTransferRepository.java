package com.gb.wallet.domain.transaction.scheduled.repository;

import com.gb.wallet.domain.transaction.scheduled.entity.ScheduledTransfer;
import com.gb.wallet.global.common.enums.ScheduledTransferStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link ScheduledTransfer} CRUD.
 *
 * <p>내역 조회는 페이지네이션 적용 + status로 선택적 필터링한다. 정렬은 Service의 {@link Pageable}에 위임
 * (현 정책: created_at DESC). 인덱스 {@code idx_scheduled_transfers_user(user_public_id)}로 첫 컬럼 검색이
 * 빠르게 동작한다.
 *
 * <p>스케줄러(다음 사이클)에서 추가될 메서드 후보(미작성):
 * {@code findAllByStatusAndNextRunDateLessThanEqual(ScheduledTransferStatus, LocalDate)} —
 * 인덱스 {@code idx_scheduled_transfers_status_next}로 처리.
 */
public interface ScheduledTransferRepository extends JpaRepository<ScheduledTransfer, Long> {

    /** 사용자의 전체 정기 송금 페이지 조회 (status 무관). */
    Page<ScheduledTransfer> findByUserPublicId(String userPublicId, Pageable pageable);

    /** 사용자의 status 필터링 페이지 조회. */
    Page<ScheduledTransfer> findByUserPublicIdAndStatus(
            String userPublicId, ScheduledTransferStatus status, Pageable pageable);

    /**
     * 스케줄러가 실행 대상으로 가져갈 행 — {@code status} == ACTIVE & {@code next_run_date <= today}.
     * 인덱스 {@code idx_scheduled_transfers_status_next (status, next_run_date)}로 빠른 조회.
     *
     * <p>한 cron 트리거에서 일괄 가져와 각 행을 별도 트랜잭션(REQUIRES_NEW)으로 실행한다.
     * 보통 도래 행은 적어 페이지네이션 불필요(전체 List 반환).
     */
    List<ScheduledTransfer> findAllByStatusAndNextRunDateLessThanEqual(
            ScheduledTransferStatus status, LocalDate nextRunDate);
}

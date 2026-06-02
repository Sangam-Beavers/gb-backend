package com.gb.wallet.domain.transaction.scheduled.repository;

import com.gb.wallet.domain.transaction.scheduled.entity.ScheduledTransfer;
import com.gb.wallet.global.common.enums.ScheduledTransferStatus;
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
}

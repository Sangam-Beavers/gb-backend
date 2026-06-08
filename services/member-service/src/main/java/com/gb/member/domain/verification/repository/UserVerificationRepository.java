package com.gb.member.domain.verification.repository;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.verification.entity.UserVerification;
import com.gb.member.domain.verification.entity.VerificationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserVerificationRepository extends JpaRepository<UserVerification, Long> {

    /** 회원의 가장 최근 인증 요청 1건(상태 조회용). 없으면 empty. */
    Optional<UserVerification> findTopByMemberOrderByIdDesc(Member member);

    /** 회원에게 주어진 상태들 중 하나인 인증이 존재하는지 — 중복 제출(진행중/승인) 방지용. */
    boolean existsByMemberAndStatusIn(Member member, Collection<VerificationStatus> statuses);

    /**
     * 회원 id 묶음에 대해 가장 최근 verification 1건씩 batch 로드(관리자 회원 목록 표시용).
     * 두-쿼리 (이 메서드 → ids → service에서 map 구성) 전략으로 N+1을 피한다.
     */
    @Query("""
            SELECT v FROM UserVerification v
            WHERE v.member.id IN :memberIds
              AND v.id IN (
                SELECT MAX(v2.id) FROM UserVerification v2
                WHERE v2.member.id IN :memberIds
                GROUP BY v2.member.id
              )
            """)
    List<UserVerification> findLatestByMemberIds(@Param("memberIds") Collection<Long> memberIds);

    /** 상태별 카운트(관리자 통계). */
    long countByStatus(VerificationStatus status);
}

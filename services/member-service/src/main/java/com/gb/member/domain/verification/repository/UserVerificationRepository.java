package com.gb.member.domain.verification.repository;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.verification.entity.UserVerification;
import com.gb.member.domain.verification.entity.VerificationStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserVerificationRepository extends JpaRepository<UserVerification, Long> {

    /** 회원의 가장 최근 인증 요청 1건(상태 조회용). 없으면 empty. */
    Optional<UserVerification> findTopByMemberOrderByIdDesc(Member member);

    /** 회원에게 주어진 상태들 중 하나인 인증이 존재하는지 — 중복 제출(진행중/승인) 방지용. */
    boolean existsByMemberAndStatusIn(Member member, Collection<VerificationStatus> statuses);
}

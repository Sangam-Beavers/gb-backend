package com.gb.member.domain.member.repository;

import com.gb.member.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // 이 이메일을 쓰는 회원이 이미 있는지 확인 (중복 체크용)
    boolean existsByEmail(String email);

    // 이 닉네임을 쓰는 회원이 이미 있는지 확인 (중복 체크용)
    boolean existsByNickname(String nickname);
}
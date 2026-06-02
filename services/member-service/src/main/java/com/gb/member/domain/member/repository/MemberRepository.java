package com.gb.member.domain.member.repository;

import com.gb.member.domain.member.entity.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    // 이 이메일을 쓰는 회원이 이미 있는지 확인 (중복 체크용)
    boolean existsByEmail(String email);

    // 이 닉네임을 쓰는 회원이 이미 있는지 확인 (중복 체크용)
    boolean existsByNickname(String nickname);

    // 로그인 시 이메일로 회원 조회
    Optional<Member> findByEmail(String email);

    // 탈퇴(deleted_at)된 회원은 제외하고 publicId로 조회한다.
    // 프로젝트 관행: @SQLRestriction/@Where가 아니라 쿼리에 deleted_at IS NULL을 "명시"한다
    // (community BaseSoftDeleteEntity + repository 방식, database.md §357).
    // existsByEmail/existsByNickname/findByEmail은 deleted_at 필터를 걸지 않는다 — 탈퇴자
    // 이메일/닉네임도 여전히 "사용 중"으로 취급해 재가입을 막기 위함(의도된 동작, 지시서 §9).
    Optional<Member> findByPublicIdAndDeletedAtIsNull(String publicId);
}
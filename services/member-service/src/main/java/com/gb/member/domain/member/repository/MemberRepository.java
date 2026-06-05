package com.gb.member.domain.member.repository;

import com.gb.member.domain.member.entity.Member;
import java.util.Collection;
import java.util.List;
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

    // 이 publicId의 회원 row가 이미 있는지 확인.
    // 소셜(Google) 로그인 신규 회원은 토큰(public_id claim)은 있지만 members row는 아직 없을 수 있다.
    // 소셜 프로필 보완 시 "이미 완료(row 존재) = 미완료 아님"을 판별하는 데 쓴다(JIT 생성).
    boolean existsByPublicId(String publicId);

    // 표시정보 배치 조회(display-info) — 다른 서비스(community/wallet)의 작성자 닉네임/수신자 표시용.
    // 탈퇴(deleted_at) 회원은 결과에서 제외한다 — 호출 측(MemberClient)이 누락 id를 "Unknown" 폴백으로
    // 채우는 계약이라, 탈퇴자는 "없는 회원"과 동일하게 표시된다(표시정보 노출 중단).
    List<Member> findByPublicIdInAndDeletedAtIsNull(Collection<String> publicIds);

    // 이메일로 활성 회원 조회(by-email) — wallet validate-member(송금 수신자 검증)용.
    // findByEmail과 달리 탈퇴자를 제외한다: 탈퇴 회원은 송금 수신자가 될 수 없으므로 "없음"으로
    // 응답해야 한다(재가입 차단용 findByEmail의 무필터 정책과 용도가 다름 — 위 주석 참고).
    Optional<Member> findByEmailAndDeletedAtIsNull(String email);
}
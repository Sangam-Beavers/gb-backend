package com.gb.member.domain.member.repository;

import com.gb.member.domain.member.entity.Member;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // ===== Credit (internal) =====

    /**
     * 크레딧을 원자적으로 1 차감한다(이슈 #244). DB UPDATE 레벨에서 race condition을 방지한다.
     * doc_analysis_credit > 0 이고 활성 회원인 경우에만 차감하며, 영향받은 행 수(0 또는 1)를 반환한다.
     * 0 반환 = 크레딧 부족 또는 회원 없음 — 호출 측에서 구분해 적절한 예외를 던진다.
     * clearAutomatically = true: UPDATE 후 영속성 컨텍스트를 클리어해 이후 조회가 최신 값을 반환하게 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Member m
            SET m.docAnalysisCredit = m.docAnalysisCredit - 1
            WHERE m.publicId = :publicId
              AND m.deletedAt IS NULL
              AND m.docAnalysisCredit > 0
            """)
    int decrementCreditIfPositive(@Param("publicId") String publicId);

    // ===== Admin internal API =====

    /**
     * 관리자 회원 검색 — q가 비어 있으면 전체. 탈퇴자 포함은 정책상 제외(탈퇴 회원은 admin 목록 노출 안 함).
     * 이메일/이름/닉네임 부분일치(LIKE) — 발표용 단순 구현(인덱스 미사용 OK, 데이터 규모 작음).
     */
    @Query("""
            SELECT m FROM Member m
            WHERE m.deletedAt IS NULL
              AND m.isAdmin = false
              AND (:q IS NULL
                   OR LOWER(m.email) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(m.name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(m.nickname) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<Member> searchForAdmin(@Param("q") String q, Pageable pageable);

    long countByDeletedAtIsNull();

    long countByDeletedAtIsNullAndCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    // ===== 인구통계 집계 (Admin business-analytics) =====
    // 탈퇴자·관리자 계정은 제외(실 사용자 모수만). group-by 한 번으로 집계해 N+1을 피한다.
    // 반환은 Object[]{enum 또는 String, Long} — Service에서 버킷 DTO로 매핑한다
    // (aggregation은 Service 조합 허용, CLAUDE §4 Repository).

    @Query("""
            SELECT m.gender, COUNT(m) FROM Member m
            WHERE m.deletedAt IS NULL AND m.isAdmin = false
            GROUP BY m.gender
            """)
    List<Object[]> countGroupByGender();

    @Query("""
            SELECT m.ageRange, COUNT(m) FROM Member m
            WHERE m.deletedAt IS NULL AND m.isAdmin = false
            GROUP BY m.ageRange
            """)
    List<Object[]> countGroupByAgeRange();

    @Query("""
            SELECT m.nationality, COUNT(m) FROM Member m
            WHERE m.deletedAt IS NULL AND m.isAdmin = false
            GROUP BY m.nationality
            ORDER BY COUNT(m) DESC
            """)
    List<Object[]> countGroupByNationality();
}
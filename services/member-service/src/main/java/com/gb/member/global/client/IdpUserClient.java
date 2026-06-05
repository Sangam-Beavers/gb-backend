package com.gb.member.global.client;

/**
 * 외부 IdP(개발=Authentik / 운영=Cognito)에 회원을 등록(프로비저닝)하는 클라이언트.
 *
 * <p>방식 B에서 비밀번호는 우리 DB가 아니라 IdP가 보유·검증한다. 따라서 회원가입 시
 * 우리 로컬 회원 프로필을 만들기 전에, 같은 이메일/비밀번호로 IdP에도 사용자를 만들어 둬야
 * 가입한 사람이 곧바로 IdP 로그인 페이지(Authorization Code flow)에서 로그인할 수 있다.
 *
 * <p>로그인은 프론트가 IdP와 직접 수행하므로 백엔드엔 로그인 클라이언트가 없다. 이 회원가입 호출은
 * <b>관리자 권한 토큰</b>으로 IdP의 관리 API를 호출한다(자격은 환경변수로만 주입). Service는 이
 * 인터페이스에만 의존하므로, IdP 구현(Authentik↔Cognito)이 바뀌어도 Service 코드는 바뀌지 않는다.
 */
public interface IdpUserClient {

    /**
     * IdP에 사용자를 생성하고 비밀번호를 설정한 뒤, 그 사용자의 외부 식별자(subject)를 돌려준다.
     * 반환값은 우리 회원의 {@code authProviderId}에 저장해 "토큰의 sub ↔ 우리 회원"을 매핑한다.
     *
     * <p>아울러 우리 시스템 식별자({@code publicId})를 IdP 사용자 attribute로 저장한다. 이렇게 하면
     * IdP가 발급하는 토큰의 custom claim({@code public_id})으로 노출할 수 있어, 토큰만으로
     * "이 사용자의 publicId"를 바로 알 수 있다(토큰 sub ↔ publicId 매핑). publicId는 우리가 만드는
     * 값이라 IdP는 모르므로, 가입 시 우리가 먼저 생성해 함께 넘긴다.
     *
     * <p><b>부분실패 보상(11D member-idp-1):</b> 사용자 생성(①) 후 비밀번호 설정(②)이 실패하면 구현체가
     * 방금 만든 사용자를 <b>내부에서 보상 DELETE</b>해 "비밀번호 없는 고아 + 이메일 영구 가입불가"를 막은 뒤
     * 원래 에러를 전파한다. 따라서 이 메서드가 예외로 끝나면 IdP에 잔존물이 없는 것이 기본이다
     * (보상 자체가 실패한 이중 실패만 예외 — error 로그로 수동 정리 유도).
     *
     * @param email       로그인 아이디로 쓸 이메일(IdP username 겸용)
     * @param name        표시 이름
     * @param rawPassword 평문 비밀번호(IdP에만 저장된다 — 우리 DB에는 저장하지 않음)
     * @param publicId    우리 회원의 대외 식별자(UUID). IdP 사용자 attribute(public_id)로 저장된다
     * @return IdP가 부여한 사용자 식별자(Authentik의 경우 user uuid)
     */
    String provisionUser(String email, String name, String rawPassword, String publicId);

    /**
     * 가입 보상 삭제(best-effort): 방금 {@link #provisionUser}로 만든 IdP 사용자를 회수한다.
     *
     * <p>IdP-first 가입(11D member-idp-1·core-2)에서 IdP 프로비저닝은 성공했으나 <b>로컬 INSERT가
     * 실패</b>(닉네임 race·DB 장애)한 경우 호출한다 — 회수하지 않으면 그 이메일이 IdP username unique에
     * 막혀 영구 가입불가가 된다. 대상은 이 가입 요청이 반환받은 식별자뿐이며, 구현체는 정확 일치 검증으로
     * 타 사용자 오삭제를 차단한다. <b>어떤 실패도 던지지 않는다</b> — 호출 측의 원인 에러가 응답을 지배해야
     * 하므로 보상 실패는 로그(수동 정리)로만 남긴다.
     *
     * @param authProviderId 회수 대상 사용자 식별자(= {@link #provisionUser} 반환값, Authentik user uuid)
     */
    void deleteUserBestEffort(String authProviderId);

    /**
     * 기존 IdP 사용자의 비밀번호를 새 값으로 변경한다(비밀번호 재설정용).
     *
     * <p>비밀번호는 IdP가 보유하므로 변경도 IdP 관리 API(set_password)로 한다. 회원가입과 달리
     * 기존 사용자라 pk를 모르므로, email(=username)로 사용자를 먼저 조회해 pk를 얻은 뒤 set_password를 호출한다.
     *
     * @param email       대상 사용자 이메일(IdP username 겸용)
     * @param newPassword 새 평문 비밀번호(IdP에만 저장된다)
     */
    void changePassword(String email, String newPassword);

    /**
     * 탈퇴 처리: IdP의 해당 사용자를 비활성화한다({@code is_active=false}). 이후 IdP 로그인/토큰 발급이 막힌다.
     *
     * <p>하드 삭제가 아니라 비활성화다 — 로컬 soft delete(deleted_at)와 의미를 맞춰 복구·감사 기록을 보존한다.
     * 대상 사용자가 IdP에 없으면(이미 삭제 등) 멱등 통과한다(예외로 올리지 않음 — 팀 결정).
     *
     * @param authProviderId 가입 시 저장한 IdP 사용자 식별자(= Authentik user uuid). {@code members.auth_provider_id}.
     */
    void deactivateUser(String authProviderId);
}

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
     * @param email       로그인 아이디로 쓸 이메일(IdP username 겸용)
     * @param name        표시 이름
     * @param rawPassword 평문 비밀번호(IdP에만 저장된다 — 우리 DB에는 저장하지 않음)
     * @param publicId    우리 회원의 대외 식별자(UUID). IdP 사용자 attribute(public_id)로 저장된다
     * @return IdP가 부여한 사용자 식별자(Authentik의 경우 user uuid)
     */
    String provisionUser(String email, String name, String rawPassword, String publicId);
}

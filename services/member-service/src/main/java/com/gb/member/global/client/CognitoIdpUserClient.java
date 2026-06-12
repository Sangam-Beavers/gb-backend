package com.gb.member.global.client;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.member.global.exception.code.MemberErrorCode;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDisableUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

/**
 * 운영/스테이징 외부 IdP(AWS Cognito)의 관리 API를 호출해 회원을 등록하는 구현체.
 *
 * <p>{@link RealIdpUserClient}(Authentik)와 <b>같은 {@link IdpUserClient} 인터페이스</b>를 구현하므로
 * Service 코드는 무변경이다. 프로필로 분리되어 prod/stage에서만 이 빈이 뜬다(dev/test는 Authentik).
 *
 * <p>우리 풀은 이메일을 로그인 식별자로 쓰므로 Cognito Username = email 이다. 따라서 관리 API의
 * {@code username} 파라미터에 email을 그대로 넣는다. {@code authProviderId}로는 토큰 {@code sub}와
 * 일치해야 하는 Cognito 사용자 {@code sub}(UUID)를 저장한다 — 로그인 토큰의 sub ↔ 우리 회원 매핑.
 *
 * <p>가입 흐름(2콜):
 * <ol>
 *   <li>{@code AdminCreateUser}(MessageAction=SUPPRESS, email_verified=true, custom:public_id 저장)
 *       — 초대 메일을 보내지 않고 사용자만 생성. 응답에서 {@code sub}를 읽는다.</li>
 *   <li>{@code AdminSetUserPassword}(Permanent=true) — 영구 비밀번호 설정(임시비번 강제변경 상태 회피).</li>
 * </ol>
 * 2번이 실패하면 1번에서 만든 사용자를 보상 {@code AdminDeleteUser}로 회수해 "비번 없는 고아 + 이메일
 * 영구 가입불가"를 막는다(RealIdpUserClient와 동일 정책).
 *
 * <p>public_id를 토큰 claim으로 노출하는 것은 Cognito Pre Token Generation Lambda가 담당한다
 * (custom:public_id → public_id claim). 이 클라이언트는 custom:public_id 저장까지만 책임진다.
 *
 * <p>에러 매핑({@link RealIdpUserClient}의 idpStatusToError와 동일 정책 — 같은 실패가 dev/stage에서
 * 400/500으로 갈리지 않게 한다): username(=email) 중복 → {@link MemberErrorCode#EMAIL_ALREADY_EXISTS}
 * (MEMBER4002, 409), 그 외 4xx(비밀번호 정책 위반·파라미터 오류 등 클라이언트 입력 문제) →
 * {@link CommonErrorCode#INVALID_REQUEST}(COMMON4001, 400), 5xx·연결 실패 →
 * {@link CommonErrorCode#INTERNAL_SERVER_ERROR}(COMMON5000).
 */
@Slf4j
@Component
@Profile("prod | stage")
public class CognitoIdpUserClient implements IdpUserClient {

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;

    public CognitoIdpUserClient(
            CognitoIdentityProviderClient cognito,
            @Value("${auth.cognito.user-pool-id}") String userPoolId) {
        this.cognito = cognito;
        this.userPoolId = userPoolId;
    }

    @Override
    public String provisionUser(String email, String name, String rawPassword, String publicId) {
        // 1) 사용자 생성(초대메일 억제). email_verified=true로 두어 검증단계 없이 바로 로그인 가능하게 한다.
        String sub;
        try {
            AdminCreateUserResponse created = cognito.adminCreateUser(AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .messageAction(MessageActionType.SUPPRESS)
                    .userAttributes(
                            AttributeType.builder().name("email").value(email).build(),
                            AttributeType.builder().name("email_verified").value("true").build(),
                            AttributeType.builder().name("name").value(name).build(),
                            AttributeType.builder().name("custom:public_id").value(publicId).build())
                    .build());

            sub = extractSub(created.user() == null ? null : created.user().attributes());
            if (sub == null) {
                log.error("Cognito 사용자 생성 응답에 sub가 없습니다. email={}", email);
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
        } catch (UsernameExistsException e) {
            // 이미 가입된 이메일 → 진단 가능한 도메인 코드로 매핑(RealIdpUserClient와 동일).
            log.error("Cognito 사용자 생성 실패(이메일 중복): email={}", email);
            throw new BusinessException(MemberErrorCode.EMAIL_ALREADY_EXISTS);
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito 사용자 생성 실패: email={}, msg={}", email, e.getMessage());
            throw cognitoToError(e);
        }

        // 2) 영구 비밀번호 설정. 실패 시 방금 만든 사용자를 보상 삭제(고아 방지) 후 원래 에러 전파.
        try {
            cognito.adminSetUserPassword(AdminSetUserPasswordRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .password(rawPassword)
                    .permanent(true)
                    .build());
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito 비밀번호 설정 실패(사용자 생성 직후) — 보상 삭제 시도: email={}, msg={}",
                    email, e.getMessage());
            deleteByUsernameBestEffort(email);
            throw cognitoToError(e);
        }

        return sub;
    }

    @Override
    public void deleteUserBestEffort(String authProviderId) {
        // 가입 로컬 INSERT 실패 시 회수(best-effort). authProviderId=sub로 username을 찾아 삭제한다.
        if (authProviderId == null || authProviderId.isBlank()) {
            log.error("Cognito 보상 삭제 불가: authProviderId가 비어 있습니다.");
            return;
        }
        try {
            String username = findUsernameBySub(authProviderId);
            if (username == null) {
                log.warn("Cognito 보상 삭제: 대상 없음(이미 삭제 — 멱등 통과). sub={}", authProviderId);
                return;
            }
            cognito.adminDeleteUser(AdminDeleteUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build());
            log.info("Cognito 보상 삭제 완료(가입 로컬 실패 회수): sub={}", authProviderId);
        } catch (RuntimeException e) {
            log.error("Cognito 보상 삭제 실패 — 고아 잔존, 수동 정리 필요: sub={}, msg={}",
                    authProviderId, e.getMessage());
        }
    }

    @Override
    public void changePassword(String email, String newPassword) {
        // 비밀번호 재설정: username=email로 바로 영구 비밀번호 설정.
        try {
            cognito.adminSetUserPassword(AdminSetUserPasswordRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .password(newPassword)
                    .permanent(true)
                    .build());
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito 비밀번호 변경 실패: email={}, msg={}", email, e.getMessage());
            throw cognitoToError(e);
        }
    }

    @Override
    public void deactivateUser(String authProviderId) {
        // 탈퇴: sub로 username을 찾아 비활성화(AdminDisableUser). 하드 삭제 아님 — 로컬 soft delete와 의미 정합.
        if (authProviderId == null || authProviderId.isBlank()) {
            log.error("Cognito 비활성화 불가: authProviderId가 비어 있습니다(가입 프로비저닝 누락 의심).");
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        try {
            String username = findUsernameBySub(authProviderId);
            if (username == null) {
                // 대상이 없으면 멱등 통과(이미 삭제 등) — 로컬 soft delete는 정상 진행.
                log.warn("Cognito 비활성화 대상 사용자 없음(멱등 통과). sub={}", authProviderId);
                return;
            }
            cognito.adminDisableUser(AdminDisableUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build());
        } catch (BusinessException e) {
            throw e;
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito 사용자 비활성화 실패: sub={}, msg={}", authProviderId, e.getMessage());
            throw cognitoToError(e);
        }
    }

    /**
     * Cognito 예외를 본체 에러로 매핑한다: 4xx(비밀번호 정책 위반·파라미터 오류 등 클라이언트 입력 문제)
     * → COMMON4001(400), 5xx·기타 → COMMON5000(500). dev(Authentik) {@link RealIdpUserClient}의
     * idpStatusToError와 동일 정책 — 같은 실패가 환경에 따라 400/500으로 갈리지 않게 한다.
     */
    private BusinessException cognitoToError(CognitoIdentityProviderException e) {
        return new BusinessException(e.statusCode() >= 400 && e.statusCode() < 500
                ? CommonErrorCode.INVALID_REQUEST
                : CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * sub로 Cognito Username을 찾는다(ListUsers의 sub 필터). 관리 API는 username을 받으므로 sub→username 변환 필요.
     * 정확 일치 1건만 사용한다(오삭제·오변경 방지 — RealIdpUserClient의 정확일치 패턴과 동일). 0건이면 null.
     */
    private String findUsernameBySub(String sub) {
        ListUsersResponse list = cognito.listUsers(ListUsersRequest.builder()
                .userPoolId(userPoolId)
                .filter("sub = \"" + sub + "\"")
                .limit(2)
                .build());

        List<UserType> matched = list.users();
        if (matched == null || matched.isEmpty()) {
            return null;
        }
        if (matched.size() > 1) {
            log.error("Cognito sub 정확 일치가 2건 이상 — 오작업 방지 위해 중단. sub={}, count={}",
                    sub, matched.size());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
        return matched.get(0).username();
    }

    /** 보상 삭제용: username(=email)으로 바로 삭제(best-effort, 던지지 않음). 프로비저닝 부분실패 회수에 사용. */
    private void deleteByUsernameBestEffort(String username) {
        try {
            cognito.adminDeleteUser(AdminDeleteUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build());
            log.info("Cognito 보상 삭제 완료(프로비저닝 부분실패 회수): username={}", username);
        } catch (RuntimeException e) {
            log.error("Cognito 보상 삭제 실패 — 고아 잔존, 수동 정리 필요: username={}, msg={}",
                    username, e.getMessage());
        }
    }

    /** 사용자 속성 목록에서 sub 값을 꺼낸다. 없으면 null. */
    private String extractSub(List<AttributeType> attributes) {
        if (attributes == null) {
            return null;
        }
        return attributes.stream()
                .filter(a -> "sub".equals(a.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
    }
}

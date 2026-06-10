package com.gb.member.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.member.global.exception.code.MemberErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

/**
 * {@link CognitoIdpUserClient} 단위 테스트 — AWS SDK를 Mockito로 가려 컨텍스트 없이 검증한다.
 *
 * <p>핵심: 가입(생성+비번설정+sub 반환), 이메일 중복→MEMBER4002, 비번설정 실패 시 보상 삭제,
 * sub→username 해석(탈퇴/보상삭제), 입력 가드(공백 fail-fast). 실제 Cognito는 호출하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class CognitoIdpUserClientTest {

    private static final String POOL_ID = "ap-northeast-2_TEST";

    @Mock
    private CognitoIdentityProviderClient cognito;

    private CognitoIdpUserClient client;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        client = new CognitoIdpUserClient(cognito, POOL_ID);
    }

    @Test
    @DisplayName("provisionUser: 사용자 생성 후 비번 설정하고 sub를 반환한다")
    void provisionUser_성공_sub반환() {
        AdminCreateUserResponse created = AdminCreateUserResponse.builder()
                .user(UserType.builder()
                        .attributes(AttributeType.builder().name("sub").value("sub-123").build())
                        .build())
                .build();
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(created);

        String sub = client.provisionUser("a@example.com", "홍길동", "P@ssw0rd!", "pub-1");

        assertThat(sub).isEqualTo("sub-123");
        verify(cognito).adminCreateUser(any(AdminCreateUserRequest.class));
        verify(cognito).adminSetUserPassword(any(AdminSetUserPasswordRequest.class));
    }

    @Test
    @DisplayName("provisionUser: 이메일(username) 중복이면 MEMBER4002로 매핑하고 비번 설정은 호출하지 않는다")
    void provisionUser_중복_MEMBER4002() {
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenThrow(UsernameExistsException.builder().message("exists").build());

        assertThatThrownBy(() -> client.provisionUser("a@example.com", "홍길동", "P@ss", "pub-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.EMAIL_ALREADY_EXISTS);

        verify(cognito, never()).adminSetUserPassword(any(AdminSetUserPasswordRequest.class));
    }

    @Test
    @DisplayName("provisionUser: 비번 설정 실패 시 보상 삭제(AdminDeleteUser) 후 COMMON5000 전파")
    void provisionUser_비번실패_보상삭제() {
        AdminCreateUserResponse created = AdminCreateUserResponse.builder()
                .user(UserType.builder()
                        .attributes(AttributeType.builder().name("sub").value("sub-123").build())
                        .build())
                .build();
        when(cognito.adminCreateUser(any(AdminCreateUserRequest.class))).thenReturn(created);
        when(cognito.adminSetUserPassword(any(AdminSetUserPasswordRequest.class)))
                .thenThrow(CognitoIdentityProviderException.builder().message("boom").build());

        assertThatThrownBy(() -> client.provisionUser("a@example.com", "홍길동", "P@ss", "pub-1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);

        // 생성 직후 부분실패 → username(email)으로 보상 삭제가 호출돼야 한다.
        verify(cognito).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    @Test
    @DisplayName("deactivateUser: sub로 username을 찾아 AdminDisableUser 호출")
    void deactivateUser_정상_비활성화() {
        ListUsersResponse list = ListUsersResponse.builder()
                .users(UserType.builder().username("a@example.com").build())
                .build();
        when(cognito.listUsers(any(ListUsersRequest.class))).thenReturn(list);

        client.deactivateUser("sub-123");

        verify(cognito).adminDisableUser(any(AdminDisableUserRequest.class));
    }

    @Test
    @DisplayName("deactivateUser: 대상 사용자가 없으면 멱등 통과(비활성화 미호출)")
    void deactivateUser_대상없음_멱등통과() {
        when(cognito.listUsers(any(ListUsersRequest.class)))
                .thenReturn(ListUsersResponse.builder().build());

        assertThatCode(() -> client.deactivateUser("sub-x")).doesNotThrowAnyException();
        verify(cognito, never()).adminDisableUser(any(AdminDisableUserRequest.class));
    }

    @Test
    @DisplayName("deactivateUser: authProviderId가 공백이면 HTTP 전에 COMMON5000으로 fail-fast")
    void deactivateUser_공백_failFast() {
        assertThatThrownBy(() -> client.deactivateUser("  "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.INTERNAL_SERVER_ERROR);
        verify(cognito, never()).listUsers(any(ListUsersRequest.class));
    }

    @Test
    @DisplayName("deleteUserBestEffort: sub로 찾아 삭제하며, 실패해도 예외를 던지지 않는다")
    void deleteUserBestEffort_실패해도_무예외() {
        when(cognito.listUsers(any(ListUsersRequest.class)))
                .thenThrow(CognitoIdentityProviderException.builder().message("boom").build());

        assertThatCode(() -> client.deleteUserBestEffort("sub-123")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("changePassword: username(email)으로 영구 비밀번호를 설정한다")
    void changePassword_성공() {
        client.changePassword("a@example.com", "NewP@ss1!");
        verify(cognito).adminSetUserPassword(any(AdminSetUserPasswordRequest.class));
    }
}

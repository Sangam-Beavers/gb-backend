package com.gb.wallet;

import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.MemberClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 전체 Spring 컨텍스트 로딩 스모크 테스트.
 *
 * <p>test 프로파일을 활성화해 {@code application-test.yml}의 H2 datasource를 사용한다.
 * 외부 클라이언트 구현체({@code MockMemberClient}, {@code MockBankClient})는 dev/stage 프로파일에서만
 * 등록되므로, 테스트에서는 인터페이스를 {@code @MockitoBean}으로 가린다.
 *
 * <p>TODO: 실제 구현체(RealXxxClient @Profile("prod"))가 생기면 test/prod 분리 정책 재정리.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletServiceApplicationTests {

    @MockitoBean
    private MemberClient memberClient;

    @MockitoBean
    private BankClient bankClient;

    @Test
    void contextLoads() {
    }
}

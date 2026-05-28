package com.gb.wallet;

import com.gb.wallet.global.client.MemberClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 전체 Spring 컨텍스트 로딩 스모크 테스트.
 *
 * <p>test 프로파일을 활성화해 {@code application-test.yml}의 H2 datasource를 사용한다.
 * {@link MemberClient} 구현체({@code MockMemberClient})는 {@code @Profile("dev")}라
 * test 프로파일에선 등록되지 않으므로, 외부 의존을 가리는 표준 방식대로 여기서 mock 빈으로 주입한다.
 *
 * <p>TODO: member-service 구현 후 RealMemberClient(@Profile("prod"))가 생기면
 * test/prod 분리 정책을 다시 정리.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletServiceApplicationTests {

    @MockitoBean
    private MemberClient memberClient;

    @Test
    void contextLoads() {
    }
}

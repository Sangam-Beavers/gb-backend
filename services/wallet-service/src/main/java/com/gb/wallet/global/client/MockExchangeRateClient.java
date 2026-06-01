package com.gb.wallet.global.client;

import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발/테스트용 환율 클라이언트. 고정 환율표를 반환한다(외부 환율 API 미연동 단계).
 *
 * <p>충전의 {@code MockBankClient}와 같은 역할 — 실제 연동 전까지 흐름을 검증할 수 있게 한다.
 * 운영 전환 시 {@code RealExchangeRateClient}(@Profile "prod")를 추가하고 이 빈은 dev/stage로 한정한다.
 *
 * <p>{@code test} 프로파일도 포함한다 — {@code @SpringBootTest}(통합 컨텍스트 로딩)가 ExchangeService를
 * 생성할 때 ExchangeRateClient 빈이 필요하기 때문이다. test를 빼면 NoSuchBeanDefinitionException으로
 * 컨텍스트 로딩이 깨진다. (MockBankClient는 통합테스트에서 @MockitoBean으로 가려 test 미포함이지만,
 * 환율 클라이언트는 그 처리가 없어 여기서 직접 포함한다.)
 *
 * <p>⚠️ 환율 값은 임시 고정값이다. 운영 정책/실시간 환율과 무관하며, 외부 API 연동 시 대체된다.
 */
@Profile({"dev", "stage", "test"})
@Component
public class MockExchangeRateClient implements ExchangeRateClient {

    /** "1 외화 → KRW" 고정 환율표. KRW는 기준(1). */
    private static final Map<CurrencyType, BigDecimal> RATES = new EnumMap<>(CurrencyType.class);

    static {
        RATES.put(CurrencyType.KRW, new BigDecimal("1"));
        RATES.put(CurrencyType.USD, new BigDecimal("1380"));
        RATES.put(CurrencyType.PHP, new BigDecimal("24.5"));
        RATES.put(CurrencyType.VND, new BigDecimal("0.054"));
    }

    @Override
    public BigDecimal getRateToKrw(CurrencyType currency) {
        // 표에 없는 통화는 null 반환 → 호출 측(Service)이 미지원 통화로 판단해 TRANSFER4002 처리.
        return RATES.get(currency);
    }
}

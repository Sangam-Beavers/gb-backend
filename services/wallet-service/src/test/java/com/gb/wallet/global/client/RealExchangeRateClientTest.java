package com.gb.wallet.global.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.wallet.global.common.enums.CurrencyType;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

/**
 * {@link RealExchangeRateClient} 단위 테스트(Redisson mock). 환율 키를 읽는 흐름을 검증한다.
 *
 * <p><b>핵심 회귀 방지:</b> exchange-updater(Python)/mcp-exchange 는 환율을 <b>평문 UTF-8 문자열</b>
 * ({@code "17.5"})로 Redis 에 쓴다. wallet-service Redisson 의 기본 코덱은 {@code Kryo5Codec} 이라
 * 그냥 {@code getBucket(key)} 로 읽으면 평문 값을 Kryo 객체로 역직렬화하려다
 * {@code KryoException: Encountered unregistered class ID} 가 터져 {@code /wallets/exchange-rates}
 * 가 500(COMMON5000)으로 깨졌다(stage 운영 장애). 그래서 환율 버킷은 반드시 {@link StringCodec}
 * 으로 읽어야 하며, 본 테스트가 코덱이 기본값으로 되돌아가는 회귀를 막는다.
 *
 * <p>{@code RealExchangeRateClient} 는 필드 주입({@code @Autowired @Lazy RedissonClient})이라
 * {@code @InjectMocks} 가 mock 을 그대로 꽂는다(PinVerificationStoreTest 패턴).
 */
@ExtendWith(MockitoExtension.class)
class RealExchangeRateClientTest {

    @Mock private RedissonClient redissonClient;
    @InjectMocks private RealExchangeRateClient client;

    private static final String KEY_USD = "rate:KRW-USD";
    private static final String KEY_VND = "rate:KRW-VND";
    private static final String KEY_USD_PREV = "rate:KRW-USD:prev";

    /** {@code getBucket(key, <codec>)} 호출을 mock 하고, 해당 버킷의 {@code get()} 이 value 를 돌려주게 한다. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private RBucket stubBucket(String key, String value) {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(eq(key), any(Codec.class))).willReturn(bucket);
        given(bucket.get()).willReturn(value);
        return bucket;
    }

    @Test
    @DisplayName("핵심 회귀: 환율 버킷은 StringCodec 으로 읽는다 (기본 Kryo5Codec 이면 평문값 디코드 실패 → 500)")
    void getRateToKrw_StringCodec로_읽는다() {
        stubBucket(KEY_USD, "0.00072");

        client.getRateToKrw(CurrencyType.USD);

        // writer(Python)가 평문 문자열로 쓰므로 reader 도 반드시 StringCodec 이어야 한다.
        verify(redissonClient).getBucket(KEY_USD, StringCodec.INSTANCE);
    }

    @Test
    @DisplayName("getRateToKrw: Redis 의 '1 KRW→X 외화' 값을 역수(1/X)로 변환해 '1 외화→KRW' 로 반환")
    void getRateToKrw_역수변환() {
        stubBucket(KEY_VND, "17.5"); // 1 KRW = 17.5 VND

        BigDecimal rate = client.getRateToKrw(CurrencyType.VND);

        // 1 / 17.5 = 0.057142857... → scale 8, HALF_UP
        assertThat(rate).isEqualByComparingTo("0.05714286");
    }

    @Test
    @DisplayName("getRateToKrw: USD 처럼 작은 값도 역수 변환 정확 (1 / 0.00072)")
    void getRateToKrw_USD_역수변환() {
        stubBucket(KEY_USD, "0.00072");

        BigDecimal rate = client.getRateToKrw(CurrencyType.USD);

        // 1 / 0.00072 = 1388.8888... → scale 8, HALF_UP
        assertThat(rate).isEqualByComparingTo("1388.88888889");
    }

    @Test
    @DisplayName("getRateToKrw(KRW): 자기 환율은 1, Redis 조회 없음")
    void getRateToKrw_KRW는1_Redis안봄() {
        assertThat(client.getRateToKrw(CurrencyType.KRW)).isEqualByComparingTo("1");
        verifyNoInteractions(redissonClient);
    }

    @Test
    @DisplayName("키 없음(cron 미실행/TTL 만료): null 반환 (호출 측에서 TRANSFER4002 처리)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void getRateToKrw_키없음_null() {
        RBucket bucket = mock(RBucket.class);
        given(redissonClient.getBucket(eq(KEY_USD), any(Codec.class))).willReturn(bucket);
        // bucket.get() 기본 반환값 null = 키 없음

        assertThat(client.getRateToKrw(CurrencyType.USD)).isNull();
    }

    @Test
    @DisplayName("값이 0 이하: null 반환 (역수 분모 0 방지)")
    void getRateToKrw_0이하_null() {
        stubBucket(KEY_USD, "0");

        assertThat(client.getRateToKrw(CurrencyType.USD)).isNull();
    }

    @Test
    @DisplayName("값 파싱 실패(비정상 문자열): null 반환 (NumberFormatException 흡수)")
    void getRateToKrw_파싱실패_null() {
        stubBucket(KEY_USD, "not-a-number");

        assertThat(client.getRateToKrw(CurrencyType.USD)).isNull();
    }

    @Test
    @DisplayName("getPrevRateToKrw: ':prev' 접미사 키를 StringCodec 으로 읽어 역수 변환")
    void getPrevRateToKrw_prev키_StringCodec() {
        stubBucket(KEY_USD_PREV, "0.00071");

        BigDecimal prev = client.getPrevRateToKrw(CurrencyType.USD);

        // 1 / 0.00071 = 1408.450704225... → scale 8, HALF_UP
        assertThat(prev).isEqualByComparingTo("1408.45070423");
        verify(redissonClient).getBucket(KEY_USD_PREV, StringCodec.INSTANCE);
    }
}

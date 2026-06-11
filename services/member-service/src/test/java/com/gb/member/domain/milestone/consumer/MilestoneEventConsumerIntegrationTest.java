package com.gb.member.domain.milestone.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.gb.member.domain.member.entity.Member;
import com.gb.member.domain.member.entity.TrustGrade;
import com.gb.member.domain.member.repository.MemberRepository;
import com.gb.member.domain.milestone.entity.MemberMilestone;
import com.gb.member.domain.milestone.entity.MilestoneType;
import com.gb.member.domain.milestone.repository.MemberMilestoneRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 마일스톤 이벤트 수신 통합 테스트 (Phase 2 — BE-4 / Phase 3 — BE-7.
 * EmbeddedKafka + H2, 실브로커 불필요 — 스파이크 결정 6).
 *
 * <p>발행(와이어 JSON — 발행측 클래스 미사용) → @KafkaListener 수신 → 역직렬화(snake_case/Instant) →
 * member_milestones 저장 → TrustGradeService 재계산까지 끝-끝으로 검증한다. 중복 발행(같은 user ×
 * milestone) 시 1건만 저장되는 자연 멱등(UNIQUE/선검사)도 함께 본다.
 *
 * <p>테스트 페이로드를 발행측 DTO가 아닌 <b>raw JSON 문자열</b>로 보내는 이유: 토픽 계약(스파이크 결정 2
 * 스키마 — snake_case·ISO-8601 Z)이 컨슈머측 역직렬화 설정과 맞는지를 와이어 레벨에서 고정하기 위함.
 *
 * <p>partitions=1 — 모든 레코드가 전역 순서로 소비되므로, "마지막에 보낸 이벤트가 처리됨"을 폴링으로
 * 확인하면 그 앞의 중복 이벤트도 처리 완료임이 보장된다(별도 latch 불필요).
 *
 * <p>{@code gb.kafka.milestone-consumer.auto-startup} — application-test.yml이 false로 꺼 둔 리스너를
 * 이 테스트만 인라인 프로퍼티로 켠다(브로커가 EmbeddedKafka로 실재하므로).
 */
@SpringBootTest(properties = "gb.kafka.milestone-consumer.auto-startup=true")
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1,
        topics = {MilestoneEventConsumer.WALLET_TOPIC, MilestoneEventConsumer.DOCUMENT_TOPIC,
                MilestoneEventConsumer.COMMUNITY_TOPIC},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class MilestoneEventConsumerIntegrationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(15);

    /** 방식 B 검표원 — 테스트엔 IdP가 없으므로 JwtDecoder를 가린다(컨텍스트 테스트 동일 패턴). */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberMilestoneRepository memberMilestoneRepository;
    @Autowired private EmbeddedKafkaBroker embeddedKafka;

    private static KafkaTemplate<String, String> producer;

    @AfterAll
    static void closeProducer() {
        if (producer != null) {
            producer.destroy();
        }
    }

    @Test
    @DisplayName("발행→수신: BANK_ACCOUNT_CONNECTED 이벤트가 member_milestones에 저장되고 등급이 VERIFIED→CONNECTED로 오른다")
    void 발행_수신_저장_등급상승() {
        Member member = persistVerifiedMember("linh");
        String userPublicId = member.getPublicId();

        send(userPublicId, milestoneJson(userPublicId, "BANK_ACCOUNT_CONNECTED", "wallet-service"));

        awaitUntil(() -> memberMilestoneRepository
                .existsByUserPublicIdAndMilestoneType(userPublicId, MilestoneType.BANK_ACCOUNT_CONNECTED));

        List<MemberMilestone> milestones = memberMilestoneRepository.findAllByUserPublicId(userPublicId);
        assertThat(milestones).hasSize(1);
        assertThat(milestones.get(0).getMilestoneType()).isEqualTo(MilestoneType.BANK_ACCOUNT_CONNECTED);
        assertThat(milestones.get(0).getAchievedAt())
                .as("achieved_at = 발행 occurred_at(UTC) 스냅샷 — ISO-8601 Z 역직렬화 확인")
                .isEqualTo(LocalDateTime.of(2026, 6, 10, 5, 21, 8));

        assertThat(reload(userPublicId).getTrustGrade())
                .as("같은 tx에서 TrustGradeService.recalculate — VERIFIED + 계좌 연결 = CONNECTED")
                .isEqualTo(TrustGrade.CONNECTED);
    }

    @Test
    @DisplayName("Phase 3 BE-7: document-service 토픽(DOCUMENT_ANALYZED)을 수신해 member_milestones에 저장된다")
    void document_토픽_DOCUMENT_ANALYZED_저장() {
        // TRUSTED 상태 회원 세팅(BANK_ACCOUNT_CONNECTED + FIRST_TRANSACTION_COMPLETED 선행)
        Member member = persistVerifiedMember("fatima");
        String userPublicId = member.getPublicId();

        // wallet 토픽으로 체인 마일스톤 먼저 기록해 TRUSTED 달성
        sendTo(MilestoneEventConsumer.WALLET_TOPIC, userPublicId,
                milestoneJson(userPublicId, "BANK_ACCOUNT_CONNECTED", "wallet-service"));
        sendTo(MilestoneEventConsumer.WALLET_TOPIC, userPublicId,
                milestoneJson(userPublicId, "FIRST_TRANSACTION_COMPLETED", "wallet-service"));
        awaitUntil(() -> memberMilestoneRepository
                .existsByUserPublicIdAndMilestoneType(userPublicId, MilestoneType.FIRST_TRANSACTION_COMPLETED));

        // document 토픽으로 보너스 마일스톤 발행
        sendTo(MilestoneEventConsumer.DOCUMENT_TOPIC, userPublicId,
                milestoneJson(userPublicId, "DOCUMENT_ANALYZED", "document-service"));
        awaitUntil(() -> memberMilestoneRepository
                .existsByUserPublicIdAndMilestoneType(userPublicId, MilestoneType.DOCUMENT_ANALYZED));

        assertThat(memberMilestoneRepository.findAllByUserPublicId(userPublicId))
                .extracting(MemberMilestone::getMilestoneType)
                .contains(MilestoneType.DOCUMENT_ANALYZED);
        // DOCUMENT_ANALYZED 1개로는 GOLD 조건(보너스 2개) 미달 — 여전히 TRUSTED
        assertThat(reload(userPublicId).getTrustGrade()).isEqualTo(TrustGrade.TRUSTED);
    }

    @Test
    @DisplayName("중복 발행: 같은 (user, milestone) 이벤트 2회 수신 시 1건만 저장(자연 멱등), 후속 이벤트로 TRUSTED까지 정상 진행")
    void 중복발행_1건만_저장_멱등() {
        Member member = persistVerifiedMember("minh");
        String userPublicId = member.getPublicId();

        // 같은 마일스톤 2회(서로 다른 event_id — 발행측 "매번 발행" 전략 모사) + 후속 마일스톤 1회.
        // partitions=1 + 키 동일이라 순서 보장 — 마지막(FIRST_TRANSACTION_COMPLETED) 처리 확인이
        // 앞 중복분 처리 완료를 함께 보장한다.
        send(userPublicId, milestoneJson(userPublicId, "BANK_ACCOUNT_CONNECTED", "wallet-service"));
        send(userPublicId, milestoneJson(userPublicId, "BANK_ACCOUNT_CONNECTED", "wallet-service"));
        send(userPublicId, milestoneJson(userPublicId, "FIRST_TRANSACTION_COMPLETED", "wallet-service"));

        awaitUntil(() -> memberMilestoneRepository
                .existsByUserPublicIdAndMilestoneType(userPublicId, MilestoneType.FIRST_TRANSACTION_COMPLETED));

        List<MemberMilestone> milestones = memberMilestoneRepository.findAllByUserPublicId(userPublicId);
        assertThat(milestones)
                .as("중복 BANK_ACCOUNT_CONNECTED는 1건으로 수렴 — (user, milestone) 자연 멱등")
                .extracting(MemberMilestone::getMilestoneType)
                .containsExactlyInAnyOrder(
                        MilestoneType.BANK_ACCOUNT_CONNECTED, MilestoneType.FIRST_TRANSACTION_COMPLETED);

        assertThat(reload(userPublicId).getTrustGrade())
                .as("VERIFIED + 계좌 연결 + 첫 거래 = TRUSTED")
                .isEqualTo(TrustGrade.TRUSTED);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 신분증 인증을 마친(Lv2) 회원을 커밋한다 — 이벤트 수신 시 CONNECTED+ 산정의 전제. */
    private Member persistVerifiedMember(String seed) {
        Member member = Member.builder()
                .publicId(UUID.randomUUID().toString())
                .email(seed + "@example.com")
                .name(seed)
                .nickname("nick-" + seed)
                .nationality("VN")
                .language("vi")
                .gender(com.gb.member.domain.member.entity.Gender.MALE)
                .ageRange(com.gb.member.domain.member.entity.AgeRange.TWENTIES)
                .authProviderId("idp-sub-" + seed)
                .termsAgreed(true)
                .privacyAgreed(true)
                .consentAgreedAt(LocalDateTime.now())
                .build();
        member.markVerified();
        member.applyTrustGrade(TrustGrade.VERIFIED);
        return memberRepository.saveAndFlush(member);
    }

    private Member reload(String userPublicId) {
        return memberRepository.findByPublicIdAndDeletedAtIsNull(userPublicId).orElseThrow();
    }

    /** 스파이크 결정 2 스키마 그대로의 와이어 JSON(snake_case). sourceService = 발행 서비스 식별자. */
    private static String milestoneJson(String userPublicId, String milestoneType, String sourceService) {
        return """
                {
                  "event_id": "%s",
                  "event_type": "MILESTONE_ACHIEVED",
                  "milestone_type": "%s",
                  "user_public_id": "%s",
                  "occurred_at": "2026-06-10T05:21:08Z",
                  "source_service": "%s",
                  "schema_version": 1
                }""".formatted(UUID.randomUUID(), milestoneType, userPublicId, sourceService);
    }

    /** wallet 토픽 발행 — 기존 테스트 호환 단축 헬퍼. */
    private void send(String userPublicId, String json) {
        sendTo(MilestoneEventConsumer.WALLET_TOPIC, userPublicId, json);
    }

    /** 메시지 키 = user_public_id(결정 2 — 유저 단위 순서 보장)로 지정 토픽에 발행한다. */
    private void sendTo(String topic, String userPublicId, String json) {
        if (producer == null) {
            Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafka);
            ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(
                    props, new StringSerializer(), new StringSerializer());
            producer = new KafkaTemplate<>(factory);
        }
        producer.send(topic, userPublicId, json);
        producer.flush();
    }

    /** 조건 충족까지 폴링(200ms 간격, 최대 15s) — 컨테이너 기동·파티션 할당 시간을 흡수한다. */
    private static void awaitUntil(Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.get())
                .as("수신 처리 대기 시간(%s) 초과 — 컨슈머가 이벤트를 처리하지 못했다", AWAIT_TIMEOUT)
                .isTrue();
    }
}

package com.gb.member.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.member.domain.milestone.dto.event.MilestoneAchievedEvent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka Consumer 설정 (Phase 2 — 마일스톤 이벤트 수신, BE-4. 스파이크 결정 5·6).
 *
 * <p>오토컨피그에 기대지 않고 명시 구성한다 — wallet의 KafkaProducerConfig와 같은 이유 2가지
 * (① 역직렬화에 Spring Boot 전역 ObjectMapper(snake_case + JavaTimeModule + 미지 필드 무시)를 명시
 * 주입, ② Boot 마이너 버전별 KafkaProperties API 변동과 무관하게 동작 고정).
 *
 * <p><b>에러 처리 파이프라인(결정 5):</b>
 * <ul>
 *   <li><b>역직렬화 실패(poison pill):</b> {@link ErrorHandlingDeserializer}가 예외를 헤더로 감싸
 *       레코드를 통과시킨다 — 컨슈머가 같은 레코드에서 무한 정지하지 않고, DefaultErrorHandler가
 *       비재시도 예외로 분류해 곧장 DLT로 보낸다(원본 byte[] 그대로).</li>
 *   <li><b>처리 실패(DB 등):</b> 3회 재시도(backoff 1s) 후 DLT.</li>
 *   <li><b>DLT 토픽:</b> {@code {원토픽}.dlt} (스파이크 표기 그대로 소문자 — 기본 suffix ".DLT"를
 *       사용하지 않고 resolver로 명시). 파티션은 지정하지 않아(-1) DLT 토픽 파티션 수와 무관하게 발행된다.
 *       모니터링은 Phase 2에선 로그 알람 수준.</li>
 * </ul>
 */
@Configuration
public class KafkaConsumerConfig {

    /** DLT 토픽 suffix — 스파이크 결정 5: {토픽}.dlt (소문자). */
    private static final String DLT_SUFFIX = ".dlt";

    /** 재시도 간격(ms)·횟수 — 결정 5: 3회, backoff 1s. (FixedBackOff maxAttempts = 재시도 횟수) */
    private static final long RETRY_BACKOFF_MS = 1_000L;
    private static final long RETRY_MAX_ATTEMPTS = 3L;

    /** 브로커 주소 — 환경변수 KAFKA_BOOTSTRAP_SERVERS 주입(결정 6). application.yaml 참조. */
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, MilestoneAchievedEvent> milestoneConsumerFactory(ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // 오프셋은 컨테이너(AckMode.BATCH)가 커밋한다 — 클라이언트 자동 커밋(기본 true)을 꺼서
        // "처리 전 커밋"으로 인한 유실을 막는다(at-least-once — 중복은 수신측 자연 멱등이 흡수).
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 신규 그룹 최초 기동 시 토픽 처음부터 — Producer(BE-3)가 먼저 배포돼 쌓인 이벤트(보관 7일)도
        // Consumer(BE-4)가 늦게 떠서 따라잡는다(이슈 BE-3 §Etc 전제).
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // 대상 타입을 명시한 JsonDeserializer(전역 ObjectMapper) — 발행측 __TypeId__ 헤더는 무시한다
        // (셋째 인자 useHeadersIfPresent=false. MSA 경계라 발행측 클래스명은 신뢰/사용하지 않음).
        JsonDeserializer<MilestoneAchievedEvent> valueDeserializer =
                new JsonDeserializer<>(MilestoneAchievedEvent.class, objectMapper, false);

        return new DefaultKafkaConsumerFactory<>(
                props,
                new StringDeserializer(),
                // poison pill 방지 — 역직렬화 예외를 헤더에 실어 통과시키고 에러 핸들러가 DLT 처리.
                new ErrorHandlingDeserializer<>(valueDeserializer));
    }

    /**
     * DLT 발행용 템플릿(raw bytes) — 역직렬화 실패 레코드는 원본 byte[] 그대로 DLT에 보존한다.
     */
    @Bean
    public KafkaTemplate<String, byte[]> milestoneDltBytesKafkaTemplate() {
        Map<String, Object> props = dltProducerProps();
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), new ByteArraySerializer()));
    }

    /**
     * DLT 발행용 템플릿(객체) — 역직렬화는 됐지만 처리(재시도 소진)에 실패한 레코드를 JSON으로 발행한다.
     */
    @Bean
    public KafkaTemplate<String, Object> milestoneDltJsonKafkaTemplate(ObjectMapper objectMapper) {
        Map<String, Object> props = dltProducerProps();
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), valueSerializer));
    }

    private Map<String, Object> dltProducerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // DLT 발행은 컨슈머 스레드에서 동기 대기된다 — 브로커 이상 시 무한 블로킹(기본 60s) 방지 캡.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000L);
        return props;
    }

    @Bean
    public DefaultErrorHandler milestoneErrorHandler(
            @Qualifier("milestoneDltBytesKafkaTemplate") KafkaTemplate<String, byte[]> bytesTemplate,
            @Qualifier("milestoneDltJsonKafkaTemplate") KafkaTemplate<String, Object> jsonTemplate) {
        // 페이로드 타입별 템플릿 라우팅: 역직렬화 실패 = byte[](원본 보존), 처리 실패 = 역직렬화된 객체(JSON).
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, jsonTemplate);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                templates,
                // {원토픽}.dlt — 파티션 -1 = 미지정(DLT 토픽 파티션 수가 원토픽과 달라도 안전).
                (ConsumerRecord<?, ?> failedRecord, Exception ex) ->
                        new TopicPartition(failedRecord.topic() + DLT_SUFFIX, -1));

        // FixedBackOff(1s, 3) = 최초 1회 + 재시도 3회, 소진 시 recoverer(DLT). 역직렬화 예외는
        // DefaultErrorHandler 기본 분류상 비재시도라 곧장 DLT로 간다(poison pill 즉시 격리).
        return new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_BACKOFF_MS, RETRY_MAX_ATTEMPTS));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, MilestoneAchievedEvent> milestoneKafkaListenerContainerFactory(
            ConsumerFactory<String, MilestoneAchievedEvent> milestoneConsumerFactory,
            DefaultErrorHandler milestoneErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, MilestoneAchievedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(milestoneConsumerFactory);
        factory.setCommonErrorHandler(milestoneErrorHandler);
        return factory;
    }
}

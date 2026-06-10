package com.gb.wallet.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gb.wallet.global.event.MilestoneAchievedEvent;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Kafka Producer 설정 (Phase 2 — 마일스톤 이벤트 발행, BE-3).
 *
 * <p>오토컨피그 KafkaTemplate에 기대지 않고 명시 구성한다 — 이유 2가지:
 * <ul>
 *   <li><b>snake_case 보장:</b> 오토컨피그가 yml의 value-serializer 클래스명으로 JsonSerializer를
 *       리플렉션 생성하면 <i>자체</i> ObjectMapper(camelCase)를 쓴다. 여기서는 Spring Boot 전역
 *       ObjectMapper({@code spring.jackson.property-naming-strategy: SNAKE_CASE} + JavaTimeModule)를
 *       생성자로 직접 주입해 이벤트 JSON이 명세 컨벤션(snake_case, ISO-8601 Z)을 따르게 한다.</li>
 *   <li><b>버전 독립:</b> 프로퍼티 맵을 직접 구성해 Boot 마이너 버전별 KafkaProperties API 변동과
 *       무관하게 동작을 고정한다.</li>
 * </ul>
 */
@Configuration
public class KafkaProducerConfig {

    /** 브로커 주소 — 환경변수 KAFKA_BOOTSTRAP_SERVERS 주입(스파이크 결정 6). application.yaml 참조. */
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * 브로커 미가용 시 send()가 메타데이터를 기다리며 블로킹되는 상한(ms). Kafka 기본 60s.
     * AFTER_COMMIT 리스너(MilestoneEventPublisher)는 API 호출 스레드에서 동기 실행되므로, 길게 두면
     * Kafka 장애가 충전/송금 응답 지연으로 번진다 — 짧게 캡해 빠르게 실패시키고 ERROR 로그로 넘긴다
     * (발행 실패 = 유실 허용 + 보정, 스파이크 결정 4). 테스트(application-test.yml)는 100ms로 더 줄인다.
     */
    @Value("${gb.kafka.producer.max-block-ms:3000}")
    private long maxBlockMs;

    @Bean
    public ProducerFactory<String, MilestoneAchievedEvent> milestoneProducerFactory(ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);

        JsonSerializer<MilestoneAchievedEvent> valueSerializer = new JsonSerializer<>(objectMapper);
        // __TypeId__ 헤더(발행측 FQCN)를 싣지 않는다 — MSA 경계라 컨슈머(member)에 우리 클래스명이
        // 무의미하고, 컨슈머는 어차피 대상 타입을 명시한 JsonDeserializer로 읽는다(헤더 미사용).
        valueSerializer.setAddTypeInfo(false);

        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, MilestoneAchievedEvent> milestoneKafkaTemplate(
            ProducerFactory<String, MilestoneAchievedEvent> milestoneProducerFactory) {
        return new KafkaTemplate<>(milestoneProducerFactory);
    }
}

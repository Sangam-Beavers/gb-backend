package com.gb.document.global.client.sqs;

import static org.assertj.core.api.Assertions.assertThat;

import io.awspring.cloud.sqs.support.converter.SqsHeaderMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageHeaders;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * spring-cloud-aws의 사용자 message attribute → 헤더 매핑 규칙 회귀 테스트.
 *
 * <p>스키마 §1·routing §3 약속 "회귀 테스트로 잠근다"의 이행. {@link AnalysisResultListener}는
 * {@code @Header("source")}/{@code @Header("document_public_id")}로 <b>접두사 없이</b> attribute를
 * 읽는다. 이 가정은 라이브러리 실동작에 의존하므로(틀리면 attribute가 null로 도착 → 전 메시지 DLQ)
 * {@link SqsHeaderMapper#toHeaders(Message)}를 직접 호출해 매핑 규칙을 못 박는다.
 *
 * <p>근거(3.4.2 소스): {@code SqsHeaderMapper.getMessageAttributesAsHeaders}는 사용자 attribute를
 * {@code Map.Entry::getKey} 그대로 헤더 키로 매핑하고, 접두사 {@code SQS_MSA_HEADER_PREFIX}(="Sqs_Msa_")는
 * {@code getMessageSystemAttributesAsHeaders}의 <b>시스템 attribute</b>에만 붙는다.
 *
 * <p>LocalStack E2E 대신 이 단위 테스트를 쓰는 이유: Docker 불필요·CI 상시 실행이면서 우리가 정작
 * 잠그려는 "사용자 attribute 무접두사" 계약을 정확히 검증한다.
 */
class SqsHeaderMappingTest {

    @Test
    @DisplayName("사용자 message attribute는 무접두사 헤더 키로 매핑된다 — @Header(\"source\") 가정 잠금")
    void 사용자_attribute는_무접두사로_헤더에_매핑된다() {
        SqsHeaderMapper mapper = new SqsHeaderMapper();
        String docId = "550e8400-e29b-41d4-a716-446655440000";

        Message message = Message.builder()
                .messageId(UUID.randomUUID().toString())   // toHeaders가 UUID.fromString으로 파싱
                .body("{}")
                .messageAttributes(Map.of(
                        AnalysisResultListener.ATTR_SOURCE, stringAttr("production"),
                        AnalysisResultListener.ATTR_DOCUMENT_PUBLIC_ID, stringAttr(docId)))
                .build();

        MessageHeaders headers = mapper.toHeaders(message);

        assertThat(headers.get(AnalysisResultListener.ATTR_SOURCE))
                .as("사용자 attribute는 키 그대로 헤더가 되어야 한다")
                .isEqualTo("production");
        assertThat(headers.get(AnalysisResultListener.ATTR_DOCUMENT_PUBLIC_ID)).isEqualTo(docId);
        // 시스템 attribute 접두사가 사용자 attribute에 붙으면 안 된다.
        assertThat(headers.get("Sqs_Msa_" + AnalysisResultListener.ATTR_SOURCE))
                .as("사용자 attribute에 시스템 접두사가 붙으면 @Header(\"source\")가 깨진다")
                .isNull();
    }

    private MessageAttributeValue stringAttr(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}

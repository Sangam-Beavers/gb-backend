package com.gb.document.global.client.sqs;

import com.gb.document.domain.document.service.AnalysisResultIngestService;
import com.gb.document.global.client.sqs.dto.AnalysisResultMessage;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Lambda B → SQS 결과 메시지 수신 리스너 (v1.1).
 *
 * <p>스키마/페이로드 위치 SSOT: {@code docs/document-analysis/result-json-schema-agreement.md} §1·§2.
 * 큐 라우팅/환경별 분리 SSOT: {@code docs/document-analysis/result-queue-routing.md}.
 *
 * <h3>활성화 조건</h3>
 * <ul>
 *   <li>{@code gb.analysis.consumer-enabled=true} — dev는 false(빈 등록 자체 생략), stage/prod는 true.</li>
 *   <li>{@code spring-cloud-aws-starter-sqs}가 컨테이너(폴링/visibility/ack)를 책임진다.</li>
 *   <li>리스너가 예외를 던지면 컨테이너가 {@code deleteMessage}를 호출하지 않으므로 visibility timeout
 *       이후 메시지가 재수신되고, 큐의 {@code maxReceiveCount} 초과 시 DLQ로 이동(ack-on-success).</li>
 * </ul>
 *
 * <h3>페이로드 분리</h3>
 * <ul>
 *   <li><b>body</b>: v1.1 결과 JSON 그대로 → {@link AnalysisResultMessage}로 역직렬화. 스키마 SSOT.</li>
 *   <li><b>attribute {@code source}</b>: {@code development}/{@code production} — 라우팅 메타.</li>
 *   <li><b>attribute {@code document_public_id}</b>: body 값과 동일 — 라우팅/메트릭 기준값. 본문 값과
 *       불일치 시 poison으로 간주 후 예외 → DLQ.</li>
 * </ul>
 *
 * <p>spring-cloud-aws 3.x의 {@code SqsHeaderMapper}는 사용자 message attribute를 헤더로 매핑할 때
 * <b>접두사를 붙이지 않고 attribute 키를 그대로</b> 헤더 키로 쓴다(시스템 attribute만 {@code Sqs_Msa_}
 * 접두사가 붙음). 따라서 {@code @Header("source")}, {@code @Header("document_public_id")}로 직접
 * 받는다. 어떤 attribute를 받을지는 {@code @SqsListener} 어노테이션이 아니라
 * {@code SqsContainerOptions.messageAttributeNames}에서 설정한다 — 설정은 {@code AwsSqsConsumerConfig}의
 * 컨테이너 팩토리 빈에서 한다(명시 없으면 attribute가 누락된 채 도착).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gb.analysis.consumer-enabled", havingValue = "true")
@RequiredArgsConstructor
public class AnalysisResultListener {

    /** SQS MessageAttribute key (Lambda B ↔ Consumer 합의 — 스키마 §1). */
    public static final String ATTR_SOURCE = "source";
    public static final String ATTR_DOCUMENT_PUBLIC_ID = "document_public_id";

    private final AnalysisResultIngestService ingestService;

    @SqsListener(value = "${gb.analysis.consumer-queue-name}")
    public void onAnalysisResult(
            AnalysisResultMessage message,
            @Header(ATTR_SOURCE) String source,
            @Header(ATTR_DOCUMENT_PUBLIC_ID) String routingDocumentPublicId
    ) {
        // 본문 ↔ attribute 일관성 검증. 불일치는 poison(라우팅 메타와 본문 SSOT 어긋남) — DLQ 유도.
        if (!routingDocumentPublicId.equals(message.documentPublicId())) {
            log.error("[sqs-consumer] document_public_id 불일치 attribute={} body={} source={}",
                    routingDocumentPublicId, message.documentPublicId(), source);
            throw new IllegalStateException(
                    "document_public_id 불일치: attribute=" + routingDocumentPublicId
                            + " body=" + message.documentPublicId());
        }

        log.info("[sqs-consumer] 분석 결과 수신 source={} documentPublicId={} schemaVersion={}",
                source, message.documentPublicId(), message.schemaVersion());

        ingestService.ingest(message);
    }
}

package com.gb.document.global.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * {@code document_results}의 JSON 컬럼({@code wage_summary}/{@code risk_items}) 안 금액 필드를
 * 관대하게 파싱하는 디시리얼라이저. <b>Hibernate JSON 포맷 매퍼 전용</b>이며({@link JpaConfig}에서 등록),
 * API 요청/응답용 Spring 전역 ObjectMapper에는 적용하지 않는다(그쪽은 엄격 유지).
 *
 * <p><b>왜 필요한가:</b> 결과 JSON은 계정 B Lambda B(개발 경로는 온프렘 MySQL 직접 INSERT)가
 * 비결정적 LLM 출력으로 쓴다. 스키마 합의(result-json-schema-agreement.md §3-1)는 금액을
 * string(decimal)로 규정하지만, 모델이 {@code "약 103,500원"}처럼 통화기호·콤마·한글이 섞인
 * 표시용 문자열을 넣는 경우가 실측됐다(2026-06-08). 이때 {@code BigDecimal} 역직렬화가 깨지면
 * 엔티티 hydration이 통째로 실패해 <b>해당 행이 섞인 목록 조회 전체가 500</b>으로 떨어진다.
 * 한 행의 오염이 목록 전부를 막지 않도록, 숫자 외 문자를 제거해 파싱하고 그래도 안 되면 null로 둔다.
 *
 * <p>표시용 수치라 손실 허용 — 파싱 실패 시 예외 대신 {@code null}("정보 없음")로 강등한다.
 * 근본 해결(깨끗한 decimal 문자열 적재)은 Lambda B 측 책임이며, 이건 읽기 측 안전망이다.
 */
public class LenientBigDecimalDeserializer extends JsonDeserializer<BigDecimal> {

    /** 숫자(부호·소수점 포함) 외 문자를 모두 제거하기 위한 패턴. */
    private static final java.util.regex.Pattern NON_NUMERIC =
            java.util.regex.Pattern.compile("[^0-9.\\-]");

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        // 숫자 토큰(따옴표 없는 값)은 그대로 신뢰한다.
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return p.getDecimalValue();
        }
        String raw = p.getValueAsString();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = NON_NUMERIC.matcher(raw).replaceAll("");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            // 제거 후에도 소수점·부호가 꼬여 파싱 불가(예: "1.2.3") — 표시용이라 null로 강등.
            return null;
        }
    }
}

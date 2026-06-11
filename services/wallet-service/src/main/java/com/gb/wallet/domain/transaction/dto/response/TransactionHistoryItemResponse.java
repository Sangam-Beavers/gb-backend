package com.gb.wallet.domain.transaction.dto.response;

import com.gb.wallet.domain.transaction.entity.Transaction;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.common.enums.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * 전자지갑 거래내역 단건 항목({@code GET /api/v1/wallets/me/transactions}). 모든 유형(CHARGE/
 * INTERNAL_TRANSFER/REMITTANCE/EXCHANGE)의 거래를 하나의 항목 형태로 표현한다. 금액은 string(소수 4자리),
 * 식별자는 public_id(UUID), 시각은 ISO 8601 UTC Z로 직렬화한다({@code ExchangeResponse}와 동일 변환 규칙).
 *
 * <p>환전 멱등성 캐시처럼 응답을 JSON으로 저장했다가 복원하는 round-trip이 없으므로(거래내역은 캐시하지
 * 않는다 — CLAUDE.md §8) {@code @JsonCreator}/{@code @JsonProperty} 없이 일반 {@code @Getter} +
 * 정적 {@code from}만 둔다. 직렬화는 전역 SNAKE_CASE 전략에 위임한다.
 *
 * <p>TODO: 응답 필드는 {@link Transaction} 엔티티 기반 잠정안이다. 팀 API 명세서(거래내역 화면 와이어프레임)
 *       확정 시 1:1 정합 검토 — 유형별 노출 필드/마스킹/추가 메타가 바뀔 수 있다.
 */
@Getter
public class TransactionHistoryItemResponse {

    @Schema(description = "거래 식별자(UUID)", example = "1a2b3c4d-5678-90ab-cdef-012345678901")
    private final String publicId;

    @Schema(description = "거래 유형", example = "CHARGE",
            allowableValues = {"CHARGE", "INTERNAL_TRANSFER", "REMITTANCE", "EXCHANGE"})
    private final String type;

    @Schema(description = "본인 기준 거래 방향. OUT = 본인이 송신자(출금), IN = 본인이 수신자(입금). "
            + "CHARGE/REMITTANCE/EXCHANGE는 본인 출금이라 항상 OUT, INTERNAL_TRANSFER만 OUT/IN 분기.",
            example = "OUT", allowableValues = {"OUT", "IN"})
    private final String direction;

    @Schema(description = "거래 상태", example = "COMPLETED",
            allowableValues = {"PENDING", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"})
    private final String status;

    @Schema(description = "거래(출금) 금액 (string, 소수 4자리)", example = "500000.0000")
    private final String amount;

    @Schema(description = "출금 통화 코드", example = "KRW")
    private final String currencyCode;

    @Schema(description = "수수료 (string, 소수 4자리)", example = "0.0000")
    private final String fee;

    @Schema(description = "수령액 (string, 환전·송금만, nullable)", example = "72.4500", nullable = true)
    private final String receiveAmount;

    @Schema(description = "수령 통화 코드 (환전·송금만, nullable)", example = "USD", nullable = true)
    private final String receiveCurrencyCode;

    @Schema(description = "수취인 이름 (REMITTANCE 해외송금 외부 수취인, nullable)", example = "홍길동", nullable = true)
    private final String receiverName;

    @Schema(description = "거래 상대 닉네임 (앱 사용자 간 송금 INTERNAL_TRANSFER만 — OUT이면 받는 사람, "
            + "IN이면 보낸 사람의 닉네임. 그 외 유형/조회불가는 null). 이메일은 PII 정책상 미노출(conventions §13).",
            example = "하노이댁", nullable = true)
    private final String counterpartyNickname;

    @Schema(description = "거래 시각 (ISO 8601 UTC Z)", example = "2026-05-26T04:15:30Z")
    private final String createdAt;

    @Builder
    private TransactionHistoryItemResponse(String publicId, String type, String direction, String status,
                                           String amount, String currencyCode, String fee, String receiveAmount,
                                           String receiveCurrencyCode, String receiverName,
                                           String counterpartyNickname, String createdAt) {
        this.publicId = publicId;
        this.type = type;
        this.direction = direction;
        this.status = status;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.fee = fee;
        this.receiveAmount = receiveAmount;
        this.receiveCurrencyCode = receiveCurrencyCode;
        this.receiverName = receiverName;
        this.counterpartyNickname = counterpartyNickname;
        this.createdAt = createdAt;
    }

    /**
     * 본인 시점({@code currentUserPublicId}) 기준으로 응답 항목을 만든다. INTERNAL_TRANSFER는 본인이
     * 수신자({@code receiverWallet})인 경우 {@code direction=IN}, 그 외(CHARGE/REMITTANCE/EXCHANGE 또는
     * INTERNAL_TRANSFER 송신자)는 {@code direction=OUT}이다.
     */
    public static TransactionHistoryItemResponse from(Transaction tx, String currentUserPublicId,
                                                      Map<String, MemberInfo> membersByPublicId) {
        return TransactionHistoryItemResponse.builder()
                .publicId(tx.getPublicId())
                .type(tx.getType().name())
                .direction(resolveDirection(tx, currentUserPublicId))
                .status(tx.getStatus().name())
                .amount(toPlainString(tx.getAmount()))
                .currencyCode(tx.getCurrencyCode().name())
                .fee(toPlainString(tx.getFee()))
                .receiveAmount(toPlainString(tx.getReceiveAmount()))
                .receiveCurrencyCode(tx.getReceiveCurrencyCode() != null ? tx.getReceiveCurrencyCode().name() : null)
                .receiverName(tx.getReceiverName())
                .counterpartyNickname(resolveCounterpartyNickname(tx, currentUserPublicId, membersByPublicId))
                .createdAt(toUtcZ(tx.getCreatedAt()))
                .build();
    }

    /**
     * 거래 상대(앱 사용자)의 user_public_id. INTERNAL_TRANSFER만 대상이며, 본인이 송신자면 수취 지갑 주인,
     * 본인이 수신자면 출금 지갑 주인을 돌려준다. 그 외 유형(CHARGE/REMITTANCE/EXCHANGE)이나 한쪽 지갑이
     * 없으면 null(상대 없음). Service가 이 값으로 표시정보를 배치 조회(getMembers)한다.
     */
    public static String counterpartyUserPublicId(Transaction tx, String currentUserPublicId) {
        if (tx.getType() != TransactionType.INTERNAL_TRANSFER) {
            return null;
        }
        Wallet sender = tx.getWallet();
        Wallet receiver = tx.getReceiverWallet();
        if (sender == null || receiver == null) {
            return null;
        }
        String senderId = sender.getUserPublicId();
        String receiverId = receiver.getUserPublicId();
        if (currentUserPublicId.equals(senderId)) {
            return receiverId;   // 본인이 보냄(OUT) → 상대 = 받는 사람
        }
        if (currentUserPublicId.equals(receiverId)) {
            return senderId;     // 본인이 받음(IN) → 상대 = 보낸 사람
        }
        return null;
    }

    /** 거래 상대 닉네임 조회. 상대 없음/맵 미제공/조회 누락이면 null(표시용 — 호출 측이 생략). */
    private static String resolveCounterpartyNickname(Transaction tx, String currentUserPublicId,
                                                      Map<String, MemberInfo> membersByPublicId) {
        String counterpartyId = counterpartyUserPublicId(tx, currentUserPublicId);
        if (counterpartyId == null || membersByPublicId == null) {
            return null;
        }
        MemberInfo member = membersByPublicId.get(counterpartyId);
        return member != null ? member.nickname() : null;
    }

    /**
     * 송수신 방향 결정. wallet(송신자)의 user_public_id와 일치하면 OUT, receiverWallet의 user_public_id와
     * 일치하면 IN. 송수신 양쪽 다 본인인 경우(자기↔자기 송금)는 OUT으로 본다(현재 자기송금은 서비스에서 차단).
     */
    private static String resolveDirection(Transaction tx, String currentUserPublicId) {
        if (tx.getWallet() != null
                && currentUserPublicId.equals(tx.getWallet().getUserPublicId())) {
            return "OUT";
        }
        if (tx.getReceiverWallet() != null
                && currentUserPublicId.equals(tx.getReceiverWallet().getUserPublicId())) {
            return "IN";
        }
        // OR 조회 결과라 양쪽 어디에도 본인이 아닐 수는 없지만, 방어적으로 OUT 처리(현 응답 의미와 동일).
        return "OUT";
    }

    /** 금액을 소수 4자리 string으로 변환한다(지수 표기 회피). null이면 그대로 null. */
    private static String toPlainString(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /** LocalDateTime을 UTC로 간주해 ISO 8601 'Z' 문자열로 변환한다(초 단위 절삭). */
    private static String toUtcZ(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(
                dateTime.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));
    }
}

package com.gb.admin.global.client;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminChargeAttempt(
        String chargeAttemptPublicId,
        String userPublicId,
        BigDecimal amount,
        String currencyCode,
        String status,
        String reason,
        Long bankAccountId,
        LocalDateTime createdAt
) {
}

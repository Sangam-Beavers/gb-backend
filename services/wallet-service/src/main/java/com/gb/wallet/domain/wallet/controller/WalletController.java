package com.gb.wallet.domain.wallet.controller;

import com.gb.common.response.ApiResponse;
import com.gb.wallet.domain.wallet.dto.response.WalletBalanceResponse;
import com.gb.wallet.domain.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    /** 전자지갑 잔액 조회. 🔒 JWT 필요. */
    @GetMapping("/me/balances")
    public ApiResponse<WalletBalanceResponse> getMyBalances(
            // TODO: 인증 구현 후 JWT 토큰(sub/claim)에서 userPublicId를 추출하도록 교체.
            //       현재는 인증 미구현으로 헤더(X-User-Public-Id)로 임시 수신.
            @RequestHeader("X-User-Public-Id") String userPublicId) {
        return ApiResponse.success(walletService.getMyBalances(userPublicId));
    }
}

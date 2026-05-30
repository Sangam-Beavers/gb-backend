package com.gb.wallet.domain.account.service.impl;

import com.gb.common.exception.BusinessException;
import com.gb.common.exception.CommonErrorCode;
import com.gb.wallet.domain.account.dto.request.RegisterAccountRequest;
import com.gb.wallet.domain.account.dto.request.VerifyAccountRequest;
import com.gb.wallet.domain.account.dto.response.AccountListResponse;
import com.gb.wallet.domain.account.dto.response.AccountResponse;
import com.gb.wallet.domain.account.dto.response.VerifyAccountResponse;
import com.gb.wallet.domain.account.entity.Bank;
import com.gb.wallet.domain.account.entity.BankAccount;
import com.gb.wallet.domain.account.repository.BankAccountRepository;
import com.gb.wallet.domain.account.repository.BankRepository;
import com.gb.wallet.domain.account.service.BankAccountService;
import com.gb.wallet.global.client.BankClient;
import com.gb.wallet.global.client.dto.AccountToken;
import com.gb.wallet.global.exception.code.AccountErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankAccountServiceImpl implements BankAccountService {

    private final BankAccountRepository bankAccountRepository;
    private final BankRepository bankRepository;
    private final BankClient bankClient;

    @Override
    public AccountListResponse getMyAccounts(String userPublicId) {
        List<BankAccount> accounts = bankAccountRepository
                .findAllByUserPublicIdAndIsActiveTrueOrderByIsPrimaryDescCreatedAtDesc(userPublicId);
        return AccountListResponse.from(accounts);
    }

    @Override
    public VerifyAccountResponse verifyAccount(VerifyAccountRequest request) {
        // Mock 은행 실패는 BankErrorMapper가 BusinessException으로 변환해 던지므로 그대로 전파한다.
        AccountToken token = bankClient.verify(
                request.getBankCode(), request.getAccountNumber(), request.getHolderName());
        return VerifyAccountResponse.from(token);
    }

    @Override
    @Transactional
    public AccountResponse registerAccount(String userPublicId, RegisterAccountRequest request) {
        // TODO: race-safety — 두 등록 요청이 동시에 existsBy → false → 둘 다 INSERT 가능. 또한 같은 race에서
        //       아래 countByUserPublicIdAndIsActiveTrue == 0 도 둘 다 참이 돼 주 계좌(is_primary=true)가 둘 생길 수 있다.
        //       해결책: bank_accounts에 (user_public_id, bank_id, account_number) UNIQUE 제약(중복 등록 차단) +
        //       등록을 user 단위로 직렬화(Redis 락 SET lock:account-register:{user} NX EX 5)해 주 계좌 단일성도 보장.
        //       (MySQL은 부분 유니크 인덱스 미지원이라 is_primary 단일성은 제약만으로 못 막는다.)
        //       명세에 UNIQUE 정의가 없으므로 별도 마이그레이션 이슈에서 함께 도입한다.
        if (bankAccountRepository.existsByUserPublicIdAndBank_CodeAndAccountNumberAndIsActiveTrue(
                userPublicId, request.getBankCode(), request.getAccountNumber())) {
            throw new BusinessException(AccountErrorCode.ACCOUNT_ALREADY_REGISTERED);
        }

        Bank bank = bankRepository.findByCode(request.getBankCode())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_REQUEST));

        // 사용자의 첫 활성 계좌면 자동으로 주 계좌. 그 외에는 항상 false로 둔다 — 등록 흐름에서 다중
        // 주 계좌(같은 사용자에 활성 is_primary=true 둘 이상)가 발생하면 충전 시 어느 계좌가 기본인지
        // 모호해진다. 명세 §11은 주 계좌 변경을 PATCH /api/v1/accounts/{id}/primary로 분리해두었으므로
        // register는 변경 책임을 갖지 않는다.
        boolean isPrimary = bankAccountRepository.countByUserPublicIdAndIsActiveTrue(userPublicId) == 0;

        BankAccount account = BankAccount.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .bank(bank)
                .accountNumber(request.getAccountNumber())
                .mockAccountToken(request.getAccountToken())
                .isVirtual(false)
                .isPrimary(isPrimary)
                .isActive(true)
                .build();

        BankAccount saved = bankAccountRepository.save(account);
        return AccountResponse.from(saved);
    }
}

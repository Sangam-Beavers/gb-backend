package com.gb.wallet.domain.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gb.common.exception.BusinessException;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse;
import com.gb.wallet.domain.transaction.dto.response.RecentRecipientsResponse.RecipientItem;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse;
import com.gb.wallet.domain.transaction.dto.response.SupportedCurrenciesResponse.CurrencyItem;
import com.gb.wallet.domain.transaction.dto.response.ValidateMemberResponse;
import com.gb.wallet.domain.transaction.repository.ReceiverCurrencyProjection;
import com.gb.wallet.domain.transaction.repository.RecentRecipientProjection;
import com.gb.wallet.domain.transaction.repository.TransactionRepository;
import com.gb.wallet.domain.transaction.service.impl.TransferServiceImpl;
import com.gb.wallet.domain.wallet.entity.Wallet;
import com.gb.wallet.domain.wallet.repository.WalletRepository;
import com.gb.wallet.global.client.MemberClient;
import com.gb.wallet.global.client.MemberInfo;
import com.gb.wallet.global.common.enums.CurrencyType;
import com.gb.wallet.global.common.enums.WalletStatus;
import com.gb.wallet.global.exception.code.MemberErrorCode;
import com.gb.wallet.global.exception.code.WalletErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link TransferServiceImpl}의 순수 단위 테스트.
 * Spring 컨텍스트/DB 없이 Mockito로만. Repository/MemberClient 응답을 stub해 매핑 로직만 검증한다.
 *
 * <p>직렬화(snake_case, JSON)는 단위 테스트 범위 밖이라 응답 객체의 필드값까지만 본다.
 */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private MemberClient memberClient;
    @InjectMocks private TransferServiceImpl transferService;

    private static final String SENDER_PUBLIC_ID = "sender-uuid";
    private static final LocalDateTime T_LINH  = LocalDateTime.of(2026, 5, 25, 10, 0);
    private static final LocalDateTime T_MARIA = LocalDateTime.of(2026, 5, 22, 10, 0);

    @Test
    @DisplayName("정상: 수신자별 최신 송금이 최근순으로 매핑되고 통화·회원 정보가 결합된다")
    void getRecentInternalRecipients_정상_조합() {
        Wallet sender      = wallet(1L,  SENDER_PUBLIC_ID);
        Wallet linhWallet  = wallet(10L, "linh-uuid");
        Wallet mariaWallet = wallet(20L, "maria-uuid");

        given(walletRepository.findByUserPublicId(SENDER_PUBLIC_ID))
                .willReturn(Optional.of(sender));

        // Repository는 이미 lastTransferredAt DESC 정렬된 결과를 돌려준다고 가정 (Repository 테스트에서 검증).
        given(transactionRepository.findRecentInternalTransferRecipients(eq(1L), any(Pageable.class)))
                .willReturn(List.of(
                        recentRecipient(linhWallet.getId(),  T_LINH),
                        recentRecipient(mariaWallet.getId(), T_MARIA)));

        given(transactionRepository.findCurrencyCodesForLatestTransfers(
                eq(1L),
                eq(List.of(linhWallet.getId(), mariaWallet.getId())),
                eq(List.of(T_LINH, T_MARIA))))
                .willReturn(List.of(
                        receiverCurrency(linhWallet.getId(),  CurrencyType.KRW, T_LINH),
                        receiverCurrency(mariaWallet.getId(), CurrencyType.VND, T_MARIA)));

        given(walletRepository.findAllById(List.of(linhWallet.getId(), mariaWallet.getId())))
                .willReturn(List.of(linhWallet, mariaWallet));

        given(memberClient.getMember("linh-uuid"))
                .willReturn(new MemberInfo("linh-uuid",  "linh-test@example.com",  "Linh",  "VN", true, "GREEN"));
        given(memberClient.getMember("maria-uuid"))
                .willReturn(new MemberInfo("maria-uuid", "maria-test@example.com", "Maria", "PH", true, "BLUE"));

        RecentRecipientsResponse response =
                transferService.getRecentInternalRecipients(SENDER_PUBLIC_ID);

        assertThat(response.getReceivers())
                .as("순서(Linh→Maria) + 모든 필드 매핑 검증. lastTransferredAt은 ISO 8601 UTC Z 문자열")
                .extracting(RecipientItem::getMemberPublicId,
                            RecipientItem::getNickname,
                            RecipientItem::getNationality,
                            RecipientItem::isVerified,
                            RecipientItem::getTemperatureGrade,
                            RecipientItem::getLastCurrencyCode,
                            RecipientItem::getLastTransferredAt)
                .containsExactly(
                        tuple("linh-uuid",  "Linh",  "VN", true, "GREEN", "KRW", "2026-05-25T10:00:00Z"),
                        tuple("maria-uuid", "Maria", "PH", true, "BLUE",  "VND", "2026-05-22T10:00:00Z"));
    }

    @Test
    @DisplayName("지갑 없음: WALLET_NOT_FOUND BusinessException, 이후 TransactionRepository·MemberClient 호출 없음")
    void getRecentInternalRecipients_지갑_없음() {
        given(walletRepository.findByUserPublicId("unknown")).willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getRecentInternalRecipients("unknown"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(WalletErrorCode.WALLET_NOT_FOUND);

        verifyNoInteractions(transactionRepository, memberClient);
    }

    @Test
    @DisplayName("송금 이력 없음: receivers 빈 배열 반환, MemberClient 호출 없음")
    void getRecentInternalRecipients_빈_결과() {
        Wallet sender = wallet(1L, SENDER_PUBLIC_ID);

        given(walletRepository.findByUserPublicId(SENDER_PUBLIC_ID))
                .willReturn(Optional.of(sender));
        given(transactionRepository.findRecentInternalTransferRecipients(eq(1L), any(Pageable.class)))
                .willReturn(List.of());

        RecentRecipientsResponse response =
                transferService.getRecentInternalRecipients(SENDER_PUBLIC_ID);

        assertThat(response.getReceivers()).isEmpty();
        verifyNoInteractions(memberClient);
    }

    @Test
    @DisplayName("validateMember 정상: 이메일로 찾은 회원의 publicId/nickname/isVerified가 응답에 매핑된다")
    void validateMember_정상() {
        String email = "linh@example.com";
        MemberInfo linh = new MemberInfo(
                "11111111-1111-1111-1111-111111111111",
                email,
                "Linh",
                "VN",
                true,
                "GREEN");
        given(memberClient.findByEmail(email)).willReturn(Optional.of(linh));

        ValidateMemberResponse response = transferService.validateMember(email);

        assertThat(response.getReceiverPublicId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(response.getNickname()).isEqualTo("Linh");
        assertThat(response.isVerified()).isTrue();

        // wallet DB 미접근 검증: wallet/transaction Repository는 호출되면 안 된다.
        verifyNoInteractions(walletRepository, transactionRepository);
    }

    @Test
    @DisplayName("validateMember 없는 회원: MEMBER_NOT_FOUND BusinessException, wallet DB 미접근")
    void validateMember_없는_회원() {
        String email = "nobody@example.com";
        given(memberClient.findByEmail(email)).willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.validateMember(email))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);

        verifyNoInteractions(walletRepository, transactionRepository);
    }

    @Test
    @DisplayName("getSupportedCurrencies: 4종 통화 모두 enum 정의대로 반환, DB/외부 호출 없음")
    void getSupportedCurrencies_정상() {
        SupportedCurrenciesResponse response = transferService.getSupportedCurrencies();

        // 1) 4개, 코드 집합이 정확히 KRW/USD/PHP/VND
        assertThat(response.getCurrencies())
                .as("지원 통화 4종이 모두 포함")
                .extracting(CurrencyItem::getCode)
                .containsExactlyInAnyOrder("KRW", "USD", "PHP", "VND");

        // 2) 각 항목의 code/name/symbol이 enum 정의와 1:1 일치
        assertThat(response.getCurrencies())
                .extracting(CurrencyItem::getCode, CurrencyItem::getName, CurrencyItem::getSymbol)
                .containsExactlyInAnyOrder(
                        tuple("KRW", "Korean Won",       "₩"),
                        tuple("USD", "US Dollar",        "$"),
                        tuple("PHP", "Philippine Peso",  "₱"),
                        tuple("VND", "Vietnamese Dong",  "₫"));

        // 3) 이 API는 DB/외부 안 본다는 설계를 코드로 못 박는다.
        verifyNoInteractions(walletRepository, transactionRepository, memberClient);
    }

    // ----- helpers -----

    /** Wallet은 GenerationType.IDENTITY라 단위 테스트에선 id를 reflection으로 직접 박는다. */
    private Wallet wallet(Long id, String userPublicId) {
        Wallet w = Wallet.builder()
                .publicId(UUID.randomUUID().toString())
                .userPublicId(userPublicId)
                .status(WalletStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(w, "id", id);
        return w;
    }

    private RecentRecipientProjection recentRecipient(Long receiverId, LocalDateTime time) {
        return new RecentRecipientProjection() {
            @Override public Long getReceiverWalletId() { return receiverId; }
            @Override public LocalDateTime getLastTransferredAt() { return time; }
        };
    }

    private ReceiverCurrencyProjection receiverCurrency(Long receiverId, CurrencyType currency,
                                                        LocalDateTime createdAt) {
        return new ReceiverCurrencyProjection() {
            @Override public Long getReceiverWalletId() { return receiverId; }
            @Override public CurrencyType getCurrencyCode() { return currency; }
            @Override public LocalDateTime getCreatedAt() { return createdAt; }
        };
    }
}

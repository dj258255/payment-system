package com.beomsu.pay.wallet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WalletServiceTest {

    private WalletAccountRepository accountRepository;
    private WalletTransactionRepository transactionRepository;
    private WalletService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(WalletAccountRepository.class);
        transactionRepository = mock(WalletTransactionRepository.class);
        service = new WalletService(accountRepository, transactionRepository);
    }

    @Test
    @DisplayName("charge: 잔액을 늘리고 CHARGE 이력(balanceAfter 포함)을 남긴다")
    void chargeAddsAndRecords() {
        WalletAccount account = WalletAccount.of(1L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.charge(1L, 500_000);

        assertThat(balance).isEqualTo(500_000);
        assertThat(account.getBalance()).isEqualTo(500_000);
        verify(accountRepository).saveAndFlush(account);
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(WalletTransactionType.CHARGE);
        assertThat(captor.getValue().getAmount()).isEqualTo(500_000);
        assertThat(captor.getValue().getBalanceAfter()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("charge: 계정이 없으면 잔액 0에서 새로 만들어 충전한다")
    void chargeCreatesAccountWhenAbsent() {
        when(accountRepository.findById(9L)).thenReturn(Optional.empty());

        long balance = service.charge(9L, 100_000);

        assertThat(balance).isEqualTo(100_000);
    }

    @Test
    @DisplayName("use: 잔액을 차감하고 USE 이력을 남긴다")
    void useDeductsAndRecords() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(10_000);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.use(1L, 6_000);

        assertThat(balance).isEqualTo(4_000);
        verify(accountRepository).saveAndFlush(account);
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(WalletTransactionType.USE);
        assertThat(captor.getValue().getBalanceAfter()).isEqualTo(4_000);
    }

    @Test
    @DisplayName("charge: 한도 초과면 LIMIT_EXCEEDED 전파, 이력 저장 안 함")
    void chargeOverLimitPropagates() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(1_800_000);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.charge(1L, 300_000))
                .isInstanceOf(WalletException.class)
                .satisfies(e -> assertThat(((WalletException) e).code()).isEqualTo("LIMIT_EXCEEDED"));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("use: 잔액 부족이면 INSUFFICIENT_BALANCE 전파, 이력 저장 안 함")
    void useInsufficientPropagates() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(2_000);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.use(1L, 6_000))
                .isInstanceOf(WalletException.class)
                .satisfies(e -> assertThat(((WalletException) e).code()).isEqualTo("INSUFFICIENT_BALANCE"));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("refund: 잔액을 되돌리고 REFUND 이력을 남긴다")
    void refundAddsAndRecords() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(10_000);
        account.use(4_000);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        long balance = service.refund(1L, 4_000);

        assertThat(balance).isEqualTo(10_000);
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(WalletTransactionType.REFUND);
    }

    @Test
    @DisplayName("balance: 계정이 없으면 0")
    void balanceZeroWhenNoAccount() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.balance(99L)).isZero();
    }
}

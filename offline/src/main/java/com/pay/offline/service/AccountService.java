package com.pay.offline.service;

import com.pay.offline.dto.AccountResponse;
import com.pay.offline.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;

    public Optional<AccountResponse> getAccountByUserId(Long userId) {
        return accountRepository.findByUserId(userId)
                .map(AccountResponse::from);
    }

}
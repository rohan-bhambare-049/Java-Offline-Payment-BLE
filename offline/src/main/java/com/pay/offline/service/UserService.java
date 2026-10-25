package com.pay.offline.service;

import com.pay.offline.dto.UserRegistrationRequest;
import com.pay.offline.dto.UserResponse;
import com.pay.offline.entity.Account;
import com.pay.offline.entity.User;
import com.pay.offline.exception.EmailAlreadyExistsException;
import com.pay.offline.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;

import com.pay.offline.repository.AccountRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    private final SecureRandom secureRandom = new SecureRandom();

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    public Optional<UserResponse> getUserById(Long id) {
        return userRepository.findById(id)
                .map(UserResponse::from);
    }

    private String generateAccountNumber() {

        String accountNumber;

        do {
            StringBuilder number = new StringBuilder(16);

            for (int i = 0; i < 16; i++) {
                number.append(secureRandom.nextInt(10));
            }

            accountNumber = number.toString();

        } while (accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }

    public boolean emailExists(String email) {
        return userRepository.existsByEmail(normalizeEmail(email));
    }

    @Transactional
    public UserResponse registerUser(UserRegistrationRequest request) {

        String normalizedEmail = normalizeEmail(request.email());

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException(
                    "An account with this email already exists"
            );
        }

        validatePasswordBytes(request.password());

        User user = new User(
                request.fullName().trim(),
                normalizedEmail,
                passwordEncoder.encode(request.password())
        );

        User savedUser = userRepository.saveAndFlush(user);

        String accountNumber = generateAccountNumber();

        Account account = new Account(savedUser, accountNumber);

        accountRepository.saveAndFlush(account);

        return UserResponse.from(savedUser);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void validatePasswordBytes(String password) {

        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;

        if (passwordBytes > 72) {
            throw new IllegalArgumentException(
                    "Password must not exceed 72 UTF-8 bytes"
            );
        }
    }
}
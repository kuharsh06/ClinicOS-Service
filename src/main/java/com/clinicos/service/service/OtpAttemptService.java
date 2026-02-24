package com.clinicos.service.service;

import com.clinicos.service.repository.OtpRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate service for OTP attempt tracking.
 * Uses REQUIRES_NEW propagation to ensure attempt increments persist
 * independently of the calling transaction's outcome.
 */
@Service
@RequiredArgsConstructor
public class OtpAttemptService {

    private final OtpRequestRepository otpRequestRepository;

    /**
     * Increment OTP verify attempts in a new transaction.
     * This ensures the counter persists even when the parent transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementVerifyAttempts(Integer otpRequestId) {
        otpRequestRepository.incrementVerifyAttempts(otpRequestId);
    }
}

package com.clinicos.service.repository;

import com.clinicos.service.entity.OtpRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OtpRequestRepository extends JpaRepository<OtpRequest, Integer> {

    Optional<OtpRequest> findByUuid(String uuid);

    List<OtpRequest> findByCountryCodeAndPhoneAndCreatedAtAfterOrderByCreatedAtDesc(
            String countryCode, String phone, Instant after);

    Optional<OtpRequest> findTopByCountryCodeAndPhoneAndIsVerifiedFalseOrderByCreatedAtDesc(
            String countryCode, String phone);

    /**
     * Increment verify attempts counter.
     * Uses direct UPDATE query to persist immediately, independent of transaction rollback.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OtpRequest o SET o.verifyAttempts = o.verifyAttempts + 1 WHERE o.id = :id")
    void incrementVerifyAttempts(@Param("id") Integer id);
}

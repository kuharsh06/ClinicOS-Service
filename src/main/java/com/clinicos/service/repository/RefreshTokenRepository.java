package com.clinicos.service.repository;

import com.clinicos.service.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Integer> {

    Optional<RefreshToken> findByUuid(String uuid);

    Optional<RefreshToken> findByTokenHashAndIsRevokedFalse(String tokenHash);

    List<RefreshToken> findByUserIdAndIsRevokedFalse(Integer userId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.isRevoked = true WHERE r.user.id = :userId")
    void revokeAllByUserId(@Param("userId") Integer userId);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.isRevoked = true WHERE r.deviceId = :deviceId")
    void revokeAllByDeviceId(@Param("deviceId") String deviceId);
}

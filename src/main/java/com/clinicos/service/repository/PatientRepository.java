package com.clinicos.service.repository;

import com.clinicos.service.entity.Patient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PatientRepository extends JpaRepository<Patient, Integer> {

    Optional<Patient> findByUuid(String uuid);

    Optional<Patient> findByOrganizationIdAndCountryCodeAndPhone(Integer orgId, String countryCode, String phone);

    List<Patient> findByOrganizationId(Integer orgId);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId AND LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<Patient> searchByName(@Param("orgId") Integer orgId, @Param("name") String name);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId " +
            "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) " +
            "OR p.phone LIKE CONCAT('%', :query, '%'))")
    List<Patient> searchByNameOrPhone(@Param("orgId") Integer orgId, @Param("query") String query, Pageable pageable);

    // --- First page queries (no cursor) ---

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId ORDER BY p.lastVisitDate DESC NULLS LAST, p.id DESC")
    List<Patient> findByOrgIdOrderByLastVisitDesc(@Param("orgId") Integer orgId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId ORDER BY p.name ASC, p.id ASC")
    List<Patient> findByOrgIdOrderByNameAsc(@Param("orgId") Integer orgId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId ORDER BY p.createdAt DESC, p.id DESC")
    List<Patient> findByOrgIdOrderByCreatedDesc(@Param("orgId") Integer orgId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId ORDER BY p.totalVisits DESC, p.id DESC")
    List<Patient> findByOrgIdOrderByVisitsDesc(@Param("orgId") Integer orgId, Pageable pageable);

    // --- Cursor queries (keyset pagination — "give me everything after this cursor") ---

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId " +
            "AND (p.lastVisitDate < :cursorDate OR (p.lastVisitDate = :cursorDate AND p.id < :cursorId) OR p.lastVisitDate IS NULL) " +
            "ORDER BY p.lastVisitDate DESC NULLS LAST, p.id DESC")
    List<Patient> findByOrgIdOrderByLastVisitDescAfterCursor(
            @Param("orgId") Integer orgId, @Param("cursorDate") LocalDate cursorDate,
            @Param("cursorId") Integer cursorId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId " +
            "AND (p.lastVisitDate IS NULL AND p.id < :cursorId) " +
            "ORDER BY p.lastVisitDate DESC NULLS LAST, p.id DESC")
    List<Patient> findByOrgIdOrderByLastVisitDescAfterCursorNull(
            @Param("orgId") Integer orgId, @Param("cursorId") Integer cursorId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId " +
            "AND (LOWER(p.name) > LOWER(:cursorName) OR (LOWER(p.name) = LOWER(:cursorName) AND p.id > :cursorId)) " +
            "ORDER BY p.name ASC, p.id ASC")
    List<Patient> findByOrgIdOrderByNameAscAfterCursor(
            @Param("orgId") Integer orgId, @Param("cursorName") String cursorName,
            @Param("cursorId") Integer cursorId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId " +
            "AND (p.createdAt < :cursorCreatedAt OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId)) " +
            "ORDER BY p.createdAt DESC, p.id DESC")
    List<Patient> findByOrgIdOrderByCreatedDescAfterCursor(
            @Param("orgId") Integer orgId, @Param("cursorCreatedAt") java.time.Instant cursorCreatedAt,
            @Param("cursorId") Integer cursorId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId " +
            "AND (p.totalVisits < :cursorVisits OR (p.totalVisits = :cursorVisits AND p.id < :cursorId)) " +
            "ORDER BY p.totalVisits DESC, p.id DESC")
    List<Patient> findByOrgIdOrderByVisitsDescAfterCursor(
            @Param("orgId") Integer orgId, @Param("cursorVisits") Integer cursorVisits,
            @Param("cursorId") Integer cursorId, Pageable pageable);

    List<Patient> findByOrganizationIdAndIsRegularTrue(Integer orgId);

    boolean existsByOrganizationIdAndCountryCodeAndPhone(Integer orgId, String countryCode, String phone);
}

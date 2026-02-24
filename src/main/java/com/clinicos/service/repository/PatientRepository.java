package com.clinicos.service.repository;

import com.clinicos.service.entity.Patient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId ORDER BY p.lastVisitDate DESC NULLS LAST")
    List<Patient> findByOrgIdOrderByLastVisitDesc(@Param("orgId") Integer orgId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId ORDER BY p.name ASC")
    List<Patient> findByOrgIdOrderByNameAsc(@Param("orgId") Integer orgId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId ORDER BY p.createdAt DESC")
    List<Patient> findByOrgIdOrderByCreatedDesc(@Param("orgId") Integer orgId, Pageable pageable);

    @Query("SELECT p FROM Patient p WHERE p.organization.id = :orgId ORDER BY p.totalVisits DESC")
    List<Patient> findByOrgIdOrderByVisitsDesc(@Param("orgId") Integer orgId, Pageable pageable);

    List<Patient> findByOrganizationIdAndIsRegularTrue(Integer orgId);

    boolean existsByOrganizationIdAndCountryCodeAndPhone(Integer orgId, String countryCode, String phone);
}

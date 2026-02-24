package com.clinicos.service.repository;

import com.clinicos.service.entity.Bill;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillRepository extends JpaRepository<Bill, Integer> {

    Optional<Bill> findByUuid(String uuid);

    List<Bill> findByOrganizationId(Integer orgId);

    List<Bill> findByOrganizationIdAndIsPaidFalse(Integer orgId);

    List<Bill> findByPatientId(Integer patientId);

    Optional<Bill> findByQueueEntryId(Integer queueEntryId);

    @Query("SELECT b FROM Bill b WHERE b.organization.id = :orgId AND b.createdAt BETWEEN :start AND :end ORDER BY b.createdAt DESC")
    List<Bill> findByOrgAndDateRange(@Param("orgId") Integer orgId, @Param("start") Instant start, @Param("end") Instant end);

    @Query("SELECT SUM(b.totalAmount) FROM Bill b WHERE b.organization.id = :orgId AND b.isPaid = true AND b.createdAt BETWEEN :start AND :end")
    BigDecimal sumPaidAmountByOrgAndDateRange(@Param("orgId") Integer orgId, @Param("start") Instant start, @Param("end") Instant end);

    // --- Paginated list queries ---

    @Query("SELECT b FROM Bill b WHERE b.organization.id = :orgId ORDER BY b.createdAt DESC")
    List<Bill> findByOrgIdOrderByCreatedDesc(@Param("orgId") Integer orgId, Pageable pageable);

    @Query("SELECT b FROM Bill b WHERE b.organization.id = :orgId AND b.isPaid = :isPaid ORDER BY b.createdAt DESC")
    List<Bill> findByOrgIdAndPaidStatusOrderByCreatedDesc(
            @Param("orgId") Integer orgId, @Param("isPaid") boolean isPaid, Pageable pageable);

    @Query("SELECT b FROM Bill b WHERE b.organization.id = :orgId AND b.createdAt BETWEEN :start AND :end ORDER BY b.createdAt DESC")
    List<Bill> findByOrgAndDateRangePaginated(
            @Param("orgId") Integer orgId, @Param("start") Instant start, @Param("end") Instant end, Pageable pageable);

    @Query("SELECT b FROM Bill b WHERE b.organization.id = :orgId AND b.createdAt BETWEEN :start AND :end AND b.isPaid = :isPaid ORDER BY b.createdAt DESC")
    List<Bill> findByOrgAndDateRangeAndPaidStatusPaginated(
            @Param("orgId") Integer orgId, @Param("start") Instant start, @Param("end") Instant end,
            @Param("isPaid") boolean isPaid, Pageable pageable);

    // --- Aggregate queries for summary (no data loaded into memory) ---

    @Query("SELECT COUNT(b), COALESCE(SUM(b.totalAmount), 0), COALESCE(SUM(CASE WHEN b.isPaid = true THEN b.totalAmount ELSE 0 END), 0) " +
            "FROM Bill b WHERE b.organization.id = :orgId")
    Object[] getBillSummaryByOrg(@Param("orgId") Integer orgId);

    @Query("SELECT COUNT(b), COALESCE(SUM(b.totalAmount), 0), COALESCE(SUM(CASE WHEN b.isPaid = true THEN b.totalAmount ELSE 0 END), 0) " +
            "FROM Bill b WHERE b.organization.id = :orgId AND b.createdAt BETWEEN :start AND :end")
    Object[] getBillSummaryByOrgAndDateRange(@Param("orgId") Integer orgId, @Param("start") Instant start, @Param("end") Instant end);
}

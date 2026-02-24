package com.clinicos.service.repository;

import com.clinicos.service.entity.Bill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
    java.math.BigDecimal sumPaidAmountByOrgAndDateRange(@Param("orgId") Integer orgId, @Param("start") Instant start, @Param("end") Instant end);
}

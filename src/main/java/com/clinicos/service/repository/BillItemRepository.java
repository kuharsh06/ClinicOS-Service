package com.clinicos.service.repository;

import com.clinicos.service.entity.BillItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BillItemRepository extends JpaRepository<BillItem, Integer> {

    Optional<BillItem> findByUuid(String uuid);

    List<BillItem> findByBillIdOrderBySortOrderAsc(Integer billId);

    void deleteByBillId(Integer billId);
}

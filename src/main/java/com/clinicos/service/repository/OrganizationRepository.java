package com.clinicos.service.repository;

import com.clinicos.service.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, Integer> {

    Optional<Organization> findByUuid(String uuid);

    List<Organization> findByCreatedById(Integer userId);

    List<Organization> findByDeletedAtIsNull();
}

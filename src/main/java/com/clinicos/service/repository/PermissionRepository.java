package com.clinicos.service.repository;

import com.clinicos.service.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Integer> {

    Optional<Permission> findByUuid(String uuid);

    Optional<Permission> findByName(String name);

    List<Permission> findByCategory(String category);

    boolean existsByName(String name);
}

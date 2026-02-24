package com.clinicos.service.repository;

import com.clinicos.service.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Integer> {

    Optional<Role> findByUuid(String uuid);

    Optional<Role> findByName(String name);

    boolean existsByName(String name);
}

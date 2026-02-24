package com.clinicos.service.repository;

import com.clinicos.service.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Integer> {

    Optional<RolePermission> findByUuid(String uuid);

    List<RolePermission> findByRoleId(Integer roleId);

    @Query("SELECT rp FROM RolePermission rp JOIN FETCH rp.permission WHERE rp.role.id = :roleId")
    List<RolePermission> findByRoleIdWithPermissions(@Param("roleId") Integer roleId);

    boolean existsByRoleIdAndPermissionId(Integer roleId, Integer permissionId);

    void deleteByRoleIdAndPermissionId(Integer roleId, Integer permissionId);
}

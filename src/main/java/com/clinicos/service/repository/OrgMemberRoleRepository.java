package com.clinicos.service.repository;

import com.clinicos.service.entity.OrgMemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrgMemberRoleRepository extends JpaRepository<OrgMemberRole, Integer> {

    Optional<OrgMemberRole> findByUuid(String uuid);

    List<OrgMemberRole> findByOrgMemberId(Integer orgMemberId);

    @Query("SELECT omr FROM OrgMemberRole omr JOIN FETCH omr.role WHERE omr.orgMember.id = :memberId")
    List<OrgMemberRole> findByOrgMemberIdWithRoles(@Param("memberId") Integer memberId);

    boolean existsByOrgMemberIdAndRoleId(Integer orgMemberId, Integer roleId);

    @Modifying
    void deleteByOrgMemberIdAndRoleId(Integer orgMemberId, Integer roleId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM OrgMemberRole omr WHERE omr.orgMember.id = :orgMemberId")
    void deleteByOrgMemberId(@Param("orgMemberId") Integer orgMemberId);

    List<OrgMemberRole> findByRoleId(Integer roleId);
}

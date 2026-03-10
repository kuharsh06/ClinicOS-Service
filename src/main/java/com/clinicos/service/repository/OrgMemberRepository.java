package com.clinicos.service.repository;

import com.clinicos.service.entity.OrgMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrgMemberRepository extends JpaRepository<OrgMember, Integer> {

    Optional<OrgMember> findByUuid(String uuid);

    Optional<OrgMember> findByUserIdAndOrganizationId(Integer userId, Integer orgId);

    List<OrgMember> findByOrganizationIdAndIsActiveTrue(Integer orgId);

    List<OrgMember> findByUserId(Integer userId);

    @Query("SELECT om FROM OrgMember om WHERE om.organization.id = :orgId AND om.isActive = true AND om.deletedAt IS NULL")
    List<OrgMember> findActiveMembers(@Param("orgId") Integer orgId);

    boolean existsByUserIdAndOrganizationId(Integer userId, Integer orgId);

    Optional<OrgMember> findByOrganizationIdAndUserId(Integer orgId, Integer userId);

    @Query("SELECT om FROM OrgMember om JOIN FETCH om.user WHERE om.organization.id = :orgId")
    List<OrgMember> findByOrganizationIdWithUser(@Param("orgId") Integer orgId);

    @Query("SELECT om FROM OrgMember om JOIN FETCH om.user WHERE om.organization.id = :orgId AND om.user.uuid = :userUuid")
    Optional<OrgMember> findByOrgIdAndUserUuid(@Param("orgId") Integer orgId, @Param("userUuid") String userUuid);

    @Query("SELECT om FROM OrgMember om JOIN FETCH om.user LEFT JOIN FETCH om.assignedDoctor ad LEFT JOIN FETCH ad.user WHERE om.id = :memberId")
    Optional<OrgMember> findByIdWithAssignedDoctor(@Param("memberId") Integer memberId);

    List<OrgMember> findByAssignedDoctorId(Integer doctorMemberId);
}

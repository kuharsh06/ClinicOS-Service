package com.clinicos.service.repository;

import com.clinicos.service.entity.VisitImage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VisitImageRepository extends JpaRepository<VisitImage, Integer> {

    Optional<VisitImage> findByUuid(String uuid);

    List<VisitImage> findByVisitIdAndDeletedAtIsNullOrderBySortOrderAsc(Integer visitId);

    // Batch query — avoids N+1 when loading images for multiple visits
    List<VisitImage> findByVisitIdInAndDeletedAtIsNullOrderBySortOrderAsc(List<Integer> visitIds);

    long countByVisitId(Integer visitId);

    // By patient — first page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.patient.id = :patientId " +
            "AND vi.organization.id = :orgId AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByPatientAndOrg(
            @Param("patientId") Integer patientId,
            @Param("orgId") Integer orgId,
            Pageable pageable);

    // By patient — cursor page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.patient.id = :patientId " +
            "AND vi.organization.id = :orgId AND vi.id < :cursorId AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByPatientAndOrgAfterCursor(
            @Param("patientId") Integer patientId,
            @Param("orgId") Integer orgId,
            @Param("cursorId") Integer cursorId,
            Pageable pageable);

    // By patient + file type — first page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.patient.id = :patientId " +
            "AND vi.organization.id = :orgId AND vi.fileType = :fileType AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByPatientAndOrgAndFileType(
            @Param("patientId") Integer patientId,
            @Param("orgId") Integer orgId,
            @Param("fileType") String fileType,
            Pageable pageable);

    // By patient + file type — cursor page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.patient.id = :patientId " +
            "AND vi.organization.id = :orgId AND vi.fileType = :fileType " +
            "AND vi.id < :cursorId AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByPatientAndOrgAndFileTypeAfterCursor(
            @Param("patientId") Integer patientId,
            @Param("orgId") Integer orgId,
            @Param("fileType") String fileType,
            @Param("cursorId") Integer cursorId,
            Pageable pageable);

    // By doctor — first page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.doctorUuid = :doctorUuid " +
            "AND vi.organization.id = :orgId AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByDoctorAndOrg(
            @Param("doctorUuid") String doctorUuid,
            @Param("orgId") Integer orgId,
            Pageable pageable);

    // By doctor — cursor page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.doctorUuid = :doctorUuid " +
            "AND vi.organization.id = :orgId AND vi.id < :cursorId AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByDoctorAndOrgAfterCursor(
            @Param("doctorUuid") String doctorUuid,
            @Param("orgId") Integer orgId,
            @Param("cursorId") Integer cursorId,
            Pageable pageable);

    // By org — first page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.organization.id = :orgId " +
            "AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByOrg(@Param("orgId") Integer orgId, Pageable pageable);

    // By org — cursor page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.organization.id = :orgId " +
            "AND vi.id < :cursorId AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByOrgAfterCursor(
            @Param("orgId") Integer orgId,
            @Param("cursorId") Integer cursorId,
            Pageable pageable);

    // By org + file type — first page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.organization.id = :orgId " +
            "AND vi.fileType = :fileType AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByOrgAndFileType(
            @Param("orgId") Integer orgId,
            @Param("fileType") String fileType,
            Pageable pageable);

    // By org + file type — cursor page
    @Query("SELECT vi FROM VisitImage vi WHERE vi.organization.id = :orgId " +
            "AND vi.fileType = :fileType AND vi.id < :cursorId AND vi.deletedAt IS NULL ORDER BY vi.id DESC")
    List<VisitImage> findByOrgAndFileTypeAfterCursor(
            @Param("orgId") Integer orgId,
            @Param("fileType") String fileType,
            @Param("cursorId") Integer cursorId,
            Pageable pageable);
}

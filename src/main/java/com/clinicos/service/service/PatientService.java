package com.clinicos.service.service;

import com.clinicos.service.dto.request.CreateVisitRequest;
import com.clinicos.service.dto.response.*;
import com.clinicos.service.entity.*;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import com.clinicos.service.security.CustomUserDetails;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatientService {

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    /**
     * Get paginated list of patients.
     * Supports cursor-based pagination, search, and sorting.
     */
    @Transactional(readOnly = true)
    public PatientListResponse listPatients(String orgUuid, String afterCursor, Integer limit,
                                            String search, String sort) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        int actualLimit = Math.min(limit != null ? limit : DEFAULT_LIMIT, MAX_LIMIT);
        PageRequest page = PageRequest.of(0, actualLimit + 1);
        String sortKey = sort != null ? sort : "last_visit_desc";

        List<Patient> patients;
        if (search != null && search.length() >= 2) {
            // Search by name OR phone in a single DB query (no cursor for search — always page 1)
            patients = patientRepository.searchByNameOrPhone(org.getId(), search, page);
        } else if (afterCursor != null) {
            // Keyset cursor pagination — decode cursor and use WHERE clause
            Patient cursorPatient = decodeCursorToPatient(afterCursor);
            if (cursorPatient != null) {
                patients = fetchPatientsAfterCursor(org.getId(), sortKey, cursorPatient, page);
            } else {
                patients = fetchPatientsFirstPage(org.getId(), sortKey, page);
            }
        } else {
            // First page — no cursor
            patients = fetchPatientsFirstPage(org.getId(), sortKey, page);
        }

        boolean hasMore = patients.size() > actualLimit;
        List<Patient> pagePatients = hasMore ? patients.subList(0, actualLimit) : patients;

        List<PatientListResponse.PatientListItem> items = pagePatients.stream()
                .map(this::toPatientListItem)
                .collect(Collectors.toList());

        String nextCursor = hasMore && !pagePatients.isEmpty()
                ? encodePatientCursor(pagePatients.get(pagePatients.size() - 1), sortKey)
                : null;

        return PatientListResponse.builder()
                .patients(items)
                .meta(PatientListResponse.Meta.builder()
                        .pagination(PatientListResponse.CursorPagination.builder()
                                .nextCursor(nextCursor)
                                .hasMore(hasMore)
                                .build())
                        .serverTimestamp(System.currentTimeMillis())
                        .build())
                .build();
    }

    /**
     * Get patient visit history (thread).
     */
    @Transactional(readOnly = true)
    public PatientThreadResponse getPatientThread(String orgUuid, String patientUuid,
                                                   String afterCursor, Integer limit) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        Patient patient = patientRepository.findByUuid(patientUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientUuid));

        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Patient", patientUuid);
        }

        int actualLimit = Math.min(limit != null ? limit : 10, 50);

        // Check if user has permission to view full clinical data
        boolean canViewFull = canViewFullClinicalData(org);
        PageRequest page = PageRequest.of(0, actualLimit + 1);

        // DB-level keyset pagination with JOIN FETCH to avoid N+1
        List<Visit> visits;
        if (afterCursor != null) {
            Visit cursorVisit = decodeCursorToVisit(afterCursor);
            if (cursorVisit != null) {
                visits = visitRepository.findByPatientIdWithDetailsAfterCursor(
                        patient.getId(), cursorVisit.getVisitDate(), cursorVisit.getId(), page);
            } else {
                visits = visitRepository.findByPatientIdWithDetailsOrderByDateDesc(patient.getId(), page);
            }
        } else {
            visits = visitRepository.findByPatientIdWithDetailsOrderByDateDesc(patient.getId(), page);
        }

        boolean hasMore = visits.size() > actualLimit;
        List<Visit> pageVisits = hasMore ? visits.subList(0, actualLimit) : visits;

        List<PatientThreadResponse.VisitDto> visitDtos = pageVisits.stream()
                .map(v -> toVisitDto(v, canViewFull))
                .collect(Collectors.toList());

        String nextCursor = hasMore && !pageVisits.isEmpty()
                ? encodeVisitCursor(pageVisits.get(pageVisits.size() - 1))
                : null;

        return PatientThreadResponse.builder()
                .visits(visitDtos)
                .meta(PatientThreadResponse.Meta.builder()
                        .pagination(PatientListResponse.CursorPagination.builder()
                                .nextCursor(nextCursor)
                                .hasMore(hasMore)
                                .build())
                        .build())
                .build();
    }

    /**
     * Create a new visit for a patient.
     */
    @Transactional
    public PatientThreadResponse.VisitDto createVisit(String orgUuid, String patientUuid,
                                                        CreateVisitRequest request, Integer userId) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        Patient patient = patientRepository.findByUuid(patientUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Patient", patientUuid));

        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Patient", patientUuid);
        }

        OrgMember createdBy = orgMemberRepository.findByOrganizationIdAndUserId(org.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", userId.toString()));

        QueueEntry queueEntry = null;
        if (request.getQueueEntryId() != null) {
            queueEntry = queueEntryRepository.findByUuid(request.getQueueEntryId()).orElse(null);
            // Validate queue entry belongs to same org and patient
            if (queueEntry != null) {
                boolean wrongOrg = !queueEntry.getQueue().getOrganization().getId().equals(org.getId());
                boolean wrongPatient = !queueEntry.getPatient().getId().equals(patient.getId());
                if (wrongOrg || wrongPatient) {
                    queueEntry = null; // silently ignore invalid reference
                    log.warn("Queue entry {} does not match org {} or patient {}",
                            request.getQueueEntryId(), orgUuid, patientUuid);
                }
            }
        }

        Visit visit = Visit.builder()
                .patient(patient)
                .organization(org)
                .queueEntry(queueEntry)
                .visitDate(LocalDate.now())
                .complaintTags(toJson(request.getComplaintTags()))
                .data(toJson(request.getData()))
                .schemaVersion(request.getSchemaVersion())
                .createdBy(createdBy)
                .build();

        visitRepository.save(visit);

        // Update patient stats
        patient.setTotalVisits(patient.getTotalVisits() + 1);
        patient.setLastVisitDate(LocalDate.now());
        if (request.getComplaintTags() != null && !request.getComplaintTags().isEmpty()) {
            patient.setLastComplaintTags(toJson(request.getComplaintTags()));
        }
        if (patient.getTotalVisits() > 3) {
            patient.setIsRegular(true);
        }
        patientRepository.save(patient);

        log.info("Visit {} created for patient {} by user {}", visit.getUuid(), patientUuid, userId);

        return toVisitDto(visit, true);
    }

    /**
     * Update an existing visit.
     */
    @Transactional
    public PatientThreadResponse.VisitDto updateVisit(String orgUuid, String patientUuid,
                                                       String visitUuid, CreateVisitRequest request) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        Visit visit = visitRepository.findByUuid(visitUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", visitUuid));

        if (!visit.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Visit", visitUuid);
        }

        if (request.getComplaintTags() != null) {
            visit.setComplaintTags(toJson(request.getComplaintTags()));
        }
        if (request.getData() != null) {
            visit.setData(toJson(request.getData()));
        }
        if (request.getSchemaVersion() != null) {
            visit.setSchemaVersion(request.getSchemaVersion());
        }

        visitRepository.save(visit);
        log.info("Visit {} updated", visitUuid);

        return toVisitDto(visit, true);
    }

    /**
     * Search patients for autocomplete.
     */
    @Transactional(readOnly = true)
    public PatientSearchResponse searchPatients(String orgUuid, String query, Integer limit) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        int actualLimit = Math.min(limit != null ? limit : 5, 20);

        List<Patient> patients;
        if (query != null && query.length() >= 2) {
            // Single DB query searches both name and phone
            patients = patientRepository.searchByNameOrPhone(org.getId(), query, PageRequest.of(0, actualLimit));
        } else {
            patients = List.of();
        }

        List<PatientSearchResponse.PatientSearchResult> results = patients.stream()
                .map(p -> PatientSearchResponse.PatientSearchResult.builder()
                        .patientId(p.getUuid())
                        .phone(p.getPhone())
                        .name(p.getName())
                        .age(p.getAge())
                        .gender(p.getGender() != null ? p.getGender().getValue() : null)
                        .lastVisitDate(p.getLastVisitDate() != null ? p.getLastVisitDate().toString() : null)
                        .build())
                .collect(Collectors.toList());

        return PatientSearchResponse.builder()
                .results(results)
                .build();
    }

    private PatientListResponse.PatientListItem toPatientListItem(Patient patient) {
        return PatientListResponse.PatientListItem.builder()
                .patientId(patient.getUuid())
                .phone(patient.getPhone())
                .name(patient.getName())
                .age(patient.getAge())
                .gender(patient.getGender() != null ? patient.getGender().getValue() : null)
                .totalVisits(patient.getTotalVisits())
                .lastVisitDate(patient.getLastVisitDate() != null ? patient.getLastVisitDate().toString() : null)
                .lastComplaintTags(parseJsonArray(patient.getLastComplaintTags()))
                .isRegular(patient.getIsRegular())
                .createdAt(patient.getCreatedAt().toString())
                .build();
    }

    private PatientThreadResponse.VisitDto toVisitDto(Visit visit, boolean canViewFull) {
        Map<String, Object> data = null;
        List<PatientThreadResponse.ImageRef> images = List.of();

        if (canViewFull) {
            data = fromJson(visit.getData());
            // TODO: Parse images from data or separate storage
        }

        OrgMember creator = visit.getCreatedBy();
        String creatorRole = "doctor";  // Default to doctor
        // TODO: Get actual role from OrgMemberRole

        return PatientThreadResponse.VisitDto.builder()
                .visitId(visit.getUuid())
                .patientId(visit.getPatient().getUuid())
                .queueEntryId(visit.getQueueEntry() != null ? visit.getQueueEntry().getUuid() : null)
                .date(visit.getVisitDate().toString())
                .complaintTags(parseJsonArray(visit.getComplaintTags()))
                .data(data)
                .schemaVersion(visit.getSchemaVersion())
                .images(images)
                .createdBy(PatientThreadResponse.CreatedBy.builder()
                        .userId(creator.getUser().getUuid())
                        .name(creator.getUser().getName())
                        .role(creatorRole)
                        .build())
                .createdAt(visit.getCreatedAt().toString())
                .updatedAt(visit.getUpdatedAt().toString())
                .build();
    }

    private boolean canViewFullClinicalData(Organization org) {
        // Check org setting and user permission
        Map<String, Object> settings = fromJson(org.getSettings());
        String visibility = settings != null ? (String) settings.get("clinicalDataVisibility") : "all_members";

        if ("all_members".equals(visibility)) {
            return true;
        }

        // Check if user has patient:view_full permission
        try {
            CustomUserDetails userDetails = (CustomUserDetails) SecurityContextHolder.getContext()
                    .getAuthentication().getPrincipal();
            return userDetails.hasPermission("patient:view_full");
        } catch (Exception e) {
            return false;
        }
    }

    // --- Keyset cursor pagination helpers ---

    private List<Patient> fetchPatientsFirstPage(Integer orgId, String sortKey, PageRequest page) {
        return switch (sortKey) {
            case "name_asc" -> patientRepository.findByOrgIdOrderByNameAsc(orgId, page);
            case "created_desc" -> patientRepository.findByOrgIdOrderByCreatedDesc(orgId, page);
            case "visits_desc" -> patientRepository.findByOrgIdOrderByVisitsDesc(orgId, page);
            default -> patientRepository.findByOrgIdOrderByLastVisitDesc(orgId, page);
        };
    }

    private List<Patient> fetchPatientsAfterCursor(Integer orgId, String sortKey, Patient cursor, PageRequest page) {
        return switch (sortKey) {
            case "name_asc" -> patientRepository.findByOrgIdOrderByNameAscAfterCursor(
                    orgId, cursor.getName(), cursor.getId(), page);
            case "created_desc" -> patientRepository.findByOrgIdOrderByCreatedDescAfterCursor(
                    orgId, cursor.getCreatedAt(), cursor.getId(), page);
            case "visits_desc" -> patientRepository.findByOrgIdOrderByVisitsDescAfterCursor(
                    orgId, cursor.getTotalVisits(), cursor.getId(), page);
            default -> {
                if (cursor.getLastVisitDate() != null) {
                    yield patientRepository.findByOrgIdOrderByLastVisitDescAfterCursor(
                            orgId, cursor.getLastVisitDate(), cursor.getId(), page);
                } else {
                    // Cursor patient has null lastVisitDate — only get others with null date after this id
                    yield patientRepository.findByOrgIdOrderByLastVisitDescAfterCursorNull(
                            orgId, cursor.getId(), page);
                }
            }
        };
    }

    /**
     * Encode cursor as Base64 of "sortValue|id" for patient list pagination.
     * The cursor captures the sort value + internal ID of the last item.
     */
    private String encodePatientCursor(Patient patient, String sortKey) {
        String value = switch (sortKey) {
            case "name_asc" -> patient.getName() + "|" + patient.getId();
            case "created_desc" -> patient.getCreatedAt().toString() + "|" + patient.getId();
            case "visits_desc" -> patient.getTotalVisits() + "|" + patient.getId();
            default -> (patient.getLastVisitDate() != null ? patient.getLastVisitDate().toString() : "null")
                    + "|" + patient.getId();
        };
        return Base64.getEncoder().encodeToString(value.getBytes());
    }

    /**
     * Decode cursor back to Patient entity (only the fields needed for the WHERE clause).
     * Returns null if cursor is invalid.
     */
    private Patient decodeCursorToPatient(String cursor) {
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor));
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) return null;

            Integer id = Integer.parseInt(parts[1]);
            return patientRepository.findById(id).orElse(null);
        } catch (Exception e) {
            log.warn("Invalid patient cursor: {}", cursor);
            return null;
        }
    }

    /**
     * Encode visit cursor as Base64 of "visitDate|id".
     */
    private String encodeVisitCursor(Visit visit) {
        String value = visit.getVisitDate().toString() + "|" + visit.getId();
        return Base64.getEncoder().encodeToString(value.getBytes());
    }

    /**
     * Decode visit cursor. Returns a Visit with just the fields needed for the WHERE clause.
     * Returns null if cursor is invalid.
     */
    private Visit decodeCursorToVisit(String cursor) {
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor));
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) return null;

            Integer id = Integer.parseInt(parts[1]);
            return visitRepository.findById(id).orElse(null);
        } catch (Exception e) {
            log.warn("Invalid visit cursor: {}", cursor);
            return null;
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Error converting to JSON", e);
            return null;
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.error("Error parsing JSON", e);
            return null;
        }
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isEmpty()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Error parsing JSON array", e);
            return List.of();
        }
    }
}

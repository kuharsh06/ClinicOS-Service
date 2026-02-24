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

        List<Patient> patients;
        if (search != null && search.length() >= 2) {
            // Search by name or phone
            patients = patientRepository.searchByName(org.getId(), search);
            // Also search by phone if the search looks like a phone number
            if (search.matches("\\d+")) {
                List<Patient> phoneMatches = patientRepository.findByOrganizationId(org.getId())
                        .stream()
                        .filter(p -> p.getPhone().contains(search))
                        .toList();
                // Merge results, avoiding duplicates
                for (Patient p : phoneMatches) {
                    if (patients.stream().noneMatch(existing -> existing.getId().equals(p.getId()))) {
                        patients.add(p);
                    }
                }
            }
        } else {
            patients = patientRepository.findByOrganizationId(org.getId());
        }

        // Apply sorting
        patients = sortPatients(patients, sort != null ? sort : "last_visit_desc");

        // Apply cursor-based pagination
        int startIndex = 0;
        if (afterCursor != null) {
            String cursorPatientId = decodeCursor(afterCursor);
            for (int i = 0; i < patients.size(); i++) {
                if (patients.get(i).getUuid().equals(cursorPatientId)) {
                    startIndex = i + 1;
                    break;
                }
            }
        }

        int endIndex = Math.min(startIndex + actualLimit, patients.size());
        List<Patient> pagePatients = patients.subList(startIndex, endIndex);
        boolean hasMore = endIndex < patients.size();

        List<PatientListResponse.PatientListItem> items = pagePatients.stream()
                .map(this::toPatientListItem)
                .collect(Collectors.toList());

        String nextCursor = hasMore && !pagePatients.isEmpty()
                ? encodeCursor(pagePatients.get(pagePatients.size() - 1).getUuid())
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

        List<Visit> visits = visitRepository.findByPatientIdOrderByVisitDateDesc(patient.getId());

        // Check if user has permission to view full clinical data
        boolean canViewFull = canViewFullClinicalData(org);

        // Apply cursor-based pagination
        int startIndex = 0;
        if (afterCursor != null) {
            String cursorVisitId = decodeCursor(afterCursor);
            for (int i = 0; i < visits.size(); i++) {
                if (visits.get(i).getUuid().equals(cursorVisitId)) {
                    startIndex = i + 1;
                    break;
                }
            }
        }

        int endIndex = Math.min(startIndex + actualLimit, visits.size());
        List<Visit> pageVisits = visits.subList(startIndex, endIndex);
        boolean hasMore = endIndex < visits.size();

        List<PatientThreadResponse.VisitDto> visitDtos = pageVisits.stream()
                .map(v -> toVisitDto(v, canViewFull))
                .collect(Collectors.toList());

        String nextCursor = hasMore && !pageVisits.isEmpty()
                ? encodeCursor(pageVisits.get(pageVisits.size() - 1).getUuid())
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
            patients = patientRepository.searchByName(org.getId(), query);
            // Also search by phone
            if (query.matches("\\d+")) {
                List<Patient> phoneMatches = patientRepository.findByOrganizationId(org.getId())
                        .stream()
                        .filter(p -> p.getPhone().contains(query))
                        .limit(actualLimit)
                        .toList();
                for (Patient p : phoneMatches) {
                    if (patients.stream().noneMatch(existing -> existing.getId().equals(p.getId()))) {
                        patients.add(p);
                    }
                }
            }
        } else {
            patients = List.of();
        }

        List<PatientSearchResponse.PatientSearchResult> results = patients.stream()
                .limit(actualLimit)
                .map(p -> PatientSearchResponse.PatientSearchResult.builder()
                        .patientId(p.getUuid())
                        .phone(p.getPhone())
                        .name(p.getName())
                        .age(p.getAge())
                        .gender(p.getGender() != null ? p.getGender().name() : null)
                        .lastVisitDate(p.getLastVisitDate() != null ? p.getLastVisitDate().toString() : null)
                        .build())
                .collect(Collectors.toList());

        return PatientSearchResponse.builder()
                .results(results)
                .build();
    }

    private List<Patient> sortPatients(List<Patient> patients, String sort) {
        return switch (sort) {
            case "name_asc" -> patients.stream()
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .collect(Collectors.toList());
            case "created_desc" -> patients.stream()
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .collect(Collectors.toList());
            case "visits_desc" -> patients.stream()
                    .sorted((a, b) -> b.getTotalVisits().compareTo(a.getTotalVisits()))
                    .collect(Collectors.toList());
            default -> patients.stream()  // last_visit_desc
                    .sorted((a, b) -> {
                        if (a.getLastVisitDate() == null) return 1;
                        if (b.getLastVisitDate() == null) return -1;
                        return b.getLastVisitDate().compareTo(a.getLastVisitDate());
                    })
                    .collect(Collectors.toList());
        };
    }

    private PatientListResponse.PatientListItem toPatientListItem(Patient patient) {
        return PatientListResponse.PatientListItem.builder()
                .patientId(patient.getUuid())
                .phone(patient.getPhone())
                .name(patient.getName())
                .age(patient.getAge())
                .gender(patient.getGender() != null ? patient.getGender().name() : null)
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

    private String encodeCursor(String id) {
        return Base64.getEncoder().encodeToString(id.getBytes());
    }

    private String decodeCursor(String cursor) {
        try {
            return new String(Base64.getDecoder().decode(cursor));
        } catch (Exception e) {
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

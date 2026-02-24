package com.clinicos.service.service;

import com.clinicos.service.dto.request.CreateBillRequest;
import com.clinicos.service.dto.request.CreateBillTemplateRequest;
import com.clinicos.service.dto.response.*;
import com.clinicos.service.entity.*;
import com.clinicos.service.exception.BusinessException;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingService {

    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final BillItemTemplateRepository billItemTemplateRepository;
    private final PatientRepository patientRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;

    /**
     * Create a new bill.
     */
    @Transactional
    public BillResponse createBill(String orgUuid, CreateBillRequest request, Integer userId) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        Patient patient = patientRepository.findByUuid(request.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient", request.getPatientId()));

        if (!patient.getOrganization().getId().equals(org.getId())) {
            throw new BusinessException("Patient does not belong to this organization");
        }

        QueueEntry queueEntry = queueEntryRepository.findByUuid(request.getQueueEntryId())
                .orElseThrow(() -> new ResourceNotFoundException("Queue Entry", request.getQueueEntryId()));

        if (!queueEntry.getQueue().getOrganization().getId().equals(org.getId())) {
            throw new BusinessException("Queue entry does not belong to this organization");
        }

        // Check if bill already exists for this queue entry
        if (billRepository.findByQueueEntryId(queueEntry.getId()).isPresent()) {
            throw new BusinessException("Bill already exists for this queue entry");
        }

        OrgMember createdBy = orgMemberRepository.findByOrganizationIdAndUserId(org.getId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", userId.toString()));

        // Get doctor name from queue
        String doctorName = queueEntry.getQueue().getDoctor().getUser().getName();

        // Calculate total
        int totalAmount = request.getItems().stream()
                .mapToInt(item -> item.getAmount() != null ? item.getAmount() : 0)
                .sum();

        // Create bill
        Bill bill = Bill.builder()
                .organization(org)
                .patient(patient)
                .queueEntryId(queueEntry.getId())
                .totalAmount(BigDecimal.valueOf(totalAmount))
                .isPaid(false)
                .patientName(patient.getName())
                .patientPhone(patient.getPhone())
                .tokenNumber(queueEntry.getTokenNumber())
                .doctorName(doctorName)
                .createdBy(createdBy)
                .build();

        billRepository.save(bill);

        // Create bill items
        List<BillItem> billItems = new ArrayList<>();
        int sortOrder = 0;
        for (CreateBillRequest.BillItemInput itemInput : request.getItems()) {
            BillItem item = BillItem.builder()
                    .bill(bill)
                    .name(itemInput.getName())
                    .amount(BigDecimal.valueOf(itemInput.getAmount() != null ? itemInput.getAmount() : 0))
                    .sortOrder(sortOrder++)
                    .build();
            billItemRepository.save(item);
            billItems.add(item);
        }

        // Update queue entry to mark as billed
        queueEntry.setIsBilled(true);
        queueEntry.setBill(bill);
        queueEntryRepository.save(queueEntry);

        log.info("Bill {} created for patient {} by user {}", bill.getUuid(), request.getPatientId(), userId);

        // TODO: Send SMS if requested
        if (Boolean.TRUE.equals(request.getSendSMS())) {
            log.info("SMS bill notification requested for bill {}", bill.getUuid());
            // Will be implemented in SMS service
        }

        return toBillResponse(bill, billItems, org.getName());
    }

    /**
     * Get a bill by ID.
     */
    @Transactional(readOnly = true)
    public BillResponse getBill(String orgUuid, String billUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        Bill bill = billRepository.findByUuid(billUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", billUuid));

        if (!bill.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Bill", billUuid);
        }

        List<BillItem> items = billItemRepository.findByBillIdOrderBySortOrderAsc(bill.getId());

        return toBillResponse(bill, items, org.getName());
    }

    /**
     * Mark a bill as paid.
     */
    @Transactional
    public BillResponse markBillPaid(String orgUuid, String billUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        Bill bill = billRepository.findByUuid(billUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", billUuid));

        if (!bill.getOrganization().getId().equals(org.getId())) {
            throw new ResourceNotFoundException("Bill", billUuid);
        }

        if (bill.getIsPaid()) {
            throw new BusinessException("Bill is already marked as paid");
        }

        bill.setIsPaid(true);
        bill.setPaidAt(Instant.now());
        billRepository.save(bill);

        log.info("Bill {} marked as paid", billUuid);

        List<BillItem> items = billItemRepository.findByBillIdOrderBySortOrderAsc(bill.getId());

        return toBillResponse(bill, items, org.getName());
    }

    /**
     * List bills with optional filtering.
     */
    @Transactional(readOnly = true)
    public BillListResponse listBills(String orgUuid, String date, String status,
                                       String afterCursor, Integer limit) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        int actualLimit = Math.min(limit != null ? limit : 20, 50);
        ZoneId IST = ZoneId.of("Asia/Kolkata");

        // Parse date range if provided
        Instant startOfDay = null;
        Instant endOfDay = null;
        if (date != null) {
            LocalDate filterDate = LocalDate.parse(date);
            startOfDay = filterDate.atStartOfDay(IST).toInstant();
            endOfDay = filterDate.plusDays(1).atStartOfDay(IST).toInstant();
        }

        // Summary via DB aggregation (no bills loaded into memory)
        Object[] summary;
        if (startOfDay != null) {
            summary = billRepository.getBillSummaryByOrgAndDateRange(org.getId(), startOfDay, endOfDay);
        } else {
            summary = billRepository.getBillSummaryByOrg(org.getId());
        }
        long billCount = ((Number) summary[0]).longValue();
        int totalBilled = ((Number) summary[1]).intValue();
        int totalPaid = ((Number) summary[2]).intValue();
        int totalUnpaid = totalBilled - totalPaid;

        // Paginated list query (only loads current page)
        org.springframework.data.domain.PageRequest page =
                org.springframework.data.domain.PageRequest.of(0, actualLimit + 1);

        List<Bill> bills;
        boolean hasPaidFilter = "paid".equalsIgnoreCase(status) || "unpaid".equalsIgnoreCase(status);
        boolean isPaidFilter = "paid".equalsIgnoreCase(status);

        if (startOfDay != null && hasPaidFilter) {
            bills = billRepository.findByOrgAndDateRangeAndPaidStatusPaginated(
                    org.getId(), startOfDay, endOfDay, isPaidFilter, page);
        } else if (startOfDay != null) {
            bills = billRepository.findByOrgAndDateRangePaginated(org.getId(), startOfDay, endOfDay, page);
        } else if (hasPaidFilter) {
            bills = billRepository.findByOrgIdAndPaidStatusOrderByCreatedDesc(org.getId(), isPaidFilter, page);
        } else {
            bills = billRepository.findByOrgIdOrderByCreatedDesc(org.getId(), page);
        }

        boolean hasMore = bills.size() > actualLimit;
        List<Bill> pageBills = hasMore ? bills.subList(0, actualLimit) : bills;

        List<BillResponse> billResponses = pageBills.stream()
                .map(bill -> {
                    List<BillItem> items = billItemRepository.findByBillIdOrderBySortOrderAsc(bill.getId());
                    return toBillResponse(bill, items, org.getName());
                })
                .collect(Collectors.toList());

        String nextCursor = hasMore && !pageBills.isEmpty()
                ? encodeCursor(pageBills.get(pageBills.size() - 1).getUuid())
                : null;

        return BillListResponse.builder()
                .bills(billResponses)
                .summary(BillListResponse.Summary.builder()
                        .totalBilled(totalBilled)
                        .totalPaid(totalPaid)
                        .totalUnpaid(totalUnpaid)
                        .billCount((int) billCount)
                        .build())
                .meta(BillListResponse.Meta.builder()
                        .pagination(PatientListResponse.CursorPagination.builder()
                                .nextCursor(nextCursor)
                                .hasMore(hasMore)
                                .build())
                        .serverTimestamp(System.currentTimeMillis())
                        .build())
                .build();
    }

    /**
     * Get bill templates.
     */
    @Transactional(readOnly = true)
    public BillTemplatesResponse getBillTemplates(String orgUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        List<BillItemTemplate> templates = billItemTemplateRepository
                .findByOrganizationIdAndIsActiveTrueOrderBySortOrderAsc(org.getId());

        List<BillTemplatesResponse.BillTemplateDto> templateDtos = templates.stream()
                .map(t -> BillTemplatesResponse.BillTemplateDto.builder()
                        .templateId(t.getUuid())
                        .name(t.getName())
                        .defaultAmount(t.getDefaultAmount().intValue())
                        .isDefault(t.getIsDefault())
                        .sortOrder(t.getSortOrder())
                        .build())
                .collect(Collectors.toList());

        return BillTemplatesResponse.builder()
                .templates(templateDtos)
                .build();
    }

    /**
     * Create a bill template.
     */
    @Transactional
    public BillTemplatesResponse.BillTemplateDto createBillTemplate(String orgUuid,
                                                                     CreateBillTemplateRequest request) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        // Get max sort order
        List<BillItemTemplate> existing = billItemTemplateRepository.findByOrganizationId(org.getId());
        int maxSortOrder = existing.stream()
                .mapToInt(BillItemTemplate::getSortOrder)
                .max()
                .orElse(0);

        BillItemTemplate template = BillItemTemplate.builder()
                .organization(org)
                .name(request.getName())
                .defaultAmount(BigDecimal.valueOf(request.getDefaultAmount()))
                .isDefault(Boolean.TRUE.equals(request.getIsDefault()))
                .sortOrder(maxSortOrder + 1)
                .build();

        billItemTemplateRepository.save(template);

        log.info("Bill template {} created for org {}", template.getUuid(), orgUuid);

        return BillTemplatesResponse.BillTemplateDto.builder()
                .templateId(template.getUuid())
                .name(template.getName())
                .defaultAmount(template.getDefaultAmount().intValue())
                .isDefault(template.getIsDefault())
                .sortOrder(template.getSortOrder())
                .build();
    }

    private BillResponse toBillResponse(Bill bill, List<BillItem> items, String clinicName) {
        List<BillResponse.BillItem> itemDtos = items.stream()
                .map(item -> BillResponse.BillItem.builder()
                        .itemId(item.getUuid())
                        .name(item.getName())
                        .amount(item.getAmount().intValue())
                        .build())
                .collect(Collectors.toList());

        // Look up queue entry UUID (bill stores Integer FK, API needs UUID)
        String queueEntryUuid = null;
        if (bill.getQueueEntryId() != null) {
            queueEntryUuid = queueEntryRepository.findById(bill.getQueueEntryId())
                    .map(QueueEntry::getUuid)
                    .orElse(null);
        }

        return BillResponse.builder()
                .billId(bill.getUuid())
                .orgId(bill.getOrganization().getUuid())
                .patientId(bill.getPatient().getUuid())
                .queueEntryId(queueEntryUuid)
                .items(itemDtos)
                .totalAmount(bill.getTotalAmount().intValue())
                .isPaid(bill.getIsPaid())
                .paidAt(bill.getPaidAt() != null ? bill.getPaidAt().toString() : null)
                .createdAt(bill.getCreatedAt().toString())
                .patientName(bill.getPatientName())
                .patientPhone(bill.getPatientPhone())
                .tokenNumber(bill.getTokenNumber())
                .doctorName(bill.getDoctorName())
                .clinicName(clinicName)
                .build();
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
}

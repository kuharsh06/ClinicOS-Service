package com.clinicos.service.service;

import com.clinicos.service.dto.response.AnalyticsResponse;
import com.clinicos.service.entity.*;
import com.clinicos.service.enums.QueueEntryState;
import com.clinicos.service.exception.ResourceNotFoundException;
import com.clinicos.service.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final OrganizationRepository organizationRepository;
    private final OrgMemberRepository orgMemberRepository;
    private final QueueRepository queueRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final BillRepository billRepository;
    private final ComplaintTagRepository complaintTagRepository;
    private final ObjectMapper objectMapper;

    /**
     * Get dashboard analytics for the specified period.
     */
    @Transactional(readOnly = true)
    public AnalyticsResponse getAnalytics(String orgUuid, String period, String doctorUuid) {
        Organization org = organizationRepository.findByUuid(orgUuid)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", orgUuid));

        // Determine date range based on period
        LocalDate today = LocalDate.now();
        LocalDate fromDate;
        LocalDate toDate = today;

        switch (period != null ? period.toLowerCase() : "today") {
            case "week" -> fromDate = today.minusDays(7);
            case "month" -> fromDate = today.minusDays(30);
            default -> fromDate = today;  // "today"
        }

        Instant fromInstant = fromDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant toInstant = toDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        // Get previous period for comparison
        LocalDate prevFromDate = fromDate.minusDays(java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) + 1);
        LocalDate prevToDate = fromDate.minusDays(1);
        Instant prevFromInstant = prevFromDate.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant prevToInstant = prevToDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        // Get all queues for the organization (optionally filtered by doctor)
        List<com.clinicos.service.entity.Queue> queues;
        if (doctorUuid != null && !doctorUuid.isEmpty()) {
            OrgMember doctor = orgMemberRepository.findByOrganizationIdWithUser(org.getId())
                    .stream()
                    .filter(m -> m.getUser().getUuid().equals(doctorUuid))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Doctor", doctorUuid));
            queues = queueRepository.findByOrganizationIdAndStatus(org.getId(), null);
            queues = queues.stream()
                    .filter(q -> q.getDoctor().getId().equals(doctor.getId()))
                    .collect(Collectors.toList());
        } else {
            queues = queueRepository.findByOrganizationId(org.getId());
        }

        // Get all queue entries for the period
        List<QueueEntry> allEntries = new ArrayList<>();
        for (com.clinicos.service.entity.Queue queue : queues) {
            allEntries.addAll(queueEntryRepository.findByQueueIdOrderByPositionAsc(queue.getId()));
        }

        // Filter entries by registration time within the period
        List<QueueEntry> periodEntries = allEntries.stream()
                .filter(e -> e.getRegisteredAt() != null &&
                        e.getRegisteredAt() >= fromInstant.toEpochMilli() &&
                        e.getRegisteredAt() < toInstant.toEpochMilli())
                .collect(Collectors.toList());

        // Calculate summary statistics
        int totalPatients = periodEntries.size();
        int completedCount = (int) periodEntries.stream()
                .filter(e -> e.getState() == QueueEntryState.COMPLETED)
                .count();

        // Calculate average wait time (time from registered to called)
        long totalWaitMs = periodEntries.stream()
                .filter(e -> e.getCalledAt() != null && e.getRegisteredAt() != null)
                .mapToLong(e -> e.getCalledAt() - e.getRegisteredAt())
                .sum();
        int waitCount = (int) periodEntries.stream()
                .filter(e -> e.getCalledAt() != null && e.getRegisteredAt() != null)
                .count();
        long avgWaitTimeMs = waitCount > 0 ? totalWaitMs / waitCount : 0;

        // Calculate average consultation time (time from called to completed)
        long totalConsultMs = periodEntries.stream()
                .filter(e -> e.getCompletedAt() != null && e.getCalledAt() != null)
                .mapToLong(e -> e.getCompletedAt() - e.getCalledAt())
                .sum();
        int consultCount = (int) periodEntries.stream()
                .filter(e -> e.getCompletedAt() != null && e.getCalledAt() != null)
                .count();
        long avgConsultationTimeMs = consultCount > 0 ? totalConsultMs / consultCount : 0;

        // Calculate revenue
        BigDecimal totalRevenue = billRepository.sumPaidAmountByOrgAndDateRange(
                org.getId(), fromInstant, toInstant);
        int revenue = totalRevenue != null ? totalRevenue.intValue() : 0;

        // Calculate queue completion rate
        double completionRate = totalPatients > 0 ? (completedCount * 100.0 / totalPatients) : 0;

        // Find busiest hour
        Map<Integer, Long> hourCounts = periodEntries.stream()
                .filter(e -> e.getRegisteredAt() != null)
                .collect(Collectors.groupingBy(
                        e -> Instant.ofEpochMilli(e.getRegisteredAt())
                                .atZone(ZoneId.systemDefault())
                                .getHour(),
                        Collectors.counting()
                ));
        int busiestHour = hourCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(10);
        String busiestHourStr = String.format("%02d:00-%02d:00", busiestHour, busiestHour + 1);

        // Build summary
        AnalyticsResponse.Summary summary = AnalyticsResponse.Summary.builder()
                .totalPatients(totalPatients)
                .totalRevenue(revenue)
                .avgWaitTimeMs(avgWaitTimeMs)
                .avgConsultationTimeMs(avgConsultationTimeMs)
                .busiestHour(busiestHourStr)
                .queueCompletionRate(Math.round(completionRate * 10.0) / 10.0)
                .build();

        // Calculate comparison with previous period
        List<QueueEntry> prevEntries = allEntries.stream()
                .filter(e -> e.getRegisteredAt() != null &&
                        e.getRegisteredAt() >= prevFromInstant.toEpochMilli() &&
                        e.getRegisteredAt() < prevToInstant.toEpochMilli())
                .collect(Collectors.toList());

        int prevPatients = prevEntries.size();
        BigDecimal prevRevenue = billRepository.sumPaidAmountByOrgAndDateRange(
                org.getId(), prevFromInstant, prevToInstant);
        int prevRevenueInt = prevRevenue != null ? prevRevenue.intValue() : 0;

        long prevTotalWait = prevEntries.stream()
                .filter(e -> e.getCalledAt() != null && e.getRegisteredAt() != null)
                .mapToLong(e -> e.getCalledAt() - e.getRegisteredAt())
                .sum();
        int prevWaitCount = (int) prevEntries.stream()
                .filter(e -> e.getCalledAt() != null && e.getRegisteredAt() != null)
                .count();
        long prevAvgWait = prevWaitCount > 0 ? prevTotalWait / prevWaitCount : 0;

        AnalyticsResponse.Comparison comparison = AnalyticsResponse.Comparison.builder()
                .patients(buildChangeMetric(totalPatients, prevPatients))
                .revenue(buildChangeMetric(revenue, prevRevenueInt))
                .avgWait(buildChangeMetric((int) avgWaitTimeMs, (int) prevAvgWait))
                .build();

        // Build daily breakdown
        List<AnalyticsResponse.DailyBreakdown> dailyBreakdown = new ArrayList<>();
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            long dayStart = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long dayEnd = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

            int dayPatients = (int) periodEntries.stream()
                    .filter(e -> e.getRegisteredAt() != null &&
                            e.getRegisteredAt() >= dayStart &&
                            e.getRegisteredAt() < dayEnd)
                    .count();

            BigDecimal dayRevenue = billRepository.sumPaidAmountByOrgAndDateRange(
                    org.getId(),
                    Instant.ofEpochMilli(dayStart),
                    Instant.ofEpochMilli(dayEnd));

            long dayWaitTotal = periodEntries.stream()
                    .filter(e -> e.getRegisteredAt() != null &&
                            e.getRegisteredAt() >= dayStart &&
                            e.getRegisteredAt() < dayEnd &&
                            e.getCalledAt() != null)
                    .mapToLong(e -> e.getCalledAt() - e.getRegisteredAt())
                    .sum();
            int dayWaitCount = (int) periodEntries.stream()
                    .filter(e -> e.getRegisteredAt() != null &&
                            e.getRegisteredAt() >= dayStart &&
                            e.getRegisteredAt() < dayEnd &&
                            e.getCalledAt() != null)
                    .count();

            dailyBreakdown.add(AnalyticsResponse.DailyBreakdown.builder()
                    .date(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .patients(dayPatients)
                    .revenue(dayRevenue != null ? dayRevenue.intValue() : 0)
                    .avgWaitMs(dayWaitCount > 0 ? dayWaitTotal / dayWaitCount : 0L)
                    .build());
        }

        // Build top complaints
        Map<String, Long> complaintCounts = new HashMap<>();
        for (QueueEntry entry : periodEntries) {
            List<String> tags = parseJsonArray(entry.getComplaintTags());
            for (String tag : tags) {
                complaintCounts.merge(tag, 1L, Long::sum);
            }
        }

        // Get complaint tag labels
        List<ComplaintTag> orgTags = complaintTagRepository.findByOrganizationIdAndIsActiveTrueOrderBySortOrderAsc(org.getId());
        Map<String, String> tagLabels = orgTags.stream()
                .collect(Collectors.toMap(ComplaintTag::getTagKey, ComplaintTag::getLabelEn, (a, b) -> a));

        List<AnalyticsResponse.TopComplaint> topComplaints = complaintCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .map(e -> AnalyticsResponse.TopComplaint.builder()
                        .tagKey(e.getKey())
                        .label(tagLabels.getOrDefault(e.getKey(), e.getKey()))
                        .count(e.getValue().intValue())
                        .percentage(totalPatients > 0 ? Math.round(e.getValue() * 1000.0 / totalPatients) / 10.0 : 0)
                        .build())
                .collect(Collectors.toList());

        // Build hourly distribution (today only)
        List<AnalyticsResponse.HourlyDistribution> hourlyDistribution = null;
        if ("today".equalsIgnoreCase(period) || period == null) {
            hourlyDistribution = new ArrayList<>();
            for (int hour = 8; hour < 22; hour++) {
                long count = hourCounts.getOrDefault(hour, 0L);
                hourlyDistribution.add(AnalyticsResponse.HourlyDistribution.builder()
                        .hour(String.format("%02d:00", hour))
                        .patients((int) count)
                        .build());
            }
        }

        return AnalyticsResponse.builder()
                .period(period != null ? period.toLowerCase() : "today")
                .dateRange(AnalyticsResponse.DateRange.builder()
                        .from(fromDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                        .to(toDate.format(DateTimeFormatter.ISO_LOCAL_DATE))
                        .build())
                .summary(summary)
                .comparison(comparison)
                .dailyBreakdown(dailyBreakdown)
                .topComplaints(topComplaints)
                .hourlyDistribution(hourlyDistribution)
                .build();
    }

    private AnalyticsResponse.ChangeMetric buildChangeMetric(int current, int previous) {
        double changePercent = 0;
        if (previous > 0) {
            changePercent = Math.round((current - previous) * 1000.0 / previous) / 10.0;
        }
        return AnalyticsResponse.ChangeMetric.builder()
                .value(current)
                .changePercent(changePercent)
                .build();
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

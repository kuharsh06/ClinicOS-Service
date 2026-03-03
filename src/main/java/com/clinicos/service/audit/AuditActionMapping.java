package com.clinicos.service.audit;

import com.clinicos.service.enums.AuditAction;

import java.util.Map;

/**
 * Maps (HTTP method + URI pattern) → AuditAction + resourceId path variable.
 * Endpoints not in this map are silently skipped (not audited).
 */
public final class AuditActionMapping {

    private AuditActionMapping() {}

    public record ActionConfig(AuditAction action, String resourceIdParam) {
        public ActionConfig(AuditAction action) {
            this(action, null);
        }
    }

    /**
     * Key format: "METHOD:pattern" e.g. "GET:/v1/orgs/{orgId}/patients/{patientId}/thread"
     */
    public static final Map<String, ActionConfig> MAPPINGS = Map.ofEntries(

            // Auth (4)
            Map.entry("POST:/v1/auth/otp/send", new ActionConfig(AuditAction.OTP_REQUEST)),
            Map.entry("POST:/v1/auth/otp/verify", new ActionConfig(AuditAction.OTP_VERIFY)),
            Map.entry("POST:/v1/auth/token/refresh", new ActionConfig(AuditAction.TOKEN_REFRESH)),
            Map.entry("POST:/v1/auth/logout", new ActionConfig(AuditAction.LOGOUT)),

            // Org & Members (6)
            Map.entry("POST:/v1/orgs", new ActionConfig(AuditAction.CREATE_ORG)),
            Map.entry("GET:/v1/orgs/{orgId}", new ActionConfig(AuditAction.VIEW_ORG, "orgId")),
            Map.entry("PUT:/v1/orgs/{orgId}", new ActionConfig(AuditAction.UPDATE_ORG, "orgId")),
            Map.entry("POST:/v1/orgs/{orgId}/members", new ActionConfig(AuditAction.ADD_MEMBER)),
            Map.entry("GET:/v1/orgs/{orgId}/members", new ActionConfig(AuditAction.LIST_MEMBERS)),
            Map.entry("PUT:/v1/orgs/{orgId}/members/{userId}", new ActionConfig(AuditAction.UPDATE_MEMBER, "userId")),

            // Patient & Clinical (5)
            Map.entry("GET:/v1/orgs/{orgId}/patients", new ActionConfig(AuditAction.LIST_PATIENTS)),
            Map.entry("GET:/v1/orgs/{orgId}/patients/search", new ActionConfig(AuditAction.SEARCH_PATIENTS)),
            Map.entry("GET:/v1/orgs/{orgId}/patients/{patientId}/thread", new ActionConfig(AuditAction.VIEW_PATIENT_THREAD, "patientId")),
            Map.entry("POST:/v1/orgs/{orgId}/patients/{patientId}/visits", new ActionConfig(AuditAction.CREATE_VISIT, "patientId")),
            Map.entry("PUT:/v1/orgs/{orgId}/patients/{patientId}/visits/{visitId}", new ActionConfig(AuditAction.UPDATE_VISIT, "visitId")),

            // Queue (4)
            Map.entry("GET:/v1/orgs/{orgId}/queues/active", new ActionConfig(AuditAction.VIEW_QUEUE)),
            Map.entry("GET:/v1/orgs/{orgId}/patients/lookup", new ActionConfig(AuditAction.LOOKUP_PATIENT)),
            Map.entry("POST:/v1/orgs/{orgId}/queues/{queueId}/end", new ActionConfig(AuditAction.END_QUEUE, "queueId")),
            Map.entry("POST:/v1/orgs/{orgId}/queues/{queueId}/import-stash", new ActionConfig(AuditAction.IMPORT_STASH, "queueId")),

            // Images (7)
            Map.entry("POST:/v1/orgs/{orgId}/images/upload", new ActionConfig(AuditAction.UPLOAD_IMAGE)),
            Map.entry("GET:/v1/orgs/{orgId}/patients/{patientId}/images", new ActionConfig(AuditAction.VIEW_PATIENT_IMAGES, "patientId")),
            Map.entry("GET:/v1/orgs/{orgId}/doctors/{doctorId}/images", new ActionConfig(AuditAction.VIEW_DOCTOR_IMAGES, "doctorId")),
            Map.entry("GET:/v1/orgs/{orgId}/visits/{visitId}/images", new ActionConfig(AuditAction.VIEW_VISIT_IMAGES, "visitId")),
            Map.entry("GET:/v1/orgs/{orgId}/images/{imageId}", new ActionConfig(AuditAction.VIEW_IMAGE_DETAIL, "imageId")),
            Map.entry("GET:/v1/orgs/{orgId}/images/my-uploads", new ActionConfig(AuditAction.LIST_MY_UPLOADS)),
            Map.entry("GET:/v1/orgs/{orgId}/images", new ActionConfig(AuditAction.LIST_ORG_IMAGES)),

            // Sync (2)
            Map.entry("POST:/v1/sync/push", new ActionConfig(AuditAction.SYNC_PUSH)),
            Map.entry("GET:/v1/sync/pull", new ActionConfig(AuditAction.SYNC_PULL)),

            // Billing (4)
            Map.entry("POST:/v1/orgs/{orgId}/bills", new ActionConfig(AuditAction.CREATE_BILL)),
            Map.entry("GET:/v1/orgs/{orgId}/bills/{billId}", new ActionConfig(AuditAction.VIEW_BILL, "billId")),
            Map.entry("GET:/v1/orgs/{orgId}/bills", new ActionConfig(AuditAction.LIST_BILLS)),
            Map.entry("PUT:/v1/orgs/{orgId}/bills/{billId}/mark-paid", new ActionConfig(AuditAction.MARK_BILL_PAID, "billId")),

            // AI (1)
            Map.entry("POST:/v1/orgs/{orgId}/ai/extract", new ActionConfig(AuditAction.AI_EXTRACT)),

            // Other (2)
            Map.entry("GET:/v1/orgs/{orgId}/analytics", new ActionConfig(AuditAction.VIEW_ANALYTICS)),
            Map.entry("POST:/v1/orgs/{orgId}/sms/send", new ActionConfig(AuditAction.SEND_SMS))
    );
}

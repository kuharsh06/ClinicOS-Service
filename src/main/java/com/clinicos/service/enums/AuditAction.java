package com.clinicos.service.enums;

import lombok.Getter;

@Getter
public enum AuditAction {

    // Auth
    OTP_REQUEST("auth"),
    OTP_VERIFY("auth"),
    TOKEN_REFRESH("auth"),
    LOGOUT("auth"),

    // Org & Members
    CREATE_ORG("org"),
    VIEW_ORG("org"),
    UPDATE_ORG("org"),
    ADD_MEMBER("member"),
    LIST_MEMBERS("member"),
    UPDATE_MEMBER("member"),

    // Patient & Clinical
    LIST_PATIENTS("patient"),
    SEARCH_PATIENTS("patient"),
    VIEW_PATIENT_THREAD("patient"),
    CREATE_VISIT("visit"),
    UPDATE_VISIT("visit"),

    // Queue
    VIEW_QUEUE("queue"),
    LOOKUP_PATIENT("patient"),
    END_QUEUE("queue"),
    IMPORT_STASH("queue"),

    // Images
    UPLOAD_IMAGE("image"),
    VIEW_PATIENT_IMAGES("image"),
    VIEW_DOCTOR_IMAGES("image"),
    VIEW_VISIT_IMAGES("image"),
    VIEW_IMAGE_DETAIL("image"),
    LIST_MY_UPLOADS("image"),
    LIST_ORG_IMAGES("image"),

    // Sync
    SYNC_PUSH("sync"),
    SYNC_PULL("sync"),

    // Billing
    CREATE_BILL("billing"),
    VIEW_BILL("billing"),
    LIST_BILLS("billing"),
    MARK_BILL_PAID("billing"),

    // Other
    VIEW_ANALYTICS("analytics"),
    SEND_SMS("sms");

    private final String resourceType;

    AuditAction(String resourceType) {
        this.resourceType = resourceType;
    }
}

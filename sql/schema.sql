-- ============================================================================
-- ClinicOS Database Schema
-- Run this script to create all tables in the 'clinicos' database
-- ============================================================================

-- Create database if not exists
CREATE DATABASE IF NOT EXISTS clinicos;
USE clinicos;

-- ============================================================================
-- 1. USERS & AUTHENTICATION
-- ============================================================================

CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    phone VARCHAR(15) NOT NULL,
    country_code VARCHAR(5) NOT NULL DEFAULT '+91',
    name VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    scheduled_permanent_deletion_at TIMESTAMP NULL,

    UNIQUE INDEX idx_users_uuid (uuid),
    UNIQUE INDEX idx_users_phone (country_code, phone)
);

CREATE TABLE otp_requests (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    phone VARCHAR(15) NOT NULL,
    country_code VARCHAR(5) NOT NULL DEFAULT '+91',
    otp_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    verify_attempts INT NOT NULL DEFAULT 0,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_otp_uuid (uuid),
    INDEX idx_otp_phone_created (country_code, phone, created_at)
);

CREATE TABLE refresh_tokens (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    user_id INT NOT NULL,
    device_id VARCHAR(100) NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_refresh_tokens_uuid (uuid),
    INDEX idx_refresh_tokens_user (user_id),
    INDEX idx_refresh_tokens_lookup (token_hash),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE devices (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    user_id INT NOT NULL,
    device_id VARCHAR(100) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    os_version VARCHAR(20),
    app_version VARCHAR(20),
    device_model VARCHAR(100),
    push_token VARCHAR(255),
    last_active_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_devices_uuid (uuid),
    UNIQUE INDEX idx_devices_device_id (device_id),
    INDEX idx_devices_user_id (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id)
);

-- ============================================================================
-- 2. ROLES & PERMISSIONS
-- ============================================================================

CREATE TABLE roles (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    name VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_roles_uuid (uuid),
    UNIQUE INDEX idx_roles_name (name)
);

CREATE TABLE permissions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    name VARCHAR(50) NOT NULL,
    category VARCHAR(30) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_permissions_uuid (uuid),
    UNIQUE INDEX idx_permissions_name (name)
);

CREATE TABLE role_permissions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    role_id INT NOT NULL,
    permission_id INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_role_permissions_uuid (uuid),
    UNIQUE INDEX idx_role_permission (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES roles(id),
    FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

-- ============================================================================
-- 3. ORGANIZATIONS & MEMBERS
-- ============================================================================

CREATE TABLE organizations (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    logo_url VARCHAR(500),
    brand_color VARCHAR(7) NOT NULL DEFAULT '#059669',
    address TEXT NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    pin VARCHAR(10) NOT NULL,
    settings JSON,
    working_hours JSON,
    created_by INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_organizations_uuid (uuid),
    FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE org_members (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    user_id INT NOT NULL,
    org_id INT NOT NULL,
    profile_data JSON,
    profile_schema_version INT,
    assigned_doctor_id INT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_profile_complete BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMP NULL,
    last_active_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_org_members_uuid (uuid),
    INDEX idx_org_members_org (org_id),
    UNIQUE INDEX idx_org_members_user_org (user_id, org_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (org_id) REFERENCES organizations(id),
    FOREIGN KEY (assigned_doctor_id) REFERENCES org_members(id)
);

CREATE TABLE org_member_roles (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    org_member_id INT NOT NULL,
    role_id INT NOT NULL,
    assigned_by INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_org_member_roles_uuid (uuid),
    UNIQUE INDEX idx_member_role (org_member_id, role_id),
    FOREIGN KEY (org_member_id) REFERENCES org_members(id),
    FOREIGN KEY (role_id) REFERENCES roles(id),
    FOREIGN KEY (assigned_by) REFERENCES users(id)
);

-- ============================================================================
-- 4. PATIENTS
-- ============================================================================

CREATE TABLE patients (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    org_id INT NOT NULL,
    phone VARCHAR(15),
    country_code VARCHAR(5) DEFAULT '+91',
    name VARCHAR(100) NOT NULL,
    age INT,
    gender VARCHAR(10),
    total_visits INT NOT NULL DEFAULT 0,
    last_visit_date DATE,
    last_complaint_tags JSON,
    is_regular BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_patients_uuid (uuid),
    INDEX idx_patients_org (org_id),
    INDEX idx_patients_name (org_id, name),
    UNIQUE INDEX idx_patients_org_phone (org_id, country_code, phone),
    FOREIGN KEY (org_id) REFERENCES organizations(id)
);

CREATE TABLE complaint_tags (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    org_id INT NOT NULL,
    tag_key VARCHAR(50) NOT NULL,
    label_en VARCHAR(100) NOT NULL,
    label_hi VARCHAR(100),
    sort_order INT NOT NULL DEFAULT 0,
    is_common BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_complaint_tags_uuid (uuid),
    INDEX idx_complaint_tags_org (org_id),
    UNIQUE INDEX idx_complaint_tags_org_key (org_id, tag_key),
    FOREIGN KEY (org_id) REFERENCES organizations(id)
);

-- ============================================================================
-- 5. QUEUES & QUEUE ENTRIES
-- ============================================================================

CREATE TABLE queues (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    org_id INT NOT NULL,
    doctor_id INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_token INT NOT NULL DEFAULT 0,
    pause_start_time BIGINT,
    total_paused_ms BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_queues_uuid (uuid),
    INDEX idx_queues_org (org_id),
    INDEX idx_queues_doctor_status (doctor_id, status),
    FOREIGN KEY (org_id) REFERENCES organizations(id),
    FOREIGN KEY (doctor_id) REFERENCES org_members(id)
);

CREATE TABLE queue_entries (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    queue_id INT NOT NULL,
    patient_id INT NOT NULL,
    token_number INT NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    position INT,
    complaint_tags JSON,
    complaint_text VARCHAR(500),
    is_billed BOOLEAN NOT NULL DEFAULT FALSE,
    bill_id INT,
    removal_reason VARCHAR(50),
    stashed_from_queue_id INT,
    step_out_reason VARCHAR(50),
    stepped_out_at BIGINT,
    registered_at BIGINT NOT NULL,
    called_at BIGINT,
    completed_at BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_queue_entries_uuid (uuid),
    INDEX idx_queue_entries_queue (queue_id),
    INDEX idx_queue_entries_patient (patient_id),
    INDEX idx_queue_entries_state (queue_id, state),
    FOREIGN KEY (queue_id) REFERENCES queues(id),
    FOREIGN KEY (patient_id) REFERENCES patients(id),
    FOREIGN KEY (stashed_from_queue_id) REFERENCES queues(id)
);

-- ============================================================================
-- 6. VISITS & VISIT IMAGES
-- ============================================================================

CREATE TABLE visits (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    patient_id INT NOT NULL,
    org_id INT NOT NULL,
    queue_entry_id INT,
    visit_date DATE NOT NULL,
    complaint_tags JSON,
    data JSON,
    schema_version INT NOT NULL DEFAULT 1,
    is_redacted BOOLEAN NOT NULL DEFAULT FALSE,
    created_by INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_visits_uuid (uuid),
    INDEX idx_visits_patient (patient_id),
    INDEX idx_visits_org_date (org_id, visit_date),
    INDEX idx_visits_queue_entry (queue_entry_id),
    FOREIGN KEY (patient_id) REFERENCES patients(id),
    FOREIGN KEY (org_id) REFERENCES organizations(id),
    FOREIGN KEY (queue_entry_id) REFERENCES queue_entries(id),
    FOREIGN KEY (created_by) REFERENCES org_members(id)
);

CREATE TABLE visit_images (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    visit_id INT NULL,
    patient_id INT NULL,
    organization_id INT NOT NULL,
    doctor_uuid VARCHAR(36),
    image_url VARCHAR(500) NOT NULL,
    thumbnail_url VARCHAR(500),
    caption VARCHAR(255),
    sort_order INT NOT NULL DEFAULT 0,
    file_size_bytes INT,
    mime_type VARCHAR(50),
    original_filename VARCHAR(255),
    file_type VARCHAR(20) NOT NULL DEFAULT 'image',
    storage_key VARCHAR(500) NOT NULL,
    tags JSON,
    metadata JSON,
    ai_analysis JSON,
    uploaded_by INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_visit_images_uuid (uuid),
    INDEX idx_visit_images_visit (visit_id),
    INDEX idx_visit_images_patient (patient_id),
    INDEX idx_visit_images_org (organization_id),
    INDEX idx_visit_images_doctor (doctor_uuid),
    INDEX idx_visit_images_org_patient (organization_id, patient_id),
    FOREIGN KEY (visit_id) REFERENCES visits(id),
    FOREIGN KEY (patient_id) REFERENCES patients(id),
    FOREIGN KEY (organization_id) REFERENCES organizations(id),
    FOREIGN KEY (uploaded_by) REFERENCES org_members(id)
);

-- ============================================================================
-- 7. BILLING
-- ============================================================================

CREATE TABLE bill_item_templates (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    org_id INT NOT NULL,
    name VARCHAR(200) NOT NULL,
    default_amount DECIMAL(10,2) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_bill_item_templates_uuid (uuid),
    INDEX idx_bill_item_templates_org (org_id),
    INDEX idx_bill_item_templates_active (org_id, is_active),
    FOREIGN KEY (org_id) REFERENCES organizations(id)
);

CREATE TABLE bills (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    org_id INT NOT NULL,
    patient_id INT NOT NULL,
    queue_entry_id INT,
    total_amount DECIMAL(10,2) NOT NULL,
    is_paid BOOLEAN NOT NULL DEFAULT FALSE,
    paid_at TIMESTAMP NULL,
    patient_name VARCHAR(100) NOT NULL,
    patient_phone VARCHAR(15) NOT NULL,
    token_number INT,
    doctor_name VARCHAR(100),
    sms_sent BOOLEAN NOT NULL DEFAULT FALSE,
    sms_sent_at TIMESTAMP NULL,
    created_by INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_bills_uuid (uuid),
    INDEX idx_bills_org (org_id),
    INDEX idx_bills_patient (patient_id),
    INDEX idx_bills_queue_entry (queue_entry_id),
    INDEX idx_bills_is_paid (org_id, is_paid),
    INDEX idx_bills_created (org_id, created_at),
    FOREIGN KEY (org_id) REFERENCES organizations(id),
    FOREIGN KEY (patient_id) REFERENCES patients(id),
    FOREIGN KEY (created_by) REFERENCES org_members(id)
);

-- Add bill_id FK to queue_entries now that bills table exists
ALTER TABLE queue_entries ADD FOREIGN KEY (bill_id) REFERENCES bills(id);

CREATE TABLE bill_items (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    bill_id INT NOT NULL,
    name VARCHAR(200) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    bill_item_template_id INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_bill_items_uuid (uuid),
    INDEX idx_bill_items_bill (bill_id),
    FOREIGN KEY (bill_id) REFERENCES bills(id),
    FOREIGN KEY (bill_item_template_id) REFERENCES bill_item_templates(id)
);

-- ============================================================================
-- 8. SMS
-- ============================================================================

CREATE TABLE sms_templates (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    org_id INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    template_key VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_sms_templates_uuid (uuid),
    INDEX idx_sms_templates_org_active (org_id, is_active),
    UNIQUE INDEX idx_sms_templates_org_key (org_id, template_key, deleted_at),
    FOREIGN KEY (org_id) REFERENCES organizations(id)
);

CREATE TABLE sms_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    org_id INT NOT NULL,
    template_id INT,
    recipient_phone VARCHAR(15) NOT NULL,
    message_content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    provider VARCHAR(50),
    provider_msg_id VARCHAR(100),
    error_message TEXT,
    sent_at TIMESTAMP NULL,
    delivered_at TIMESTAMP NULL,
    patient_id INT,
    queue_entry_id INT,
    bill_id INT,
    created_by INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_sms_logs_uuid (uuid),
    INDEX idx_sms_logs_org (org_id),
    INDEX idx_sms_logs_status (status),
    INDEX idx_sms_logs_recipient (recipient_phone),
    INDEX idx_sms_logs_created (org_id, created_at),
    INDEX idx_sms_logs_provider_msg (provider_msg_id),
    FOREIGN KEY (org_id) REFERENCES organizations(id),
    FOREIGN KEY (template_id) REFERENCES sms_templates(id),
    FOREIGN KEY (patient_id) REFERENCES patients(id),
    FOREIGN KEY (queue_entry_id) REFERENCES queue_entries(id),
    FOREIGN KEY (bill_id) REFERENCES bills(id),
    FOREIGN KEY (created_by) REFERENCES org_members(id)
);

-- ============================================================================
-- 9. EVENT STORE (Sync Protocol)
-- ============================================================================

CREATE TABLE event_store (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    device_id VARCHAR(100) NOT NULL,
    user_id INT NOT NULL,
    org_id INT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    target_entity_uuid VARCHAR(36) NOT NULL,
    target_table VARCHAR(50) NOT NULL,
    payload JSON NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    device_timestamp BIGINT NOT NULL,
    server_received_at BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_code VARCHAR(50),
    rejection_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_event_store_uuid (uuid),
    INDEX idx_events_org_received (org_id, server_received_at),
    INDEX idx_events_user (user_id),
    FOREIGN KEY (user_id) REFERENCES users(id),
    FOREIGN KEY (org_id) REFERENCES organizations(id)
);

CREATE TABLE event_role_snapshot (
    id INT AUTO_INCREMENT PRIMARY KEY,
    uuid VARCHAR(36) NOT NULL,
    event_id INT NOT NULL,
    role_id INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP NULL,

    UNIQUE INDEX idx_event_role_snapshot_uuid (uuid),
    INDEX idx_ers_event (event_id),
    FOREIGN KEY (event_id) REFERENCES event_store(id),
    FOREIGN KEY (role_id) REFERENCES roles(id)
);

-- ============================================================================
-- 10. SEED DATA - Roles & Permissions
-- ============================================================================

-- Insert default roles
INSERT INTO roles (uuid, name, display_name, description) VALUES
(UUID(), 'owner', 'Owner', 'Full access to organization'),
(UUID(), 'admin', 'Admin', 'Administrative access'),
(UUID(), 'doctor', 'Doctor', 'Doctor role with clinical access'),
(UUID(), 'assistant', 'Assistant', 'Front desk operations'),
(UUID(), 'nurse', 'Nurse', 'Clinical support staff'),
(UUID(), 'billing', 'Billing Staff', 'Billing operations only');

-- Insert default permissions
INSERT INTO permissions (uuid, name, category, display_name, description) VALUES
-- Organization
(UUID(), 'org:view', 'organization', 'View Organization', 'View organization details'),
(UUID(), 'org:update', 'organization', 'Update Organization', 'Update organization settings'),
(UUID(), 'org:delete', 'organization', 'Delete Organization', 'Delete organization'),
-- Members
(UUID(), 'members:view', 'members', 'View Members', 'View organization members'),
(UUID(), 'members:add', 'members', 'Add Members', 'Add new members'),
(UUID(), 'members:update', 'members', 'Update Members', 'Update member details'),
(UUID(), 'members:remove', 'members', 'Remove Members', 'Remove members from organization'),
(UUID(), 'members:assign_roles', 'members', 'Assign Roles', 'Assign roles to members'),
-- Queue
(UUID(), 'queue:view', 'queue', 'View Queue', 'View queue and entries'),
(UUID(), 'queue:manage', 'queue', 'Manage Queue', 'Add/remove patients from queue'),
(UUID(), 'queue:call_next', 'queue', 'Call Next', 'Call next patient'),
-- Patient
(UUID(), 'patient:view', 'patient', 'View Patients', 'View patient list'),
(UUID(), 'patient:view_clinical', 'patient', 'View Clinical Data', 'View clinical/visit data'),
(UUID(), 'patient:create', 'patient', 'Create Patient', 'Create new patients'),
(UUID(), 'patient:update', 'patient', 'Update Patient', 'Update patient details'),
-- Visit
(UUID(), 'visit:create', 'visit', 'Create Visit', 'Create visit records'),
(UUID(), 'visit:update', 'visit', 'Update Visit', 'Update visit records'),
(UUID(), 'visit:view', 'visit', 'View Visits', 'View visit records'),
-- Billing
(UUID(), 'billing:view', 'billing', 'View Bills', 'View billing information'),
(UUID(), 'billing:create', 'billing', 'Create Bills', 'Create new bills'),
(UUID(), 'billing:mark_paid', 'billing', 'Mark Paid', 'Mark bills as paid'),
(UUID(), 'billing:manage_templates', 'billing', 'Manage Templates', 'Manage bill item templates'),
-- Analytics
(UUID(), 'analytics:view', 'analytics', 'View Analytics', 'View analytics dashboard'),
-- SMS
(UUID(), 'sms:view_templates', 'sms', 'View SMS Templates', 'View SMS templates'),
(UUID(), 'sms:manual_send', 'sms', 'Manual SMS Send', 'Send SMS manually');

-- Assign permissions to roles
-- Owner gets all permissions
INSERT INTO role_permissions (uuid, role_id, permission_id)
SELECT UUID(), r.id, p.id FROM roles r, permissions p WHERE r.name = 'owner';

-- Admin gets most permissions except org:delete
INSERT INTO role_permissions (uuid, role_id, permission_id)
SELECT UUID(), r.id, p.id FROM roles r, permissions p
WHERE r.name = 'admin' AND p.name NOT IN ('org:delete');

-- Doctor permissions
INSERT INTO role_permissions (uuid, role_id, permission_id)
SELECT UUID(), r.id, p.id FROM roles r, permissions p
WHERE r.name = 'doctor' AND p.name IN (
    'org:view', 'members:view', 'queue:view', 'queue:manage', 'queue:call_next',
    'patient:view', 'patient:view_clinical', 'patient:create', 'patient:update',
    'visit:create', 'visit:update', 'visit:view', 'billing:view', 'analytics:view'
);

-- Assistant permissions
INSERT INTO role_permissions (uuid, role_id, permission_id)
SELECT UUID(), r.id, p.id FROM roles r, permissions p
WHERE r.name = 'assistant' AND p.name IN (
    'org:view', 'members:view', 'queue:view', 'queue:manage',
    'patient:view', 'patient:create', 'patient:update',
    'billing:view', 'billing:create', 'billing:mark_paid', 'sms:view_templates', 'sms:manual_send'
);

-- Nurse permissions
INSERT INTO role_permissions (uuid, role_id, permission_id)
SELECT UUID(), r.id, p.id FROM roles r, permissions p
WHERE r.name = 'nurse' AND p.name IN (
    'org:view', 'members:view', 'queue:view', 'queue:manage',
    'patient:view', 'patient:view_clinical', 'patient:create', 'patient:update',
    'visit:view'
);

-- Billing staff permissions
INSERT INTO role_permissions (uuid, role_id, permission_id)
SELECT UUID(), r.id, p.id FROM roles r, permissions p
WHERE r.name = 'billing' AND p.name IN (
    'org:view', 'patient:view', 'billing:view', 'billing:create',
    'billing:mark_paid', 'billing:manage_templates', 'analytics:view'
);

-- ============================================================================
-- 11. AUDIT LOGS (DPDP Rule 6 Compliance)
-- ============================================================================

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(36),
    org_id VARCHAR(36),
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    resource_id VARCHAR(36),
    status VARCHAR(15) NOT NULL,
    denied_reason VARCHAR(200),
    endpoint VARCHAR(200),
    ip_address VARCHAR(45),
    device_id VARCHAR(100),
    user_agent VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_audit_user (user_id, created_at),
    INDEX idx_audit_org (org_id, created_at),
    INDEX idx_audit_action (action, created_at),
    INDEX idx_audit_status (status, created_at),
    INDEX idx_audit_created (created_at)
);

-- ============================================================================
-- 12. TEST PHONES (OTP bypass for testing on production)
-- ============================================================================

CREATE TABLE test_phones (
    phone VARCHAR(15) NOT NULL PRIMARY KEY,
    country_code VARCHAR(5) NOT NULL DEFAULT '+91'
);

-- ============================================================================
-- Done!
-- ============================================================================
SELECT 'Schema created successfully!' AS status;

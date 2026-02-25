package com.clinicos.service.controller;

import com.clinicos.service.dto.request.CreateBillRequest;
import com.clinicos.service.dto.request.CreateBillTemplateRequest;
import com.clinicos.service.dto.request.UpdateBillTemplateRequest;
import com.clinicos.service.dto.response.ApiResponse;
import com.clinicos.service.dto.response.BillListResponse;
import com.clinicos.service.dto.response.BillResponse;
import com.clinicos.service.dto.response.BillTemplatesResponse;
import com.clinicos.service.security.CustomUserDetails;
import com.clinicos.service.security.RequirePermission;
import com.clinicos.service.service.BillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/orgs/{orgId}")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    /**
     * Create a new bill.
     * POST /v1/orgs/:orgId/bills
     */
    @PostMapping("/bills")
    @RequirePermission("billing:create")
    public ResponseEntity<ApiResponse<BillResponse>> createBill(
            @PathVariable String orgId,
            @RequestBody @Valid CreateBillRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        BillResponse bill = billingService.createBill(orgId, request, userDetails.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(bill));
    }

    /**
     * Get a bill by ID.
     * GET /v1/orgs/:orgId/bills/:billId
     */
    @GetMapping("/bills/{billId}")
    @RequirePermission("billing:view")
    public ResponseEntity<ApiResponse<BillResponse>> getBill(
            @PathVariable String orgId,
            @PathVariable String billId) {

        BillResponse bill = billingService.getBill(orgId, billId);
        return ResponseEntity.ok(ApiResponse.success(bill));
    }

    /**
     * Mark a bill as paid.
     * PUT /v1/orgs/:orgId/bills/:billId/mark-paid
     */
    @PutMapping("/bills/{billId}/mark-paid")
    @RequirePermission("billing:mark_paid")
    public ResponseEntity<ApiResponse<BillResponse>> markBillPaid(
            @PathVariable String orgId,
            @PathVariable String billId) {

        BillResponse bill = billingService.markBillPaid(orgId, billId);
        return ResponseEntity.ok(ApiResponse.success(bill));
    }

    /**
     * List bills with optional filtering.
     * GET /v1/orgs/:orgId/bills?date=<ISO>&status=<paid|unpaid|all>&after=<cursor>&limit=20
     */
    @GetMapping("/bills")
    @RequirePermission("billing:view")
    public ResponseEntity<ApiResponse<BillListResponse>> listBills(
            @PathVariable String orgId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) Integer limit) {

        BillListResponse response = billingService.listBills(orgId, date, status, after, limit);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get bill templates.
     * GET /v1/orgs/:orgId/bill-templates
     */
    @GetMapping("/bill-templates")
    @RequirePermission("billing:view")
    public ResponseEntity<ApiResponse<BillTemplatesResponse>> getBillTemplates(
            @PathVariable String orgId) {

        BillTemplatesResponse response = billingService.getBillTemplates(orgId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Create a bill template.
     * POST /v1/orgs/:orgId/bill-templates
     */
    @PostMapping("/bill-templates")
    @RequirePermission("billing:create")
    public ResponseEntity<ApiResponse<BillTemplatesResponse.BillTemplateDto>> createBillTemplate(
            @PathVariable String orgId,
            @RequestBody @Valid CreateBillTemplateRequest request) {

        BillTemplatesResponse.BillTemplateDto template = billingService.createBillTemplate(orgId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(template));
    }

    /**
     * Update a bill template.
     * PUT /v1/orgs/:orgId/bill-templates/:templateId
     */
    @PutMapping("/bill-templates/{templateId}")
    @RequirePermission("billing:manage_templates")
    public ResponseEntity<ApiResponse<BillTemplatesResponse.BillTemplateDto>> updateBillTemplate(
            @PathVariable String orgId,
            @PathVariable String templateId,
            @RequestBody @Valid UpdateBillTemplateRequest request) {

        BillTemplatesResponse.BillTemplateDto template = billingService.updateBillTemplate(orgId, templateId, request);
        return ResponseEntity.ok(ApiResponse.success(template));
    }

    /**
     * Deactivate a bill template.
     * DELETE /v1/orgs/:orgId/bill-templates/:templateId
     */
    @DeleteMapping("/bill-templates/{templateId}")
    @RequirePermission("billing:manage_templates")
    public ResponseEntity<ApiResponse<java.util.Map<String, String>>> deleteBillTemplate(
            @PathVariable String orgId,
            @PathVariable String templateId) {

        billingService.deleteBillTemplate(orgId, templateId);
        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of("message", "Template deactivated")));
    }
}

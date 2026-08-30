package com.sclinic.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Integrity check on the audit trail.
 *
 * <p>Admin only, and a POST rather than a GET: it walks the whole trail, and the
 * run is itself recorded, so it is not the idempotent read a GET implies.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private static final String ENTITY_TYPE = "audit_log";

    private final AuditChainVerifier verifier;
    private final AuditService auditService;

    @PostMapping("/verify-chain")
    @PreAuthorize("hasRole('ADMIN')")
    public AuditChainVerifier.ChainVerification verifyChain() {
        AuditChainVerifier.ChainVerification result = verifier.verify();

        // Recorded after the walk, so the entry describing the check is never
        // part of what the check examined.
        auditService.record(AuditAction.VERIFY_CHAIN, ENTITY_TYPE, null,
                AuditDetail.builder()
                        .code("result", result.intact() ? "INTACT" : "BROKEN")
                        .code("headHash", result.headHash())
                        .build());

        return result;
    }
}

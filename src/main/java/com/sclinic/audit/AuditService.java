package com.sclinic.audit;

import com.sclinic.staff.Staff;
import com.sclinic.staff.StaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Records audit trail entries for actions on sensitive records.
 *
 * <p>Runs in a REQUIRES_NEW transaction so the audit entry is committed
 * independently — it works even when the caller runs read-only (e.g. a VIEW),
 * and a failed business operation does not silently drop the access record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final StaffRepository staffRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String entityType, UUID entityId, Map<String, Object> detail) {
        AuditLog entry = new AuditLog();
        entry.setStaffId(currentStaffId());
        entry.setAction(action.name());
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setDetail(detail);
        auditLogRepository.save(entry);
    }

    public void record(AuditAction action, String entityType, UUID entityId) {
        record(action, entityType, entityId, null);
    }

    /** Resolve the current authenticated staff id, or null if unauthenticated. */
    private UUID currentStaffId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return staffRepository.findByUsernameAndActiveTrue(auth.getName())
                .map(Staff::getId)
                .orElse(null);
    }
}

package com.sclinic.access;

import com.sclinic.appointment.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Decides who may open a patient record, and how much of it they see.
 *
 * <p>The rule per role:
 *
 * <ul>
 *   <li><b>DOCTOR</b> — only patients they treat, which means a patient they have
 *       an appointment with. Anyone else needs a break-glass grant.</li>
 *   <li><b>RECEPTIONIST</b> — every patient, but the administrative view only.
 *       Booking an appointment needs a name and a phone number; it does not need
 *       to know what the patient is being treated for.</li>
 *   <li><b>ADMIN</b> — every patient, for administration, and every one of those
 *       reads is recorded. Administrative access to clinical data is the access
 *       least likely to be clinically necessary, so it is the access most worth
 *       being able to review.</li>
 * </ul>
 *
 * <p>A treating relationship is derived from appointments rather than kept in a
 * table of its own. It is the same fact recorded twice otherwise, and the copy
 * would drift: a doctor reassigned away from a patient would keep access until
 * something remembered to delete the relationship row.
 */
@Component
@RequiredArgsConstructor
public class PatientAccessGuard {

    private static final String DOCTOR = "DOCTOR";
    private static final String RECEPTIONIST = "RECEPTIONIST";
    private static final String ADMIN = "ADMIN";

    private final AppointmentRepository appointmentRepository;
    private final BreakGlassGrantRepository breakGlassRepository;
    private final Clock clock;

    /** How much of the record a role is entitled to see. */
    public enum View {
        /** Everything, including clinical fields. */
        FULL,
        /** Name, contact and billing identifiers; no clinical data. */
        ADMINISTRATIVE
    }

    /**
     * @throws PatientAccessDeniedException if a doctor has neither a treating
     *                                      relationship nor a live grant
     */
    @Transactional(readOnly = true)
    public void requireAccess(UUID patientId, UUID staffId, String role) {
        if (!DOCTOR.equals(role)) {
            // Receptionists and admins reach every patient; what differs is how
            // much they see, which is viewFor's job.
            return;
        }

        if (hasTreatingRelationship(patientId, staffId)) {
            return;
        }
        if (breakGlassRepository.findActive(staffId, patientId, clock.instant()).isPresent()) {
            return;
        }
        throw new PatientAccessDeniedException(patientId);
    }

    /** Whether this staff member treats this patient. */
    @Transactional(readOnly = true)
    public boolean hasTreatingRelationship(UUID patientId, UUID staffId) {
        return appointmentRepository.existsByPatientIdAndDoctorId(patientId, staffId);
    }

    /**
     * Clinical data is withheld from receptionists. Requirement 8.6: they need
     * administrative access to every patient, and clinical access to none.
     */
    public View viewFor(String role) {
        return RECEPTIONIST.equals(role) ? View.ADMINISTRATIVE : View.FULL;
    }

    /**
     * Whether a read by this role should be recorded even when it is routine.
     *
     * <p>Doctors reading their own patients is the system working; recording every
     * such read would bury the entries that matter. An admin reading a patient
     * record is different: it is rarely clinically necessary, so requirement 8.7
     * singles it out.
     */
    public boolean alwaysAudit(String role) {
        return ADMIN.equals(role);
    }

    public boolean isDoctor(String role) {
        return DOCTOR.equals(role);
    }
}

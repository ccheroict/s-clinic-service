package com.sclinic.access;

import lombok.Getter;

import java.util.UUID;

/**
 * A clinician asked for a patient they have no treating relationship with.
 *
 * <p>Carries the patient id and says plainly that break-glass is the way through,
 * because a bare 403 at the moment someone needs a record leads to the worst
 * available workaround: borrowing a colleague's login. Telling them the door has
 * a key, and that using it leaves their name on it, is what keeps the access
 * attributable.
 */
@Getter
public class PatientAccessDeniedException extends RuntimeException {

    private final UUID patientId;

    public PatientAccessDeniedException(UUID patientId) {
        super("You have no treating relationship with this patient. To open the record anyway, "
                + "request temporary access via POST /api/patients/" + patientId
                + "/break-glass with a reason. The request is recorded and administrators are notified.");
        this.patientId = patientId;
    }
}

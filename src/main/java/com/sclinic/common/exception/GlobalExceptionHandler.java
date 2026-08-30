package com.sclinic.common.exception;

import com.sclinic.appointment.exception.BusinessHoursException;
import com.sclinic.appointment.exception.ConflictException;
import com.sclinic.appointment.exception.InvalidStatusTransitionException;
import com.sclinic.appointment.exception.NotEditableException;
import com.sclinic.security.AuthenticationFailedException;
import com.sclinic.security.mfa.MfaAlreadyEnrolledException;
import com.sclinic.security.mfa.MfaFailedException;
import com.sclinic.security.password.WeakPasswordException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Rejected authentication. The message is uniform by design so callers cannot
     * distinguish an unknown account from a wrong password from a locked account.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ProblemDetail handleAuthenticationFailed(AuthenticationFailedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /** A proposed password failed the policy; the rule is named, never the password. */
    @ExceptionHandler(WeakPasswordException.class)
    public ProblemDetail handleWeakPassword(WeakPasswordException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** A second-factor challenge was refused. */
    @ExceptionHandler(MfaFailedException.class)
    public ProblemDetail handleMfaFailed(MfaFailedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
     * Enrolment attempted on an account that already has a second factor. A
     * conflict rather than a rejection: the credentials were fine, the account is
     * simply not in a state where enrolling is allowed.
     */
    @ExceptionHandler(MfaAlreadyEnrolledException.class)
    public ProblemDetail handleMfaAlreadyEnrolled(MfaAlreadyEnrolledException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * Service-layer argument rejections (e.g. staff without the DOCTOR role,
     * duplicate facility code). These are client mistakes, not server faults,
     * so they must not surface as 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex) {
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("id", ex.getConflictingAppointmentId());
        conflict.put("patientName", ex.getConflictingPatientName());
        conflict.put("scheduledAt", ex.getConflictingScheduledAt());
        conflict.put("durationMin", ex.getConflictingDurationMin());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        body.put("conflict", conflict);

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(BusinessHoursException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessHours(BusinessHoursException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        body.put("code", ex.getCode());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidStatusTransition(InvalidStatusTransitionException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        body.put("currentStatus", ex.getCurrentStatus().name());
        body.put("allowedTransitions", ex.getAllowedTransitions().stream()
                .map(Enum::name)
                .collect(Collectors.toList()));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(NotEditableException.class)
    public ResponseEntity<Map<String, Object>> handleNotEditable(NotEditableException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        body.put("currentStatus", ex.getCurrentStatus().name());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}

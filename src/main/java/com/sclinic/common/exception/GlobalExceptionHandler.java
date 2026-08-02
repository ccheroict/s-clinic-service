package com.sclinic.common.exception;

import com.sclinic.appointment.exception.BusinessHoursException;
import com.sclinic.appointment.exception.ConflictException;
import com.sclinic.appointment.exception.InvalidStatusTransitionException;
import com.sclinic.appointment.exception.NotEditableException;
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

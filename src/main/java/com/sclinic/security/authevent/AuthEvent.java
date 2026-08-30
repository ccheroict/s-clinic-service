package com.sclinic.security.authevent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * An authentication attempt or session lifecycle event.
 *
 * <p>Kept separate from {@code audit_log}, which records changes to business
 * records. A failed login has no business record and often no resolvable staff
 * row, but is exactly what a security investigation needs.
 */
@Entity
@Table(name = "auth_event")
@Getter
@Setter
public class AuthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** As supplied by the client; may not match any staff row. */
    @Column(nullable = false)
    private String username;

    @Column(name = "staff_id")
    private UUID staffId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private boolean succeeded;

    private String ip;

    @Column(name = "user_agent")
    private String userAgent;

    /**
     * Free-text context. Must never contain a password, a raw token, or patient
     * data.
     */
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}

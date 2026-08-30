package com.sclinic.security.authevent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes authentication events.
 *
 * <p>Most entry points run in their own transaction for the same reason
 * {@code AuditService} does: a rejected login rolls back its caller, and the
 * record of that rejection must survive anyway.
 *
 * <p>Every public method declares its own propagation and delegates to a private
 * helper rather than to another public method. That is deliberate: a call from one
 * public method to another would not pass through the Spring proxy, so the
 * propagation declared on the target would be silently ignored — the bug this
 * class shipped with, where every failed-login record was rolled back along with
 * the login that produced it.
 */
@Service
@RequiredArgsConstructor
public class AuthEventLogger {

    /**
     * A username is bounded to something a real one could plausibly be. The value
     * arrives from an unauthenticated endpoint, so without a cap anyone can write
     * arbitrarily large rows into this table, and a long value is far more likely
     * to be a mistake or an attack than a login attempt worth recording in full.
     */
    private static final int MAX_USERNAME_LENGTH = 100;

    private final AuthEventRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthEventType type, String username, UUID staffId,
                       boolean succeeded, RequestContext context, String detail) {
        write(type, username, staffId, succeeded, context, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(AuthEventType type, String username, UUID staffId, RequestContext context) {
        write(type, username, staffId, true, context, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(AuthEventType type, String username, UUID staffId,
                        RequestContext context, String detail) {
        write(type, username, staffId, false, context, detail);
    }

    /**
     * Records a failure inside the caller's transaction, for callers that already
     * commit independently of the request.
     *
     * <p>{@code MANDATORY} rather than {@code REQUIRED}: this method is only
     * correct when the caller's transaction is one that will commit even though
     * the request fails. Demanding an existing transaction makes a caller that has
     * not thought about that fail loudly instead of quietly losing the record.
     *
     * <p>Exists to keep {@link com.sclinic.security.LoginAttemptTracker} from
     * opening a third nested transaction, and so a third database connection, for
     * every failed login.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void failureInCurrentTransaction(AuthEventType type, String username, UUID staffId,
                                            RequestContext context, String detail) {
        write(type, username, staffId, false, context, detail);
    }

    private void write(AuthEventType type, String username, UUID staffId,
                       boolean succeeded, RequestContext context, String detail) {
        AuthEvent event = new AuthEvent();
        event.setEventType(type.name());
        event.setUsername(boundedUsername(username));
        event.setStaffId(staffId);
        event.setSucceeded(succeeded);
        event.setIp(context == null ? null : context.ip());
        event.setUserAgent(context == null ? null : context.userAgent());
        event.setDetail(detail);
        repository.save(event);
    }

    /**
     * Keeps the stored username bounded.
     *
     * <p>A long value here is usually one of two things: an attempt to bloat the
     * table, or a password typed into the username box, which happens often enough
     * with autofill that this table would otherwise end up holding cleartext
     * passwords — in exactly the table a security review reads.
     */
    private String boundedUsername(String username) {
        if (username == null) {
            return "";
        }
        return username.length() > MAX_USERNAME_LENGTH
                ? username.substring(0, MAX_USERNAME_LENGTH)
                : username;
    }
}

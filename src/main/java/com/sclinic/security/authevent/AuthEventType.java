package com.sclinic.security.authevent;

/**
 * Authentication events worth recording. Failures are recorded as deliberately
 * as successes: a burst of {@link #LOGIN_FAILED} is the signal that matters.
 */
public enum AuthEventType {

    LOGIN_SUCCESS,
    LOGIN_FAILED,
    /** Credentials were not even checked because the account was locked. */
    LOGIN_BLOCKED,
    /** Login attempted against a disabled or unknown account. */
    LOGIN_UNKNOWN_ACCOUNT,
    ACCOUNT_LOCKED,
    LOGOUT,
    PASSWORD_CHANGED,
    PASSWORD_CHANGE_REJECTED,
    SESSIONS_REVOKED,
    TOKEN_REJECTED,

    // Second factor (TOTP).
    MFA_ENROLLED,
    MFA_SUCCESS,
    MFA_FAILED,
    MFA_RESET,
    /**
     * Recovery codes were replaced at the account holder's request.
     *
     * <p>Its own event rather than part of {@link #MFA_ENROLLED}: it hands out a
     * fresh set of ways past the second factor without the second factor
     * changing, and a review asking "when did this account last gain a bypass"
     * should not have to infer it from an enrolment that did not happen.
     */
    MFA_BACKUP_CODES_REISSUED
}

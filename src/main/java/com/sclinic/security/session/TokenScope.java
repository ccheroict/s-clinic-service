package com.sclinic.security.session;

/**
 * What a session token is allowed to do.
 *
 * <p>Only {@link #FULL} reaches business endpoints. The other values represent
 * a half-finished login: the holder proved their password but must still clear
 * one more gate, and only the auth endpoints accept them.
 */
public enum TokenScope {

    /** Fully authenticated. */
    FULL,

    /** Password verified, but the account must set a new password first. */
    CHANGE_PASSWORD,

    /** Password verified, waiting for a TOTP code. */
    MFA_PENDING,

    /** Password verified, but the account has no TOTP device registered yet. */
    ENROLL_MFA
}

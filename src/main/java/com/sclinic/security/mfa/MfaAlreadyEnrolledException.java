package com.sclinic.security.mfa;

/**
 * Enrolment was attempted for an account that already has a working second factor.
 *
 * <p>Refused rather than silently replaced. Letting a session replace the second
 * factor it just satisfied would make the factor worth nothing against a stolen
 * session, and it is also how a legitimate user loses a working authenticator by
 * opening a setup screen and closing it again. Replacing a lost device goes
 * through an administrator.
 */
public class MfaAlreadyEnrolledException extends RuntimeException {

    public MfaAlreadyEnrolledException() {
        super("Two-factor authentication is already set up for this account. "
                + "Ask an administrator to reset it before enrolling a new device.");
    }
}

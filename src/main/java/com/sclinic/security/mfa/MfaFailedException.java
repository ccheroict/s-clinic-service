package com.sclinic.security.mfa;

/**
 * A second-factor challenge was refused.
 *
 * <p>Like a failed password, this counts toward the account lockout so codes
 * cannot be brute forced.
 */
public class MfaFailedException extends RuntimeException {

    public MfaFailedException(String message) {
        super(message);
    }
}

package com.sclinic.security.password;

/**
 * A proposed password does not satisfy the policy.
 *
 * <p>The message describes the rule that failed, never the password.
 */
public class WeakPasswordException extends RuntimeException {

    public WeakPasswordException(String message) {
        super(message);
    }
}

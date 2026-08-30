package com.sclinic.security;

/**
 * Authentication was refused.
 *
 * <p>The message is deliberately generic. Distinguishing "no such account" from
 * "wrong password" from "account locked" would let an attacker enumerate staff
 * accounts and learn when a lockout is in effect.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Invalid username or password");
    }

    public AuthenticationFailedException(String message) {
        super(message);
    }
}

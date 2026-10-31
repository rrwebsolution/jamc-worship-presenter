package com.jamc.worship.service;

/** A user-correctable problem; its message is shown to the operator as-is. */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}

package com.jamc.worship.database;

/** Unchecked wrapper for SQL failures so callers above the repository layer never see JDBC types. */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}

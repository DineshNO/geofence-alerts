package com.example.geoalert.common.jdbc;

import java.sql.SQLException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Tells a transient database failure (worth a few retries) from one that will fail the same way
 * every time (a schema or data problem). The decision follows the SQLSTATE class:
 *
 * <ul>
 *   <li>retry: deadlock 40P01, serialization failure 40001, connection loss (class 08), the
 *       server shutting down or not accepting connections (57P01, 57P03, 53300);
 *   <li>never retry: schema and syntax errors (class 42), integrity errors (class 23), data
 *       errors (class 22) and anything else.
 * </ul>
 */
public final class DatabaseErrors {

    private DatabaseErrors() {}

    public static boolean isTransient(Throwable error) {
        if (error instanceof CannotCreateTransactionException
                || error instanceof TransientDataAccessException) {
            return true;
        }
        String state = sqlState(error);
        if (state == null) {
            return error.getCause() != null && isTransient(error.getCause());
        }
        return state.startsWith("08")
                || state.equals("40P01")
                || state.equals("40001")
                || state.equals("57P01")
                || state.equals("57P03")
                || state.equals("53300");
    }

    /** The SQLSTATE of the first SQL error in the cause chain, or null. */
    public static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}

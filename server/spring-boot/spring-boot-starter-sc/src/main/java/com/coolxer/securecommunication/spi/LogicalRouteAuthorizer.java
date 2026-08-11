package com.coolxer.securecommunication.spi;

/** Restricts the logical HTTP operations that an encrypted tunnel may invoke. */
@FunctionalInterface
public interface LogicalRouteAuthorizer {
    boolean isAllowed(String method, String normalizedPath);
}

package com.example.geoalert.common.web;

import org.springframework.http.HttpStatus;

/**
 * An expected, client-facing failure with a code from the contract's problem catalogue
 * (for example ALERT_ALREADY_CLAIMED). The handler turns it into an RFC 9457 response.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

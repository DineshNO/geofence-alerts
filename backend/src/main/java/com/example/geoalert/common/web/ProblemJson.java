package com.example.geoalert.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Writes an RFC 9457 response by hand, for places that run before Spring MVC's exception
 * handling (the security filters).
 */
public final class ProblemJson {

    private ProblemJson() {}

    public static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            ObjectMapper mapper,
            HttpStatus status,
            String code,
            String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}

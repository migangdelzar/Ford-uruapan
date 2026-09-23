package com.ford.service.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Set;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        var supportedMethods = exception.getSupportedHttpMethods() == null
                ? Set.<HttpMethod>of()
                : exception.getSupportedHttpMethods();
        var supportedNames = supportedMethods.stream().map(HttpMethod::name).sorted().toList();
        var method = request.getMethod();
        var path = request.getRequestURI();
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED,
                "Request method '%s' is not supported for '%s'. Supported methods: %s"
                        .formatted(method, path, supportedNames.isEmpty() ? "none" : String.join(", ", supportedNames)));
        detail.setTitle("HTTP method not supported");
        detail.setProperty("path", path);
        detail.setProperty("method", method);
        detail.setProperty("supportedMethods", supportedNames);

        var traceId = MDC.get("traceId");
        log.warn("HTTP method not supported: method={} path={} supportedMethods={} traceId={}",
                method, path, supportedNames, traceId == null ? "-" : traceId);

        var headers = new HttpHeaders();
        headers.setAllow(supportedMethods);
        return new ResponseEntity<>(detail, headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NoSuchElementException exception,
                                                          HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", "resource_not_found", exception, request);
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(SecurityException exception,
                                                          HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "forbidden", exception, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException exception,
                                                            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "invalid_request", exception, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> handleConflict(IllegalStateException exception,
                                                         HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "State conflict", "state_conflict", exception, request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String code,
                                                    RuntimeException exception, HttpServletRequest request) {
        var detail = ProblemDetail.forStatusAndDetail(status,
                exception.getMessage() == null || exception.getMessage().isBlank()
                        ? title : exception.getMessage());
        detail.setTitle(title);
        detail.setProperty("code", code);
        detail.setProperty("path", request.getRequestURI());
        var traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            detail.setProperty("traceId", traceId);
        }
        var correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank()) {
            detail.setProperty("correlationId", correlationId);
        }
        return ResponseEntity.status(status).body(detail);
    }
}

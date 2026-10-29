package com.ford.service.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

public class HttpExchangeLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpExchangeLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var startedAt = System.nanoTime();
        var requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank() || requestId.length() > 128 || containsControlCharacter(requestId)) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);

        log.info("HTTP request started: requestId={} method={} path={} queryPresent={} contentType={}",
                requestId, request.getMethod(), request.getRequestURI(),
                request.getQueryString() != null, safeContentType(request.getContentType()));

        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            var durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            var status = response.getStatus();
            var contentType = safeContentType(response.getContentType());
            if (failure != null || status >= 500) {
                log.error("HTTP request failed: requestId={} method={} path={} status={} durationMs={} responseContentType={}",
                        requestId, request.getMethod(), request.getRequestURI(), status, durationMs, contentType, failure);
            } else if (status >= 400) {
                log.warn("HTTP request completed with client error: requestId={} method={} path={} status={} durationMs={} responseContentType={}",
                        requestId, request.getMethod(), request.getRequestURI(), status, durationMs, contentType);
            } else {
                log.info("HTTP request completed: requestId={} method={} path={} status={} durationMs={} responseContentType={}",
                        requestId, request.getMethod(), request.getRequestURI(), status, durationMs, contentType);
            }
        }
    }

    private static String safeContentType(String contentType) {
        return contentType == null ? "-" : contentType;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}

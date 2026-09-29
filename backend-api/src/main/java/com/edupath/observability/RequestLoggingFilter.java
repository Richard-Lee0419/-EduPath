package com.edupath.observability;

import com.edupath.auth.AuthContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(20)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final AuditLogRepository auditLogRepository;

    public RequestLoggingFilter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute("trace_id", traceId);
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            String actor = AuthContext.currentUsername();
            log.info(
                    "api_request trace_id={} actor={} method={} path={} status={} duration_ms={}",
                    traceId,
                    actor,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    duration);
            try {
                auditLogRepository.record(
                        "api_request",
                        traceId,
                        actor,
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        duration,
                        null);
            } catch (Exception exception) {
                log.debug("audit log write skipped: {}", exception.getMessage());
            }
        }
    }
}

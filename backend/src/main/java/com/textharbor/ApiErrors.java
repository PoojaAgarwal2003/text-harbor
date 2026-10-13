package com.textharbor;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        return error(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File exceeds the upload limit.");
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<Map<String, String>> invalid() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Provide a file and valid extraction options.");
    }

    @ExceptionHandler(IOException.class)
    ResponseEntity<Map<String, String>> storage(IOException e) {
        LOG.error("Storage operation failed: {}", e.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "Local storage is unavailable. Please retry.");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    @org.springframework.context.annotation.Bean
    OncePerRequestFilter responseHeaders() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                    HttpServletResponse response, jakarta.servlet.FilterChain chain)
                    throws jakarta.servlet.ServletException, IOException {
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Referrer-Policy", "no-referrer");
                chain.doFilter(request, response);
            }
        };
    }
}

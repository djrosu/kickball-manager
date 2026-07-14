package com.singleskickball.manager.controller;

import com.singleskickball.manager.dto.ManagerDashboardState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts manager API exceptions into small JSON error responses.
 *
 * Without this handler, Spring returns an HTML error page. The browser would
 * then be unable to show the useful application message beside the manager
 * controls. This advice applies only to ManagerApiController.
 */
@RestControllerAdvice(assignableTypes = ManagerApiController.class)
public class ManagerApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ManagerDashboardState> handleAccessDenied(AccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ManagerDashboardState> handleBadRequest(RuntimeException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ManagerDashboardState> handleUnexpected(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "The server could not complete that manager action.");
    }

    private ResponseEntity<ManagerDashboardState> error(HttpStatus status, String message) {
        ManagerDashboardState response = new ManagerDashboardState();
        response.setSuccess(false);
        response.setMessage(message == null || message.isBlank()
                ? "The manager action could not be completed."
                : message);
        return ResponseEntity.status(status).body(response);
    }
}

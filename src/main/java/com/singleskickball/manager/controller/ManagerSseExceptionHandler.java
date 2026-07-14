package com.singleskickball.manager.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * Suppresses expected client-disconnect exceptions from the manager SSE stream.
 *
 * <p>Refreshing a page, closing a tab, putting a phone to sleep, changing Wi-Fi,
 * or an EventSource reconnect can all abort the underlying HTTP connection.
 * Spring/Tomcat may redispatch that asynchronous write failure after the service
 * has already removed the dead emitter. These are normal connection lifecycle
 * events and should not appear as application ERROR stack traces.</p>
 *
 * <p>The advice is intentionally scoped only to {@link ManagerLiveUpdateController}
 * so genuine I/O errors from unrelated controllers are not hidden.</p>
 */
@RestControllerAdvice(assignableTypes = ManagerLiveUpdateController.class)
public class ManagerSseExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ManagerSseExceptionHandler.class);

    /**
     * The response is already committed for an SSE stream, so there is no body
     * to return. Handling the exception prevents it from becoming an unhandled
     * DispatcherServlet error.
     */
    @ExceptionHandler({
            IOException.class,
            AsyncRequestNotUsableException.class
    })
    public void handleDisconnectedSseClient(Exception error) {
        LOGGER.debug("Manager SSE client disconnected: {}", error.getMessage());
    }
}

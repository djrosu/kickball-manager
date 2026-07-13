package com.singleskickball.manager.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * Quietly handles normal browser disconnects from the manager SSE endpoint.
 *
 * <p>An EventSource connection can disappear when a manager refreshes, closes a
 * tab, locks a phone, changes networks, or reconnects. Tomcat can redispatch the
 * resulting failed write after the response has already been committed. Those
 * disconnects are expected and should not appear as application ERROR traces.</p>
 *
 * <p>This filter is intentionally narrow:</p>
 * <ul>
 *   <li>It runs only for {@code /manager/api/live}.</li>
 *   <li>It participates in async and error redispatches.</li>
 *   <li>It suppresses only recognized connection-abort exceptions/messages.</li>
 *   <li>All unrelated exceptions continue through normal error handling.</li>
 * </ul>
 */
@Component
public class ManagerSseClientDisconnectFilter extends OncePerRequestFilter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ManagerSseClientDisconnectFilter.class);

    private static final String LIVE_ENDPOINT = "/manager/api/live";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(LIVE_ENDPOINT);
    }

    /**
     * OncePerRequestFilter skips async redispatches by default. SSE write
     * failures often surface during exactly that redispatch, so we opt in.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * Tomcat may also redispatch the failed async request as an ERROR dispatch.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException error) {
            if (isExpectedClientDisconnect(error)) {
                LOGGER.debug(
                        "Manager SSE client disconnected during {} dispatch: {}",
                        request.getDispatcherType(),
                        rootMessage(error));
                return;
            }

            throw error;
        }
    }

    /**
     * Recognizes the common Windows, Linux, Tomcat, and proxy messages produced
     * when the remote browser has already closed an SSE socket.
     */
    private boolean isExpectedClientDisconnect(Throwable error) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof ClientAbortException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);

                if (normalized.contains("connection was aborted")
                        || normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("clientabortexception")
                        || normalized.contains("async request is not usable")
                        || normalized.contains("response has already been committed")) {
                    return true;
                }
            }

            current = current.getCause();
        }

        return false;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error.getMessage();

        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }

        return message == null ? "client connection closed" : message;
    }
}

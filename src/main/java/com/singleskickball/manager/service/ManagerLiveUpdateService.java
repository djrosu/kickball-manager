package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.ManagerDashboardState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintains Server-Sent Event subscriptions for the live manager dashboards.
 *
 * <p>Manager commands still arrive through the authenticated JSON API. This
 * service handles the opposite direction: broadcasting authoritative game
 * state from the server to every browser currently watching the same game.</p>
 *
 * <p>A small heartbeat event is sent periodically. This keeps an otherwise
 * idle SSE connection active through Railway's reverse proxy and through
 * mobile networks that may discard quiet HTTP connections. Dead browser
 * connections are treated as normal lifecycle events and are removed without
 * allowing the scheduler to fail.</p>
 */
@Service
public class ManagerLiveUpdateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagerLiveUpdateService.class);

    /**
     * Spring itself does not expire the stream. Railway, the browser, or the
     * mobile network may still close it; EventSource reconnects automatically.
     */
    private static final long EMITTER_TIMEOUT_MILLIS = 0L;

    /**
     * Frequent enough to keep common proxies active without creating meaningful
     * traffic. Each event is only a few bytes.
     */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 25L;

    /** One subscriber list per game prevents cross-game broadcasts. */
    private final Map<Long, CopyOnWriteArrayList<Subscriber>> subscribersByGameWeek =
            new ConcurrentHashMap<>();

    /** Dedicated daemon thread so heartbeat work never blocks request threads. */
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "manager-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    public ManagerLiveUpdateService() {
        heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeatsSafely,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * Registers one authenticated browser for updates for one game week.
     */
    public SseEmitter subscribe(Long gameWeekId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        Subscriber subscriber = new Subscriber(gameWeekId, emitter);

        subscribersByGameWeek
                .computeIfAbsent(gameWeekId, ignored -> new CopyOnWriteArrayList<>())
                .add(subscriber);

        // Every normal and abnormal stream-ending path uses the same idempotent
        // cleanup routine. AtomicBoolean prevents duplicate completion work.
        emitter.onCompletion(() -> removeSubscriber(subscriber, false));
        emitter.onTimeout(() -> removeSubscriber(subscriber, true));
        emitter.onError(error -> removeSubscriber(subscriber, false));

        // Confirm the stream immediately. This also detects clients that closed
        // before registration completed.
        try {
            sendEvent(subscriber, SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "gameWeekId", gameWeekId,
                            "connectedAt", Instant.now().toString())));
        } catch (Exception error) {
            removeSubscriber(subscriber, true);
        }

        return emitter;
    }

    /**
     * Broadcasts the authoritative post-action snapshot to every dashboard
     * currently watching the affected game week.
     */
    public void publish(ManagerDashboardState state) {
        if (state == null || state.getGameWeekId() == null) {
            return;
        }

        CopyOnWriteArrayList<Subscriber> subscribers =
                subscribersByGameWeek.get(state.getGameWeekId());

        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        for (Subscriber subscriber : subscribers) {
            try {
                sendEvent(subscriber, SseEmitter.event()
                        .name("dashboard-state")
                        .id(String.valueOf(System.nanoTime()))
                        .data(state));
            } catch (Exception error) {
                // Closed tabs, refreshes, sleeping phones, and Wi-Fi changes are
                // expected. Remove the stale subscriber and continue broadcasting.
                removeSubscriber(subscriber, true);
            }
        }
    }

    /**
     * Sends a lightweight named event to keep idle streams active.
     *
     * <p>Every send is isolated so one disconnected browser cannot terminate the
     * scheduled task or affect the remaining subscribers.</p>
     */
    private void sendHeartbeatsSafely() {
        try {
            subscribersByGameWeek.forEach((gameWeekId, subscribers) -> {
                for (Subscriber subscriber : subscribers) {
                    try {
                        sendEvent(subscriber, SseEmitter.event()
                                .name("heartbeat")
                                .data(Instant.now().toString()));
                    } catch (Throwable error) {
                        // Catch Throwable here intentionally: this runs on a shared
                        // scheduled task, which must survive all client disconnects.
                        removeSubscriber(subscriber, true);
                    }
                }
            });
        } catch (Throwable error) {
            // Defensive final boundary: ScheduledExecutorService suppresses future
            // executions if an unchecked exception escapes a recurring task.
            LOGGER.warn("Unexpected error while sending manager SSE heartbeats.", error);
        }
    }

    /**
     * Serializes writes to one SseEmitter. Two managers can perform actions at
     * nearly the same moment, and SseEmitter is not documented as thread-safe.
     */
    private void sendEvent(Subscriber subscriber, SseEmitter.SseEventBuilder event)
            throws IOException {
        if (subscriber.closed.get()) {
            throw new IOException("SSE subscriber is already closed.");
        }

        synchronized (subscriber.emitter) {
            if (subscriber.closed.get()) {
                throw new IOException("SSE subscriber closed before send.");
            }
            subscriber.emitter.send(event);
        }
    }

    /**
     * Removes and optionally completes a subscriber. This method is idempotent
     * because several lifecycle callbacks can fire for the same connection.
     */
    private void removeSubscriber(Subscriber subscriber, boolean completeEmitter) {
        if (!subscriber.closed.compareAndSet(false, true)) {
            return;
        }

        CopyOnWriteArrayList<Subscriber> subscribers =
                subscribersByGameWeek.get(subscriber.gameWeekId);

        if (subscribers != null) {
            subscribers.remove(subscriber);
            if (subscribers.isEmpty()) {
                subscribersByGameWeek.remove(subscriber.gameWeekId, subscribers);
            }
        }

        if (completeEmitter) {
            try {
                subscriber.emitter.complete();
            } catch (Exception ignored) {
                // The underlying socket may already be gone. That is normal for
                // browser refreshes and mobile-network interruptions.
                LOGGER.debug("Manager SSE emitter was already closed during cleanup.");
            }
        }
    }

    /** Closes the heartbeat thread and all subscriptions during app shutdown. */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();

        subscribersByGameWeek.values().forEach(subscribers ->
                subscribers.forEach(subscriber -> removeSubscriber(subscriber, true)));

        subscribersByGameWeek.clear();
    }

    /**
     * Small wrapper holding lifecycle state for a single browser connection.
     */
    private static final class Subscriber {
        private final Long gameWeekId;
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Subscriber(Long gameWeekId, SseEmitter emitter) {
            this.gameWeekId = gameWeekId;
            this.emitter = emitter;
        }
    }
}

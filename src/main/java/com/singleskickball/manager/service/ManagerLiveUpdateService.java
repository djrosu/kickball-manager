package com.singleskickball.manager.service;

import com.singleskickball.manager.dto.AudioTargetState;
import com.singleskickball.manager.dto.ManagerDashboardState;
import com.singleskickball.manager.dto.WalkUpSongInfo;
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

    /** Current optional audio owner for each active game. This is intentionally
     * in memory: selecting an audio device is temporary game-session state. */
    private final Map<Long, AudioTargetState> audioTargetsByGameWeek = new ConcurrentHashMap<>();

    /**
     * Device currently playing between-at-bat music for each game. This lets a
     * later Next Batter action stop the correct browser even when another
     * manager initiated the next-batter command.
     */
    private final Map<Long, String> betweenAtBatAudioDeviceByGameWeek =
            new ConcurrentHashMap<>();

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
    public SseEmitter subscribe(Long gameWeekId, String deviceId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        Subscriber subscriber = new Subscriber(gameWeekId, deviceId, emitter);

        CopyOnWriteArrayList<Subscriber> subscribers =
                subscribersByGameWeek.computeIfAbsent(
                        gameWeekId,
                        ignored -> new CopyOnWriteArrayList<>());

        /*
         * A browser can reconnect before Tomcat notices that its previous socket
         * disappeared. Keep only the newest EventSource connection for a device.
         * This is the main protection against repeatedly writing to stale mobile
         * or refreshed-browser connections.
         */
        if (deviceId != null && !deviceId.isBlank()) {
            for (Subscriber existing : subscribers) {
                if (deviceId.equals(existing.deviceId)) {
                    removeSubscriber(existing, false);
                }
            }
        }

        subscribers.add(subscriber);

        // Every normal and abnormal stream-ending path uses the same idempotent
        // cleanup routine. AtomicBoolean prevents duplicate completion work.
        emitter.onCompletion(() -> removeSubscriber(subscriber, false));
        emitter.onTimeout(() -> removeSubscriber(subscriber, true));
        emitter.onError(error -> removeSubscriber(subscriber, false));

        // Confirm the stream immediately. This also detects clients that closed
        // before registration completed.
        boolean connected = trySendEvent(
                subscriber,
                SseEmitter.event()
                        .name("connected")
                        .data(Map.of(
                                "gameWeekId", gameWeekId,
                                "connectedAt", Instant.now().toString())));

        if (!connected) {
            removeSubscriber(subscriber, false);
        }

        return emitter;
    }



    /** Returns the current target, or an untargeted state when default audio routing is active. */
    public AudioTargetState getAudioTargetState(Long gameWeekId) {
        AudioTargetState state = audioTargetsByGameWeek.get(gameWeekId);
        return state != null
                ? state
                : new AudioTargetState(gameWeekId, false, null, null);
    }

    /**
     * Gives one manager device exclusive ownership of all subsequent walk-up audio.
     * A later claim immediately replaces the previous owner.
     */
    public AudioTargetState claimAudioTarget(Long gameWeekId, String deviceId, String managerName) {
        AudioTargetState state = new AudioTargetState(gameWeekId, true, deviceId, managerName);
        audioTargetsByGameWeek.put(gameWeekId, state);
        broadcastNamedEvent(gameWeekId, "audio-target-state", state);
        return state;
    }

    /** Releases audio only when the requesting device currently owns it. */
    public AudioTargetState releaseAudioTarget(Long gameWeekId, String deviceId) {
        audioTargetsByGameWeek.computeIfPresent(gameWeekId, (ignored, current) ->
                deviceId != null && deviceId.equals(current.getDeviceId()) ? null : current);
        AudioTargetState state = getAudioTargetState(gameWeekId);
        broadcastNamedEvent(gameWeekId, "audio-target-state", state);
        return state;
    }

    /**
     * Starts one between-at-bat song on the selected audio controller, or on the
     * device that ended the at-bat when no dedicated controller is selected.
     */
    public void publishBetweenAtBatSong(Long gameWeekId,
                                        String initiatingDeviceId,
                                        String songUrl) {
        if (songUrl == null || songUrl.isBlank()) {
            return;
        }

        AudioTargetState target = audioTargetsByGameWeek.get(gameWeekId);
        String destinationDeviceId =
                target != null && target.isTargeted()
                        ? target.getDeviceId()
                        : initiatingDeviceId;

        if (destinationDeviceId == null || destinationDeviceId.isBlank()) {
            return;
        }

        betweenAtBatAudioDeviceByGameWeek.put(gameWeekId, destinationDeviceId);

        sendNamedEventToDevice(
                gameWeekId,
                destinationDeviceId,
                "between-at-bat-audio",
                Map.of(
                        "targetDeviceId", destinationDeviceId,
                        "audioUrl", songUrl));
    }

    /**
     * Stops any between-at-bat music currently playing for this game.
     *
     * <p>This is called before Next Batter advances. The stop command is routed
     * to the device that actually received the break-music command.</p>
     */
    public void stopBetweenAtBatAudio(Long gameWeekId) {
        String destinationDeviceId =
                betweenAtBatAudioDeviceByGameWeek.remove(gameWeekId);

        if (destinationDeviceId == null || destinationDeviceId.isBlank()) {
            return;
        }

        sendNamedEventToDevice(
                gameWeekId,
                destinationDeviceId,
                "audio-stop",
                Map.of("reason", "next-batter"));
    }

    /**
     * Sends a batter audio command only to the currently selected audio device.
     *
     * <p>Earlier versions broadcast this event to every connected browser and
     * relied on JavaScript to ignore commands for other devices. Targeting the
     * subscriber on the server is quieter, avoids needless writes to stale
     * connections, and guarantees that only the selected device is asked to play.</p>
     */
    public void publishAudioCommandIfTargeted(Long gameWeekId, WalkUpSongInfo batter) {
        AudioTargetState target = audioTargetsByGameWeek.get(gameWeekId);
        if (target == null || batter == null || target.getDeviceId() == null) {
            return;
        }

        sendNamedEventToDevice(
                gameWeekId,
                target.getDeviceId(),
                "audio-command",
                Map.of(
                        "targetDeviceId", target.getDeviceId(),
                        "batter", batter));
    }

    /**
     * Sends one named event only to subscribers belonging to a specific device.
     *
     * <p>A browser may briefly have more than one EventSource connection while
     * reconnecting. Sending to every matching subscription is intentional; dead
     * subscriptions are removed immediately after a failed write.</p>
     */
    private void sendNamedEventToDevice(Long gameWeekId,
                                        String deviceId,
                                        String eventName,
                                        Object data) {
        CopyOnWriteArrayList<Subscriber> subscribers =
                subscribersByGameWeek.get(gameWeekId);

        if (subscribers == null || deviceId == null) {
            return;
        }

        for (Subscriber subscriber : subscribers) {
            if (!deviceId.equals(subscriber.deviceId)) {
                continue;
            }

            if (!trySendEvent(
                    subscriber,
                    SseEmitter.event().name(eventName).data(data))) {
                removeSubscriber(subscriber, false);
            }
        }
    }

    /** Broadcasts a named SSE event to all viewers of one game. */
    private void broadcastNamedEvent(Long gameWeekId, String eventName, Object data) {
        CopyOnWriteArrayList<Subscriber> subscribers =
                subscribersByGameWeek.get(gameWeekId);

        if (subscribers == null) {
            return;
        }

        for (Subscriber subscriber : subscribers) {
            if (!trySendEvent(
                    subscriber,
                    SseEmitter.event().name(eventName).data(data))) {
                removeSubscriber(subscriber, false);
            }
        }
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
            boolean sent = trySendEvent(
                    subscriber,
                    SseEmitter.event()
                            .name("dashboard-state")
                            .id(String.valueOf(System.nanoTime()))
                            .data(state));

            if (!sent) {
                // Do not call complete() on a socket that already failed during
                // a write. Removing it is enough; EventSource reconnects itself.
                removeSubscriber(subscriber, false);
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
                    boolean sent = trySendEvent(
                            subscriber,
                            SseEmitter.event()
                                    .name("heartbeat")
                                    .data(Instant.now().toString()));

                    if (!sent) {
                        // A heartbeat is a best-effort liveness check. A failed
                        // socket is simply removed and allowed to reconnect.
                        removeSubscriber(subscriber, false);
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
     * Best-effort SSE write used by all broadcast paths.
     *
     * <p>Client disconnects are normal: users refresh pages, phones sleep, and
     * wireless networks change. Those failures must not escape into the JSON API
     * request that triggered a broadcast or create noisy application exceptions.</p>
     */
    private boolean trySendEvent(Subscriber subscriber,
                                 SseEmitter.SseEventBuilder event) {
        try {
            sendEvent(subscriber, event);
            return true;
        } catch (IOException | IllegalStateException error) {
            LOGGER.debug("Discarding closed manager SSE connection: {}",
                    error.getMessage());
            return false;
        } catch (RuntimeException error) {
            // Converter/container implementations can wrap a closed socket in a
            // RuntimeException. Treat it as a dead subscriber, not an API failure.
            LOGGER.debug("Discarding failed manager SSE connection.", error);
            return false;
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
        audioTargetsByGameWeek.clear();
    }

    /**
     * Small wrapper holding lifecycle state for a single browser connection.
     */
    private static final class Subscriber {
        private final Long gameWeekId;
        private final String deviceId;
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Subscriber(Long gameWeekId, String deviceId, SseEmitter emitter) {
            this.gameWeekId = gameWeekId;
            this.deviceId = deviceId;
            this.emitter = emitter;
        }
    }
}

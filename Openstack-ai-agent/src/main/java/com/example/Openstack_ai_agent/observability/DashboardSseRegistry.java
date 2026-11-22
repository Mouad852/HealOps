package com.example.Openstack_ai_agent.observability;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
public class DashboardSseRegistry {
    private static final Logger log = LoggerFactory.getLogger(DashboardSseRegistry.class);

    private final ConcurrentHashMap<String, Set<ClientSubscription>> subscriptionsByEnv = new ConcurrentHashMap<>();
    private final DashboardSseProperties properties;
    private final ScheduledExecutorService heartbeatScheduler;

    public DashboardSseRegistry(DashboardSseProperties properties) {
        this.properties = properties;
        long intervalMs = properties.getHeartbeatInterval() == null ? 0L : properties.getHeartbeatInterval().toMillis();
        if (properties.isHeartbeatEnabled() && intervalMs > 0) {
            this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "healops-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });
            this.heartbeatScheduler.scheduleAtFixedRate(
                    this::sendHeartbeats,
                    intervalMs,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
        } else {
            this.heartbeatScheduler = null;
        }
    }

    public SseEmitter registerAndReplay(
            String environmentId,
            Long lastEventId,
            Supplier<List<DashboardEventEnvelope>> replaySupplier
    ) {
        long timeoutMs = properties.getEmitterTimeout() == null ? 0L : properties.getEmitterTimeout().toMillis();
        SseEmitter emitter = createEmitter(timeoutMs);
        long initialCursor = lastEventId == null ? 0L : lastEventId;
        ClientSubscription subscription = new ClientSubscription(
                environmentId,
                emitter,
                initialCursor,
                properties.getMaxPendingLiveEvents()
        );

        subscriptionsByEnv
                .computeIfAbsent(environmentId, ignored -> ConcurrentHashMap.newKeySet())
                .add(subscription);

        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> {
            remove(subscription);
            subscription.completeSilently();
        });
        emitter.onError(throwable -> remove(subscription));

        try {
            subscription.sendComment("connected");
            if (lastEventId != null && replaySupplier != null) {
                List<DashboardEventEnvelope> replayed = replaySupplier.get();
                int limit = Math.min(replayed.size(), properties.getMaxReplayEvents());
                for (int i = 0; i < limit; i++) {
                    if (!subscription.sendReplayEnvelope(replayed.get(i))) {
                        remove(subscription);
                        return emitter;
                    }
                }
            }
            if (!subscription.finishReplayAndFlushPending()) {
                remove(subscription);
            }
        } catch (Exception ex) {
            log.debug("Failed during SSE initial replay for environment {}: {}", environmentId, ex.getMessage());
            remove(subscription);
            subscription.completeWithErrorSilently(ex);
        }

        return emitter;
    }

    public void broadcast(DashboardEventEnvelope envelope) {
        if (envelope == null || envelope.environmentId() == null) {
            return;
        }
        Set<ClientSubscription> subscribers = subscriptionsByEnv.get(envelope.environmentId());
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (ClientSubscription subscription : List.copyOf(subscribers)) {
            if (!subscription.sendLiveEnvelope(envelope)) {
                remove(subscription);
            }
        }
    }

    public void sendHeartbeats() {
        for (Set<ClientSubscription> subscribers : subscriptionsByEnv.values()) {
            for (ClientSubscription subscription : List.copyOf(subscribers)) {
                if (!subscription.sendComment("heartbeat")) {
                    remove(subscription);
                }
            }
        }
    }

    public int activeConnectionCount(String environmentId) {
        Set<ClientSubscription> subscribers = subscriptionsByEnv.get(environmentId);
        return subscribers == null ? 0 : subscribers.size();
    }

    public int totalActiveConnections() {
        return subscriptionsByEnv.values().stream().mapToInt(Set::size).sum();
    }

    public void completeEnvironment(String environmentId) {
        Set<ClientSubscription> subscribers = subscriptionsByEnv.get(environmentId);
        if (subscribers == null) {
            return;
        }
        for (ClientSubscription subscription : List.copyOf(subscribers)) {
            remove(subscription);
            subscription.completeSilently();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        for (Set<ClientSubscription> subscribers : subscriptionsByEnv.values()) {
            for (ClientSubscription subscription : List.copyOf(subscribers)) {
                subscription.completeSilently();
            }
        }
        subscriptionsByEnv.clear();
    }

    protected SseEmitter createEmitter(long timeoutMs) {
        return new SseEmitter(timeoutMs);
    }

    private void remove(ClientSubscription subscription) {
        subscription.markClosed();
        Set<ClientSubscription> subscribers = subscriptionsByEnv.get(subscription.environmentId);
        if (subscribers != null) {
            subscribers.remove(subscription);
            if (subscribers.isEmpty()) {
                subscriptionsByEnv.remove(subscription.environmentId, subscribers);
            }
        }
    }

    static final class ClientSubscription {
        private final String environmentId;
        private final SseEmitter emitter;
        private final int maxPendingLiveEvents;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Queue<DashboardEventEnvelope> pendingLive = new ArrayDeque<>();
        private boolean replaying = true;
        private long lastSentSequence;

        ClientSubscription(
                String environmentId,
                SseEmitter emitter,
                long initialSequence,
                int maxPendingLiveEvents
        ) {
            this.environmentId = environmentId;
            this.emitter = emitter;
            this.lastSentSequence = initialSequence;
            this.maxPendingLiveEvents = Math.max(1, maxPendingLiveEvents);
        }

        synchronized boolean sendReplayEnvelope(DashboardEventEnvelope envelope) {
            if (closed.get()) {
                return false;
            }
            if (envelope.sequenceNumber() <= lastSentSequence) {
                return true;
            }
            return writeEnvelope(envelope);
        }

        synchronized boolean finishReplayAndFlushPending() {
            if (closed.get()) {
                return false;
            }
            while (!pendingLive.isEmpty()) {
                DashboardEventEnvelope queued = pendingLive.poll();
                if (queued.sequenceNumber() > lastSentSequence) {
                    if (!writeEnvelope(queued)) {
                        return false;
                    }
                }
            }
            replaying = false;
            return true;
        }

        synchronized boolean sendLiveEnvelope(DashboardEventEnvelope envelope) {
            if (closed.get()) {
                return false;
            }
            if (replaying) {
                if (pendingLive.size() >= maxPendingLiveEvents) {
                    closed.set(true);
                    completeSilently();
                    return false;
                }
                pendingLive.add(envelope);
                return true;
            }
            if (envelope.sequenceNumber() <= lastSentSequence) {
                return true;
            }
            return writeEnvelope(envelope);
        }

        synchronized boolean sendComment(String comment) {
            if (closed.get()) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().comment(comment));
                return true;
            } catch (IOException | IllegalStateException ex) {
                closed.set(true);
                completeWithErrorSilently(ex);
                return false;
            }
        }

        void markClosed() {
            closed.set(true);
        }

        void completeSilently() {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // Already completed or disconnected
            }
        }

        void completeWithErrorSilently(Throwable error) {
            try {
                emitter.completeWithError(error);
            } catch (Exception ignored) {
                // Already completed or disconnected
            }
        }

        private boolean writeEnvelope(DashboardEventEnvelope envelope) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(envelope.sequenceNumber()))
                        .name(envelope.type())
                        .data(envelope, MediaType.APPLICATION_JSON));
                lastSentSequence = Math.max(lastSentSequence, envelope.sequenceNumber());
                return true;
            } catch (IOException | IllegalStateException ex) {
                closed.set(true);
                completeWithErrorSilently(ex);
                return false;
            }
        }
    }
}

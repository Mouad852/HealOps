package com.example.Openstack_ai_agent.observability;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DashboardEventPublisher {
    private final DashboardEventRepository dashboardEventRepository;
    private final DashboardSseRegistry sseRegistry;
    private final EntityManager entityManager;

    public DashboardEventPublisher(
            DashboardEventRepository dashboardEventRepository,
            DashboardSseRegistry sseRegistry,
            EntityManager entityManager
    ) {
        this.dashboardEventRepository = dashboardEventRepository;
        this.sseRegistry = sseRegistry;
        this.entityManager = entityManager;
    }

    @Transactional
    public DashboardEventEnvelope publish(
            String environmentId,
            String eventType,
            UUID incidentId,
            String traceId,
            Map<String, Object> payload
    ) {
        DashboardEventEntity saved = dashboardEventRepository.saveAndFlush(
                DashboardEventEntity.of(environmentId, eventType, incidentId, traceId, payload)
        );
        if (saved.getSequenceNumber() == null) {
            entityManager.refresh(saved);
        }
        DashboardEventEnvelope envelope = DashboardEventEnvelope.from(saved);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sseRegistry.broadcast(envelope);
                }
            });
        } else {
            sseRegistry.broadcast(envelope);
        }
        return envelope;
    }

    @Transactional(readOnly = true)
    public List<DashboardEventEnvelope> findEventsAfter(String environmentId, long lastEventId) {
        return dashboardEventRepository
                .findByEnvironmentIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(environmentId, lastEventId)
                .stream()
                .map(DashboardEventEnvelope::from)
                .toList();
    }
}

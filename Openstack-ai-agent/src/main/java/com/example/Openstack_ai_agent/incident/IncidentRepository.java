package com.example.Openstack_ai_agent.incident;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID>, JpaSpecificationExecutor<IncidentEntity> {
    Optional<IncidentEntity> findBySourceAndEnvironmentIdAndFingerprint(
            String source,
            String environmentId,
            String fingerprint
    );

    List<IncidentEntity> findByEnvironmentId(String environmentId);

    List<IncidentEntity> findByEnvironmentIdIn(Collection<String> environmentIds);

    List<IncidentEntity> findByEnvironmentIdAndService(String environmentId, String service);

    List<IncidentEntity> findByEnvironmentIdAndTraceId(String environmentId, String traceId);

    List<IncidentEntity> findByEnvironmentIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            String environmentId,
            OffsetDateTime from,
            OffsetDateTime to
    );

    List<IncidentEntity> findByEnvironmentIdAndServiceAndCreatedAtBetweenOrderByCreatedAtDesc(
            String environmentId,
            String service,
            OffsetDateTime from,
            OffsetDateTime to
    );

    @Query("""
            select i from IncidentEntity i
            where i.environmentId = :environmentId
              and (i.status not in :terminalStatuses or (i.createdAt >= :from and i.createdAt <= :to))
            """)
    List<IncidentEntity> findForTopology(
            @Param("environmentId") String environmentId,
            @Param("terminalStatuses") Collection<IncidentStatus> terminalStatuses,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to
    );

    /**
     * Database-level keyset pagination ordered by {@code created_at DESC, id DESC},
     * leveraging {@code idx_incidents_environment_status_created_at} without full-table scans.
     */
    default List<IncidentEntity> findKeysetPage(
            Collection<String> environmentIds,
            IncidentStatus status,
            IncidentSeverity severity,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int fetchLimit
    ) {
        if (environmentIds == null || environmentIds.isEmpty() || fetchLimit <= 0) {
            return List.of();
        }
        Specification<IncidentEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("environmentId").in(environmentIds));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (cursorCreatedAt != null && cursorId != null) {
                predicates.add(cb.or(
                        cb.lessThan(root.get("createdAt"), cursorCreatedAt),
                        cb.and(
                                cb.equal(root.get("createdAt"), cursorCreatedAt),
                                cb.lessThan(root.get("id"), cursorId)
                        )
                ));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return findBy(spec, query -> query
                .sortBy(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                .limit(fetchLimit)
                .all());
    }
}

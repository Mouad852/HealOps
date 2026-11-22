package com.example.Openstack_ai_agent.remediation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforces the allowlisted typed action catalog.
 * Validates all action types and parameter values against strict identifier patterns and bounds,
 * and renders a read-only human command preview without accepting shell or YAML text.
 */
@Component
public class ActionCatalogValidator {
    public static final int MIN_K8S_REPLICAS = 1;
    public static final int MAX_K8S_REPLICAS = 10;
    public static final int MIN_K8S_REVISION = 1;
    public static final int MAX_K8S_REVISION = 1000;

    private static final Pattern K8S_DNS_LABEL = Pattern.compile("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$");
    private static final Pattern CONTAINER_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$");
    private static final Pattern HOST_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9.-]{0,127}$");
    private static final Pattern SERVICE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$");

    private static final Set<String> ALLOWED_K8S_WORKLOAD_KINDS = Set.of("Deployment", "StatefulSet", "DaemonSet");

    public ValidatedActionProposal validateAndNormalize(String rawActionType, Map<String, Object> rawArguments) {
        if (rawActionType == null || rawActionType.isBlank()) {
            throw new ActionCatalogValidationException("Action type is required");
        }
        ActionType actionType;
        try {
            actionType = ActionType.valueOf(rawActionType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ActionCatalogValidationException("Unsupported action type: " + rawActionType);
        }
        return validateAndNormalize(actionType, rawArguments);
    }

    public ValidatedActionProposal validateAndNormalize(ActionType actionType, Map<String, Object> rawArguments) {
        if (actionType == null) {
            throw new ActionCatalogValidationException("Action type is required");
        }
        Map<String, Object> args = rawArguments == null ? Map.of() : rawArguments;
        Map<String, Object> normalized = new LinkedHashMap<>();

        return switch (actionType) {
            case NO_ACTION -> {
                rejectUnexpectedKeys(actionType, args, Set.of("reason"));
                if (args.containsKey("reason") && args.get("reason") != null) {
                    String reason = asSafeText(args.get("reason"), "reason", 500);
                    if (!reason.isBlank()) {
                        normalized.put("reason", reason);
                    }
                }
                Map<String, Object> immutable = Map.copyOf(normalized);
                yield new ValidatedActionProposal(
                        ActionType.NO_ACTION,
                        immutable,
                        ActionRiskLevel.LOW,
                        "NONE",
                        renderCommandPreview(ActionType.NO_ACTION, immutable)
                );
            }
            case RESTART_CONTAINER -> {
                rejectUnexpectedKeys(actionType, args, Set.of("container"));
                String container = requirePattern(args, "container", CONTAINER_IDENTIFIER, "Docker container identifier");
                normalized.put("container", container);
                Map<String, Object> immutable = Map.copyOf(normalized);
                yield new ValidatedActionProposal(
                        ActionType.RESTART_CONTAINER,
                        immutable,
                        ActionRiskLevel.MEDIUM,
                        "NON_REVERSIBLE_RESTART",
                        renderCommandPreview(ActionType.RESTART_CONTAINER, immutable)
                );
            }
            case RESTART_K8S_WORKLOAD -> {
                rejectUnexpectedKeys(actionType, args, Set.of("namespace", "workload", "kind"));
                String namespace = requirePattern(args, "namespace", K8S_DNS_LABEL, "Kubernetes namespace");
                String workload = requirePattern(args, "workload", K8S_DNS_LABEL, "Kubernetes workload name");
                String kind = optionalWorkloadKind(args.get("kind"));
                normalized.put("namespace", namespace);
                normalized.put("workload", workload);
                normalized.put("kind", kind);
                Map<String, Object> immutable = Map.copyOf(normalized);
                yield new ValidatedActionProposal(
                        ActionType.RESTART_K8S_WORKLOAD,
                        immutable,
                        ActionRiskLevel.MEDIUM,
                        "NON_REVERSIBLE_ROLLOUT_RESTART",
                        renderCommandPreview(ActionType.RESTART_K8S_WORKLOAD, immutable)
                );
            }
            case SCALE_K8S_DEPLOYMENT -> {
                rejectUnexpectedKeys(actionType, args, Set.of("namespace", "deployment", "replicas"));
                String namespace = requirePattern(args, "namespace", K8S_DNS_LABEL, "Kubernetes namespace");
                String deployment = requirePattern(args, "deployment", K8S_DNS_LABEL, "Kubernetes deployment name");
                int replicas = requireBoundedInt(args, "replicas", MIN_K8S_REPLICAS, MAX_K8S_REPLICAS);
                normalized.put("namespace", namespace);
                normalized.put("deployment", deployment);
                normalized.put("replicas", replicas);
                Map<String, Object> immutable = Map.copyOf(normalized);
                ActionRiskLevel risk = replicas >= 8 ? ActionRiskLevel.HIGH : ActionRiskLevel.MEDIUM;
                yield new ValidatedActionProposal(
                        ActionType.SCALE_K8S_DEPLOYMENT,
                        immutable,
                        risk,
                        "RESTORE_PREVIOUS_REPLICAS",
                        renderCommandPreview(ActionType.SCALE_K8S_DEPLOYMENT, immutable)
                );
            }
            case ROLLBACK_K8S_DEPLOYMENT -> {
                rejectUnexpectedKeys(actionType, args, Set.of("namespace", "deployment", "revision"));
                String namespace = requirePattern(args, "namespace", K8S_DNS_LABEL, "Kubernetes namespace");
                String deployment = requirePattern(args, "deployment", K8S_DNS_LABEL, "Kubernetes deployment name");
                normalized.put("namespace", namespace);
                normalized.put("deployment", deployment);
                if (args.containsKey("revision") && args.get("revision") != null && !String.valueOf(args.get("revision")).isBlank()) {
                    int revision = requireBoundedInt(args, "revision", MIN_K8S_REVISION, MAX_K8S_REVISION);
                    normalized.put("revision", revision);
                }
                Map<String, Object> immutable = Map.copyOf(normalized);
                yield new ValidatedActionProposal(
                        ActionType.ROLLBACK_K8S_DEPLOYMENT,
                        immutable,
                        ActionRiskLevel.HIGH,
                        "ROLLOUT_TO_PRIOR_REVISION",
                        renderCommandPreview(ActionType.ROLLBACK_K8S_DEPLOYMENT, immutable)
                );
            }
            case RESTART_OPENSTACK_SERVICE -> {
                rejectUnexpectedKeys(actionType, args, Set.of("node", "service"));
                String node = requirePattern(args, "node", HOST_IDENTIFIER, "OpenStack node identifier");
                String service = requirePattern(args, "service", SERVICE_IDENTIFIER, "OpenStack service identifier");
                normalized.put("node", node);
                normalized.put("service", service);
                Map<String, Object> immutable = Map.copyOf(normalized);
                yield new ValidatedActionProposal(
                        ActionType.RESTART_OPENSTACK_SERVICE,
                        immutable,
                        ActionRiskLevel.HIGH,
                        "NON_REVERSIBLE_SERVICE_RESTART",
                        renderCommandPreview(ActionType.RESTART_OPENSTACK_SERVICE, immutable)
                );
            }
        };
    }

    public String renderCommandPreview(ActionType actionType, Map<String, Object> arguments) {
        Map<String, Object> safeArgs = arguments == null ? Map.of() : arguments;
        return switch (actionType) {
            case NO_ACTION -> {
                Object reason = safeArgs.get("reason");
                yield reason != null && !String.valueOf(reason).isBlank()
                        ? "No automated action required (" + reason + ")"
                        : "No automated action required";
            }
            case RESTART_CONTAINER ->
                    "docker restart " + safeArgs.getOrDefault("container", "<unknown>");
            case RESTART_K8S_WORKLOAD -> {
                String kind = String.valueOf(safeArgs.getOrDefault("kind", "Deployment")).toLowerCase(Locale.ROOT);
                yield "kubectl rollout restart " + kind + "/" + safeArgs.getOrDefault("workload", "<unknown>")
                        + " -n " + safeArgs.getOrDefault("namespace", "<unknown>");
            }
            case SCALE_K8S_DEPLOYMENT ->
                    "kubectl scale deployment/" + safeArgs.getOrDefault("deployment", "<unknown>")
                            + " --replicas=" + safeArgs.getOrDefault("replicas", "?")
                            + " -n " + safeArgs.getOrDefault("namespace", "<unknown>");
            case ROLLBACK_K8S_DEPLOYMENT -> {
                String base = "kubectl rollout undo deployment/" + safeArgs.getOrDefault("deployment", "<unknown>")
                        + " -n " + safeArgs.getOrDefault("namespace", "<unknown>");
                Object revision = safeArgs.get("revision");
                yield revision != null ? base + " --to-revision=" + revision : base;
            }
            case RESTART_OPENSTACK_SERVICE ->
                    "systemctl restart " + safeArgs.getOrDefault("service", "<unknown>")
                            + " on OpenStack node " + safeArgs.getOrDefault("node", "<unknown>");
        };
    }

    private void rejectUnexpectedKeys(ActionType actionType, Map<String, Object> args, Set<String> allowedKeys) {
        for (String key : args.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new ActionCatalogValidationException(
                        "Unsupported argument '" + key + "' for action " + actionType
                );
            }
        }
    }

    private String requirePattern(Map<String, Object> args, String key, Pattern pattern, String label) {
        Object raw = args.get(key);
        if (raw == null) {
            throw new ActionCatalogValidationException("Missing required argument '" + key + "' (" + label + ")");
        }
        String value = String.valueOf(raw).trim();
        if (value.isEmpty() || !pattern.matcher(value).matches()) {
            throw new ActionCatalogValidationException(
                    "Invalid " + label + " for argument '" + key + "': must match " + pattern.pattern()
            );
        }
        return value;
    }

    private String optionalWorkloadKind(Object rawKind) {
        if (rawKind == null || String.valueOf(rawKind).isBlank()) {
            return "Deployment";
        }
        String trimmed = String.valueOf(rawKind).trim();
        for (String allowed : ALLOWED_K8S_WORKLOAD_KINDS) {
            if (allowed.equalsIgnoreCase(trimmed)) {
                return allowed;
            }
        }
        throw new ActionCatalogValidationException(
                "Unsupported Kubernetes workload kind '" + trimmed + "'; allowed: " + ALLOWED_K8S_WORKLOAD_KINDS
        );
    }

    private int requireBoundedInt(Map<String, Object> args, String key, int min, int max) {
        Object raw = args.get(key);
        if (raw == null) {
            throw new ActionCatalogValidationException("Missing required integer argument '" + key + "'");
        }
        int parsed;
        if (raw instanceof Number number) {
            double asDouble = number.doubleValue();
            if (Math.floor(asDouble) != asDouble || Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
                throw new ActionCatalogValidationException("Argument '" + key + "' must be a whole integer");
            }
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException exception) {
                throw new ActionCatalogValidationException("Argument '" + key + "' must be a valid integer");
            }
        }
        if (parsed < min || parsed > max) {
            throw new ActionCatalogValidationException(
                    "Argument '" + key + "' value " + parsed + " is out of allowed bounds [" + min + ", " + max + "]"
            );
        }
        return parsed;
    }

    private String asSafeText(Object raw, String key, int maxLength) {
        String text = String.valueOf(raw).trim();
        if (text.length() > maxLength) {
            throw new ActionCatalogValidationException(
                    "Argument '" + key + "' exceeds maximum length of " + maxLength
            );
        }
        if (text.contains("\n") || text.contains("\r")) {
            throw new ActionCatalogValidationException(
                    "Argument '" + key + "' must be single-line text"
            );
        }
        return text;
    }
}

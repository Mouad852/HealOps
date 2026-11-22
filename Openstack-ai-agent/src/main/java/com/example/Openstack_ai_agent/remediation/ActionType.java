package com.example.Openstack_ai_agent.remediation;

public enum ActionType {
    NO_ACTION,
    RESTART_CONTAINER,
    RESTART_K8S_WORKLOAD,
    SCALE_K8S_DEPLOYMENT,
    ROLLBACK_K8S_DEPLOYMENT,
    RESTART_OPENSTACK_SERVICE
}

package com.aethergate.domain.model;

/**
 * Workspace roles, strictly ordered: every role can do everything the roles below it can.
 * <ul>
 *   <li>VIEWER: read-only console.</li>
 *   <li>DEVELOPER: + playground, API keys, prompt template drafts.</li>
 *   <li>ADMIN: + projects, guardrails, models, webhooks, alerts, inviting Developers/Viewers, publishing prompts.</li>
 *   <li>OWNER: + workspace limit, top-ups, managing Admins and Owners.</li>
 * </ul>
 */
public enum MemberRole {
    VIEWER, DEVELOPER, ADMIN, OWNER;

    public boolean atLeast(MemberRole required) {
        return compareTo(required) >= 0;
    }
}

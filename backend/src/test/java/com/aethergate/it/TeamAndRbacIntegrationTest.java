package com.aethergate.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Members, invitations, role enforcement, workspace switching and the audit log. */
@AutoConfigureMockMvc
class TeamAndRbacIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    record Account(JwtRequestPostProcessor auth, UUID tenantId, String email) {}

    private Account signUp() throws Exception {
        UUID user = UUID.randomUUID();
        String email = "u" + user.toString().substring(0, 8) + "@example.com";
        JwtRequestPostProcessor auth = jwt().jwt(j -> j.subject(user.toString()).claim("email", email)
                .audience(List.of("authenticated")));
        MvcResult me = mvc.perform(get("/api/v1/me").with(auth)).andExpect(status().isOk()).andReturn();
        return new Account(auth, UUID.fromString(read(me).at("/tenant/id").asText()), email);
    }

    private JsonNode read(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String invite(Account owner, String email, String role) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/tenant/members").with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.member.status").value("INVITED"))
                .andReturn();
        return read(r).get("token").asText();
    }

    private void accept(Account who, String token) throws Exception {
        mvc.perform(post("/api/v1/invitations/accept").with(who.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("invite → accept → switch workspace; Viewer reads but cannot write (403, audited); token is single-use and e-mail bound")
    void inviteAcceptAndViewerIsReadOnly() throws Exception {
        Account owner = signUp();
        Account viewer = signUp();
        Account stranger = signUp();
        String token = invite(owner, viewer.email().toUpperCase(), "VIEWER");
        assertThat(token).startsWith("inv_");
        // only the SHA-256 is stored
        assertThat(count("select count(*) from tenant_members where invite_token_hash = ?", token)).isZero();

        mvc.perform(post("/api/v1/invitations/accept").with(stranger.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(endsWith("/invitation-email-mismatch")));
        accept(viewer, token);
        mvc.perform(post("/api/v1/invitations/accept").with(viewer.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isGone());

        mvc.perform(get("/api/v1/workspaces").with(viewer.auth()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].id").value(owner.tenantId().toString()))
                .andExpect(jsonPath("$[1].role").value("VIEWER"));

        String ws = owner.tenantId().toString();
        mvc.perform(get("/api/v1/me").with(viewer.auth()).header("X-Workspace-Id", ws))
                .andExpect(jsonPath("$.tenant.id").value(ws))
                .andExpect(jsonPath("$.role").value("VIEWER"));
        mvc.perform(get("/api/v1/tenant/projects").with(viewer.auth()).header("X-Workspace-Id", ws))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenant/projects").with(viewer.auth()).header("X-Workspace-Id", ws)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Sneaky\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(endsWith("/insufficient-role")))
                .andExpect(jsonPath("$.required_role").value("ADMIN"))
                .andExpect(jsonPath("$.your_role").value("VIEWER"));
        mvc.perform(post("/api/v1/playground/completions").with(viewer.auth()).header("X-Workspace-Id", ws)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"hi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenant/projects").with(viewer.auth()).header("X-Workspace-Id", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        // the stranger is not a member at all
        mvc.perform(get("/api/v1/tenant/projects").with(stranger.auth()).header("X-Workspace-Id", ws))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(endsWith("/workspace-access-denied")));

        // the refusal is in the owner's audit log, attributed to the viewer
        mvc.perform(get("/api/v1/tenant/audit").with(owner.auth()))
                .andExpect(jsonPath("$.entries[0].action").value("POST /playground/completions"))
                .andExpect(jsonPath("$.entries[1].action").value("POST /tenant/projects"))
                .andExpect(jsonPath("$.entries[1].status_code").value(403))
                .andExpect(jsonPath("$.entries[1].actor_role").value("VIEWER"));
        mvc.perform(get("/api/v1/tenant/audit").with(viewer.auth()).header("X-Workspace-Id", ws))
                .andExpect(status().isForbidden());
        assertThat(count("select count(*) from audit_events where tenant_id = ? and actor_email = ?", owner.tenantId(),
                viewer.email())).isEqualTo(3); // two refused writes + the refused audit read
    }

    @Test
    @DisplayName("hierarchy: Admin manages only Developers/Viewers, nobody edits themselves, If-Match on role changes, removal revokes access at once")
    void roleHierarchyAndRemoval() throws Exception {
        Account owner = signUp();
        Account admin = signUp();
        Account dev = signUp();
        accept(admin, invite(owner, admin.email(), "ADMIN"));
        accept(dev, invite(owner, dev.email(), "VIEWER"));
        String ws = owner.tenantId().toString();

        // an Admin cannot hand out ADMIN/OWNER and cannot touch other Admins
        mvc.perform(post("/api/v1/tenant/members").with(admin.auth()).header("X-Workspace-Id", ws)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.com\",\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());
        MvcResult list = mvc.perform(get("/api/v1/tenant/members").with(admin.auth()).header("X-Workspace-Id", ws))
                .andExpect(jsonPath("$.your_role").value("ADMIN"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.members[0].primary_owner").value(true))
                .andReturn();
        String adminId = null;
        String devId = null;
        long devVersion = 0;
        for (JsonNode m : read(list).get("members")) {
            if (admin.email().equals(m.path("email").asText())) {
                adminId = m.get("id").asText();
            }
            if (dev.email().equals(m.path("email").asText())) {
                devId = m.get("id").asText();
                devVersion = m.get("version").asLong();
            }
        }
        mvc.perform(patch("/api/v1/tenant/members/" + adminId).with(admin.auth()).header("X-Workspace-Id", ws)
                        .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());

        // promote the viewer to developer: 428 without If-Match, 412 stale, 200 current
        mvc.perform(patch("/api/v1/tenant/members/" + devId).with(admin.auth()).header("X-Workspace-Id", ws)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"DEVELOPER\"}")).andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/tenant/members/" + devId).with(admin.auth()).header("X-Workspace-Id", ws)
                        .header("If-Match", "\"99\"").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(patch("/api/v1/tenant/members/" + devId).with(admin.auth()).header("X-Workspace-Id", ws)
                        .header("If-Match", "\"" + devVersion + "\"").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("DEVELOPER"));

        // the promotion is effective on the next request: a Developer may create keys but not projects
        mvc.perform(post("/api/v1/tenant/api-keys").with(dev.auth()).header("X-Workspace-Id", ws)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"dev key\"}")).andExpect(status().isCreated());
        mvc.perform(put("/api/v1/tenant/settings").with(admin.auth()).header("X-Workspace-Id", ws)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\",\"hard_budget_limit_usd\":100}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.required_role").value("OWNER"));

        // removal: access ends immediately (cache evicted after commit)
        mvc.perform(delete("/api/v1/tenant/members/" + devId).with(admin.auth()).header("X-Workspace-Id", ws))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tenant/projects").with(dev.auth()).header("X-Workspace-Id", ws))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/me").with(dev.auth()).header("X-Workspace-Id", ws))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace_fallback").value(true))
                .andExpect(jsonPath("$.role").value("OWNER"));

        // an Admin may leave on their own
        mvc.perform(delete("/api/v1/tenant/members/" + adminId).with(admin.auth()).header("X-Workspace-Id", ws))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/workspaces").with(admin.auth())).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("invitations: duplicate 409, resend replaces the token, expired → 410, audit log is append-only")
    void invitationLifecycle() throws Exception {
        Account owner = signUp();
        Account invitee = signUp();
        String first = invite(owner, invitee.email(), "DEVELOPER");
        mvc.perform(post("/api/v1/tenant/members").with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + invitee.email() + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/tenant/members").with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"role\":\"VIEWER\"}")).andExpect(status().isBadRequest());

        UUID memberId = jdbc.queryForObject("select id from tenant_members where tenant_id = ? and email = ?", UUID.class,
                owner.tenantId(), invitee.email());
        MvcResult resent = mvc.perform(post("/api/v1/tenant/members/" + memberId + "/resend").with(owner.auth()))
                .andExpect(status().isOk()).andReturn();
        String second = read(resent).get("token").asText();
        assertThat(second).isNotEqualTo(first);
        mvc.perform(post("/api/v1/invitations/accept").with(invitee.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + first + "\"}")).andExpect(status().isGone());

        jdbc.update("update tenant_members set invite_expires_at = now() - interval '1 minute' where id = ?", memberId);
        mvc.perform(post("/api/v1/invitations/accept").with(invitee.auth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + second + "\"}")).andExpect(status().isGone());

        mvc.perform(delete("/api/v1/tenant/members/" + memberId).with(owner.auth())).andExpect(status().isNoContent());
        assertThat(count("select count(*) from audit_events where tenant_id = ?", owner.tenantId())).isGreaterThanOrEqualTo(4);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("delete from audit_events where tenant_id = ?", owner.tenantId()))
                .rootCause().hasMessageContaining("append-only");
    }
}

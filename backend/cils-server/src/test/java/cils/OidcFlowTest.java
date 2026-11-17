package cils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@SpringBootTest(classes = FlowApplication.class)
@AutoConfigureMockMvc
@DisplayName("CILS 문서 기반 OIDC / RBAC / 로그아웃 흐름")
class OidcFlowTest {
    static final String VERIFIER = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ-._~";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtDecoder decoder;
    @Autowired FlowSessions sessions;
    @Autowired OAuth2AuthorizationService grants;
    @Autowired JwtEncoder encoder;

    MockHttpSession login(String user) throws Exception {
        var result = mvc.perform(post("/login").with(csrf()).param("username", user)
                        .param("password", "test-password"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("error");
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorization(String client) throws Exception {
        return authorization(client, FlowSecurity.callback(client), "openid");
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorization(String client, String redirectUri, String scope) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return get("/oauth2/authorize").accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code").queryParam("client_id", client)
                .queryParam("redirect_uri", redirectUri).queryParam("scope", scope)
                .queryParam("state", "test-state").queryParam("nonce", "test-nonce")
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256");
    }

    String code(MockHttpSession session, String client) throws Exception {
        String redirect = mvc.perform(authorization(client).session(session))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var params = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams();
        assertThat(params.getFirst("error")).isNull();
        assertThat(params.getFirst("state")).isEqualTo("test-state");
        assertThat(params.getFirst("code")).isNotBlank();
        return params.getFirst("code");
    }

    ResultActions exchange(String code, String client, String verifier) throws Exception {
        return mvc.perform(post("/oauth2/token").with(httpBasic(client, "test-secret"))
                .param("grant_type", "authorization_code").param("code", code)
                .param("redirect_uri", FlowSecurity.callback(client)).param("code_verifier", verifier));
    }

    JsonNode tokens(MockHttpSession session, String client) throws Exception {
        return json.readTree(exchange(code(session, client), client, VERIFIER)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test @DisplayName("S1/S5: 실제 로그인 → 코드 → PKCE 교환 → 서명된 ID/Access Token")
    void firstLoginAndTokenExchange() throws Exception {
        var request = authorization("STUDENT_PORTAL");
        var initial = mvc.perform(request).andExpect(redirectedUrl("http://localhost/login")).andReturn();
        var session = (MockHttpSession) initial.getRequest().getSession(false);
        String beforeLogin = session.getId();
        mvc.perform(post("/login").session(session).with(csrf()).param("username", "student")
                        .param("password", "test-password"))
                .andExpect(status().is3xxRedirection());
        assertThat(session.getId()).isNotEqualTo(beforeLogin); // session fixation protection
        JsonNode response = tokens(session, "STUDENT_PORTAL");
        Jwt id = decoder.decode(response.get("id_token").asText());
        assertThat(id.getIssuer().toString()).isEqualTo(FlowSecurity.ISSUER);
        assertThat(id.getAudience()).containsExactly("STUDENT_PORTAL");
        assertThat(id.getSubject()).isEqualTo("student");
        assertThat(id.getExpiresAt()).isAfter(Instant.now());
        assertThat(id.getClaimAsString("nonce")).isEqualTo("test-nonce");
        assertThat(id.getClaimAsString("sid")).isEqualTo(session.getId());
        assertThat(id.getClaims()).doesNotContainKeys("roles", "permissions");
        Jwt access = decoder.decode(response.get("access_token").asText());
        assertThat(access.getClaimAsString("sid")).isEqualTo(session.getId());
        assertThat(response.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(response.get("refresh_token").asText()).isNotBlank();
        mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk()).andExpect(jsonPath("$.keys[0].kty").value("RSA"));
    }

    static Stream<Arguments> policyMatrix() {
        return Stream.of("student", "professor", "staff", "admin").flatMap(user ->
                FlowSecurity.PORTALS.stream().map(portal -> Arguments.of(user, portal,
                        user.equals("admin") || portal.equals(user.toUpperCase(Locale.ROOT) + "_PORTAL"))));
    }
    @ParameterizedTest(name = "{0} → {1}: allowed={2}") @MethodSource("policyMatrix")
    @DisplayName("ADR-001: 4 Role × 3 포털의 코드 발급 허용/거부")
    void portalAccessPolicy(String user, String portal, boolean allowed) throws Exception {
        var session = login(user);
        if (allowed) {
            code(session, portal);
        } else {
            var result = mvc.perform(authorization(portal).session(session)).andReturn();
            String redirect = result.getResponse().getRedirectedUrl();
            assertThat(redirect).contains("error=access_denied").doesNotContain("code=");
        }
    }

    @Test @DisplayName("S2/S4: ADMIN은 동일 SSO 세션으로 세 포털에 재인증 없이 로그인")
    void reusesSessionAcrossPortals() throws Exception {
        var session = login("admin");
        Set<String> accessTokens = new HashSet<>();
        for (String portal : FlowSecurity.PORTALS) {
            var response = tokens(session, portal);
            assertThat(decoder.decode(response.get("id_token").asText()).getClaimAsString("sid"))
                    .isEqualTo(session.getId());
            accessTokens.add(response.get("access_token").asText());
        }
        assertThat(accessTokens).hasSize(3);
    }

    @ParameterizedTest @ValueSource(strings = {"wrong-password", "disabled-account"})
    @DisplayName("FR-001: 잘못된 비밀번호 / 비활성 계정은 세션 인증 실패")
    void rejectsBadCredentials(String scenario) throws Exception {
        var result = mvc.perform(post("/login").with(csrf())
                        .param("username", scenario.equals("disabled-account") ? "disabled" : "student")
                        .param("password", scenario.equals("disabled-account") ? "test-password" : "wrong"))
                .andExpect(redirectedUrl("/login?error")).andReturn();
        var session = result.getRequest().getSession(false);
        if (session != null) assertThat(sessions.active).doesNotContainKey(session.getId());
    }

    @Test @DisplayName("FR-003: 등록되지 않은 client / redirect URI / scope / 누락 PKCE 거부")
    void rejectsInvalidAuthorizationRequests() throws Exception {
        var session = login("student");
        mvc.perform(authorization("UNKNOWN").session(session)).andExpect(status().is4xxClientError());
        var badRedirect = mvc.perform(authorization("STUDENT_PORTAL", "https://attacker.example/callback", "openid").session(session))
                .andReturn().getResponse();
        // An unregistered URI must never receive an authorization response.
        assertThat(badRedirect.getRedirectedUrl()).isNull();
        var badScope = mvc.perform(authorization("STUDENT_PORTAL", FlowSecurity.callback("STUDENT_PORTAL"), "forbidden").session(session)).andReturn().getResponse();
        assertThat(badScope.getRedirectedUrl()).contains("error=invalid_scope").doesNotContain("code=");
        mvc.perform(get("/oauth2/authorize").session(session).queryParam("response_type", "code")
                        .queryParam("client_id", "STUDENT_PORTAL").queryParam("redirect_uri", FlowSecurity.callback("STUDENT_PORTAL"))
                        .queryParam("scope", "openid"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error=invalid_request")));
    }

    @Test @DisplayName("FR-005: Authorization Code 재사용 거부")
    void rejectsCodeReuse() throws Exception {
        String code = code(login("student"), "STUDENT_PORTAL");
        exchange(code, "STUDENT_PORTAL", VERIFIER).andExpect(status().isOk());
        exchange(code, "STUDENT_PORTAL", VERIFIER).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test @DisplayName("FR-005: 잘못된 PKCE / 다른 클라이언트 / 잘못된 client secret 거부")
    void rejectsInvalidTokenExchange() throws Exception {
        var session = login("student");
        exchange(code(session, "STUDENT_PORTAL"), "STUDENT_PORTAL", "wrong-verifier")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        exchange(code(session, "STUDENT_PORTAL"), "PROFESSOR_PORTAL", VERIFIER)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(post("/oauth2/token").with(httpBasic("STUDENT_PORTAL", "wrong"))
                        .param("grant_type", "authorization_code").param("code", code(session, "STUDENT_PORTAL"))
                        .param("redirect_uri", FlowSecurity.callback("STUDENT_PORTAL")).param("code_verifier", VERIFIER))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test @DisplayName("FR-004/005: 만료한 코드 거부 (저장소 시각을 변경하여 대기 없이 검증)")
    void rejectsExpiredCode() throws Exception {
        String code = code(login("student"), "STUDENT_PORTAL");
        var grant = grants.findByToken(code, new OAuth2TokenType("code"));
        var expired = new OAuth2AuthorizationCode(code, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
        grants.save(OAuth2Authorization.from(grant).token(expired).build());
        exchange(code, "STUDENT_PORTAL", VERIFIER).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test @DisplayName("통합 DCD: 로그아웃은 HTTP 세션·grant 폐기, 참여 앱 통지, 다른 세션 보존")
    void centralLogoutRevokesOnlyThisSession() throws Exception {
        var session = login("admin");
        String sid = session.getId();
        JsonNode first = tokens(session, "STUDENT_PORTAL");
        tokens(session, "PROFESSOR_PORTAL");
        String unusedCode = code(session, "STAFF_PORTAL");
        var otherSession = login("admin");
        JsonNode other = tokens(otherSession, "STUDENT_PORTAL");
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
        assertThat(sessions.active).doesNotContainKey(sid).containsKey(otherSession.getId());
        assertThat(grants.findByToken(first.get("access_token").asText(), OAuth2TokenType.ACCESS_TOKEN)).isNull();
        assertThat(grants.findByToken(other.get("access_token").asText(), OAuth2TokenType.ACCESS_TOKEN)).isNotNull();
        exchange(unusedCode, "STAFF_PORTAL", VERIFIER).andExpect(status().isBadRequest());
        mvc.perform(post("/oauth2/token").with(httpBasic("STUDENT_PORTAL", "test-secret"))
                        .param("grant_type", "refresh_token").param("refresh_token", first.get("refresh_token").asText()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        mvc.perform(authorization("STUDENT_PORTAL")).andExpect(redirectedUrl("http://localhost/login"));
        // The DCD explicitly states: offline JWT signature validation survives grant revocation.
        assertThat(decoder.decode(first.get("access_token").asText()).getSubject()).isEqualTo("admin");
        var notifications = sessions.outbox.stream().filter(n -> n.sid.equals(sid)).toList();
        assertThat(notifications).extracting(n -> n.clientId).containsExactlyInAnyOrderElementsOf(FlowSecurity.PORTALS);
        for (var notification : notifications) {
            var logout = decoder.decode(notification.token);
            assertThat(logout.getIssuer().toString()).isEqualTo(FlowSecurity.ISSUER);
            assertThat(logout.getAudience()).containsExactly(notification.clientId);
            assertThat(logout.getId()).isNotBlank();
            assertThat(logout.getIssuedAt()).isNotNull();
            assertThat(logout.getClaimAsString("sid")).isEqualTo(sid);
            assertThat(logout.getClaimAsMap("events")).containsKey("http://schemas.openid.net/event/backchannel-logout");
            assertThat(logout.getClaims()).doesNotContainKey("nonce");
        }
        sessions.invalidate(sid);
        assertThat(sessions.audit.stream().filter(value -> value.equals("INVALIDATED:" + sid)).count()).isEqualTo(1);
        assertThat(sessions.outbox.stream().filter(n -> n.sid.equals(sid)).count()).isEqualTo(3);
    }

    @Test @DisplayName("Security: 로그인/로그아웃 POST에는 CSRF 검증 적용")
    void csrfProtection() throws Exception {
        mvc.perform(post("/login").param("username", "student").param("password", "test-password"))
                .andExpect(status().isForbidden());
        var session = login("student");
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test @DisplayName("통합 DCD: Back-channel Logout은 등록된 앱에 POST하고 성공 후 재전송하지 않음")
    void sendsLogoutNotification() {
        var local = new FlowSessions(new FlowAuthorizationStore(), encoder);
        var server = MockRestServiceServer.bindTo(local.http).build();
        Instant now = Instant.now();
        var notification = new FlowSessions.Notification("STUDENT_PORTAL", "test-sid", "signed-logout-token", now);
        local.outbox.add(notification);
        server.expect(requestTo(FlowSessions.logoutUri("STUDENT_PORTAL")))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .string("logout_token=signed-logout-token"))
                .andRespond(withSuccess());
        local.dispatch(now);
        local.dispatch(now.plusSeconds(10));
        assertThat(notification.status).isEqualTo("SENT");
        assertThat(notification.attempts).isEqualTo(1);
        server.verify();
    }

    @Test @DisplayName("통합 DCD: 실패 시 10초 → 1분 → 5분 → 5분 재시도, 5회 실패 후 알림")
    void retriesFailedLogoutNotificationWithoutSleeping() {
        var local = new FlowSessions(new FlowAuthorizationStore(), encoder);
        var server = MockRestServiceServer.bindTo(local.http).build();
        Instant now = Instant.now();
        var notification = new FlowSessions.Notification("STUDENT_PORTAL", "test-sid", "signed-logout-token", now);
        local.outbox.add(notification);
        server.expect(org.springframework.test.web.client.ExpectedCount.times(5),
                requestTo(FlowSessions.logoutUri("STUDENT_PORTAL"))).andRespond(withServerError());
        long[] offsets = {0, 10, 70, 370, 670};
        for (int i = 0; i < offsets.length; i++) {
            local.dispatch(now.plusSeconds(offsets[i]));
            assertThat(notification.attempts).isEqualTo(i + 1);
            if (i < 4) {
                assertThat(notification.nextRetryAt).isEqualTo(now.plusSeconds(offsets[i + 1]));
                local.dispatch(notification.nextRetryAt.minusSeconds(1));
                assertThat(notification.attempts).isEqualTo(i + 1);
            }
        }
        assertThat(notification.status).isEqualTo("FAILED");
        assertThat(local.alerts).containsExactly("STUDENT_PORTAL");
        local.dispatch(now.plusSeconds(1000));
        server.verify();
    }
}

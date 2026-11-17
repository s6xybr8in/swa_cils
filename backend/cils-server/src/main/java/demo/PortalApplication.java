package demo;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpSession;
import cils.DemoPages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

/** Same small demo client is started on three ports, with independent sessions. */
@SpringBootConfiguration
@EnableAutoConfiguration
@RestController
public class PortalApplication {
    private static final String ISSUER = "http://localhost:9000";
    @Value("${demo.client-id}") String clientId;
    private final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();
    private final Set<String> received = ConcurrentHashMap.newKeySet();
    private final JwtDecoder logoutTokens = logoutDecoder();

    @Bean ClientRegistrationRepository registrations() {
        var registration = ClientRegistration.withRegistrationId(clientId).clientId(clientId)
                .clientSecret("test-secret").clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").scope("openid")
                .authorizationUri(ISSUER + "/oauth2/authorize").tokenUri(ISSUER + "/oauth2/token")
                .jwkSetUri(ISSUER + "/oauth2/jwks").issuerUri(ISSUER)
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build()).build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean SecurityFilterChain portalSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/", "/backchannel-logout", "/error").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/backchannel-logout"))
                .oauth2Login(login -> login
                        .successHandler((request, response, authentication) -> {
                            var user = (OidcUser) authentication.getPrincipal();
                            sessions.put(user.getClaimAsString("sid"), request.getSession());
                            response.sendRedirect("/portal");
                        })
                        .failureHandler((request, response, failure) -> response.sendRedirect("/?denied")));
        return http.build();
    }

    private JwtDecoder logoutDecoder() {
        var decoder = NimbusJwtDecoder.withJwkSetUri(ISSUER + "/oauth2/jwks").build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    String home(Authentication authentication, @RequestParam(name = "denied", required = false) String denied) {
        String error = denied == null ? "" : "<p class='error'>로그인에 실패했습니다. 현재 계정의 포털 접근 권한을 확인하세요.</p>";
        String status = authentication instanceof OAuth2AuthenticationToken ? "이 포털에 로그인되어 있습니다." : "이 포털은 로그인 전입니다.";
        return DemoPages.page(DemoPages.label(clientId), "<p class='eyebrow'>DEMO CLIENT · " + clientId
                + "</p><h1>" + DemoPages.label(clientId) + "</h1>" + error + "<p class='status'>" + status + "</p>"
                + "<p><a class='button' href='/oauth2/authorization/" + clientId + "'>중앙 인증 서버로 로그인</a> "
                + "<a href='/portal'>로그인 결과 보기</a></p><p class='muted'>이미 중앙 세션이 있으면 비밀번호를 다시 입력하지 않습니다."
                + " 포털 접근 권한은 새 요청마다 확인합니다.</p>");
    }

    @GetMapping(value = "/portal", produces = "text/html;charset=UTF-8")
    String portal(Authentication authentication) {
        var user = (OidcUser) authentication.getPrincipal();
        return DemoPages.page(DemoPages.label(clientId), "<p class='eyebrow'>LOGIN COMPLETE</p><h1>"
                + DemoPages.label(clientId) + " 로그인 완료</h1><p class='status'>코드·PKCE 교환과 ID Token 검증이 완료되었습니다.</p>"
                + "<dl><dt>사용자</dt><dd>" + DemoPages.escape(user.getSubject())
                + "</dd><dt>중앙 세션 sid</dt><dd>" + DemoPages.escape(user.getClaimAsString("sid"))
                + "</dd><dt>발급자</dt><dd>" + DemoPages.escape(user.getIssuer())
                + "</dd><dt>대상</dt><dd>" + DemoPages.escape(user.getAudience())
                + "</dd><dt>만료 시각</dt><dd>" + DemoPages.escape(user.getExpiresAt()) + "</dd></dl>"
                + "<p><a class='button' href='http://localhost:9000'>다른 포털 열기</a> "
                + "<a href='http://localhost:9000/logout'>중앙 로그아웃</a></p>"
                + "<p class='muted'>중앙 로그아웃 후 이 페이지를 새로고침해 포털 세션 종료를 확인하세요.</p>");
    }

    @PostMapping("/backchannel-logout")
    ResponseEntity<Void> backchannel(@RequestParam("logout_token") String token) {
        // Validated signed message, not a browser request: no CSRF token is used here.
        try {
            Jwt jwt = logoutTokens.decode(token);
            String sid = jwt.getClaimAsString("sid");
            if (!jwt.getAudience().equals(List.of(clientId)) || jwt.getId() == null || sid == null
                    || jwt.getIssuedAt() == null || jwt.getIssuedAt().isBefore(Instant.now().minusSeconds(300))
                    || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(60)) || jwt.hasClaim("nonce")
                    || !jwt.getClaimAsMap("events").containsKey("http://schemas.openid.net/event/backchannel-logout")) {
                return ResponseEntity.badRequest().build();
            }
            if (received.add(jwt.getId())) {
                var session = sessions.remove(sid);
                if (session != null) {
                    try { session.invalidate(); } catch (IllegalStateException alreadyEnded) { /* idempotent */ }
                }
            }
            return ResponseEntity.ok().build();
        } catch (JwtException | IllegalArgumentException | NullPointerException invalid) {
            return ResponseEntity.badRequest().build();
        }
    }
}

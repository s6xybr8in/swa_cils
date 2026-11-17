package cils;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import static org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR;
import static org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR;

@Configuration(proxyBeanMethods = false)
class FlowSecurity {
    static final String ISSUER = "http://localhost";
    static final List<String> PORTALS = List.of("STUDENT_PORTAL", "PROFESSOR_PORTAL", "STAFF_PORTAL");
    @Value("${cils.demo:false}") boolean demo;
    @Value("${cils.issuer:http://localhost}") String issuer;

    static int portalPort(String portal) { return 8081 + PORTALS.indexOf(portal); }

    @Bean PasswordEncoder passwordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean UserDetailsService users(PasswordEncoder encoder) {
        var users = new InMemoryUserDetailsManager();
        for (String role : List.of("STUDENT", "PROFESSOR", "STAFF", "ADMIN")) {
            users.createUser(User.withUsername(role.toLowerCase(Locale.ROOT))
                    .password(encoder.encode("test-password")).roles(role).build());
        }
        users.createUser(User.withUsername("disabled").password(encoder.encode("test-password"))
                .roles("STUDENT").disabled(true).build());
        return users;
    }

    @Bean RegisteredClientRepository clients(PasswordEncoder encoder) {
        return new InMemoryRegisteredClientRepository(PORTALS.stream().map(portal ->
                RegisteredClient.withId(portal).clientId(portal)
                        .clientSecret(encoder.encode("test-secret"))
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                        .redirectUri(demo ? "http://localhost:" + portalPort(portal) + "/login/oauth2/code/" + portal : callback(portal)).scope("openid")
                        .clientSettings(ClientSettings.builder().requireProofKey(true)
                                .requireAuthorizationConsent(false)
                                .setting("backchannel_logout_uri", demo
                                        ? "http://localhost:" + portalPort(portal) + "/backchannel-logout"
                                        : FlowSessions.logoutUri(portal)).build())
                        .tokenSettings(TokenSettings.builder().reuseRefreshTokens(false).build()).build()).toList());
    }

    static String callback(String portal) { return "https://" + portal.toLowerCase(Locale.ROOT) + ".example/callback"; }

    @Bean OAuth2AuthorizationService authorizations() { return new FlowAuthorizationStore(); }
    @Bean AuthorizationServerSettings endpoints() { return AuthorizationServerSettings.builder().issuer(issuer).build(); }
    @Bean FlowSessions sessions(OAuth2AuthorizationService authorizations, JwtEncoder encoder, RegisteredClientRepository clients) {
        var sessions = new FlowSessions(authorizations, encoder);
        sessions.issuer = issuer;
        sessions.logoutAddress = client -> clients.findByClientId(client).getClientSettings().getSetting("backchannel_logout_uri");
        return sessions;
    }

    @Bean @Order(1)
    SecurityFilterChain authorizationChain(HttpSecurity http) throws Exception {
        var server = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(server.getEndpointsMatcher())
                .with(server, config -> config.oidc(Customizer.withDefaults())
                        .authorizationEndpoint(endpoint -> endpoint.authenticationProviders(FlowSecurity::applyPortalPolicy)))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    private static void applyPortalPolicy(List<org.springframework.security.authentication.AuthenticationProvider> providers) {
        for (var provider : providers) {
            if (!(provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider)) continue;
            // Keep framework validation, then apply ADR-001 before issuing a code.
            codeProvider.setAuthenticationValidator(
                    DEFAULT_REDIRECT_URI_VALIDATOR.andThen(DEFAULT_SCOPE_VALIDATOR)
                    .andThen(context -> {
                        OAuth2AuthorizationCodeRequestAuthenticationToken request = context.getAuthentication();
                        if (!(request.getPrincipal() instanceof Authentication principal)
                                || !principal.isAuthenticated()
                                || principal instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) return;
                        String portal = context.getRegisteredClient().getClientId();
                        boolean allowed = principal.getAuthorities().stream().anyMatch(authority ->
                                authority.getAuthority().equals("ROLE_ADMIN")
                                || (authority.getAuthority() + "_PORTAL").equals("ROLE_" + portal));
                        if (!allowed) throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                                new OAuth2Error("access_denied"), request);
                    }));
        }
    }

    @Bean @Order(2)
    SecurityFilterChain loginChain(HttpSecurity http, FlowSessions sessions) throws Exception {
        var savedRequest = new SavedRequestAwareAuthenticationSuccessHandler();
        http.authorizeHttpRequests(auth -> {
                    if (demo) auth.requestMatchers("/").permitAll();
                    auth.anyRequest().authenticated();
                })
                .formLogin(form -> form.successHandler((request, response, authentication) -> {
                    sessions.create(request.getSession(), authentication.getName());
                    savedRequest.onAuthenticationSuccess(request, response, authentication);
                }))
                .logout(logout -> logout.addLogoutHandler((request, response, authentication) -> {
                    HttpSession session = request.getSession(false);
                    if (session != null) sessions.invalidate(session.getId());
                }));
        return http.build();
    }

    @Bean OAuth2TokenCustomizer<JwtEncodingContext> sessionClaims(FlowSessions sessions) {
        return context -> {
            if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())
                    && !AuthorizationGrantType.REFRESH_TOKEN.equals(context.getAuthorizationGrantType())) return;
            String sid = sessions.bind(context.getAuthorization(), context.getPrincipal().getName(),
                    context.getRegisteredClient().getClientId());
            context.getClaims().claim("sid", sid);
        };
    }

    @Bean JWKSource<SecurityContext> keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(UUID.randomUUID().toString()).build();
        var set = new JWKSet(key);
        return (selector, context) -> selector.select(set);
    }
    @Bean JwtEncoder encoder(JWKSource<SecurityContext> keys) { return new NimbusJwtEncoder(keys); }
    @Bean JwtDecoder decoder(JWKSource<SecurityContext> keys) { return OAuth2AuthorizationServerConfiguration.jwtDecoder(keys); }
}

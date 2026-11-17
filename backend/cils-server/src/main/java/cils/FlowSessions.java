package cils;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.web.client.RestClient;

/** In-memory implementation for tests and the local browser demo. */
class FlowSessions {
    record Session(HttpSession http, String subject) {}
    static class Notification {
        final String clientId, sid, token;
        int attempts;
        Instant nextRetryAt;
        String status = "PENDING";
        Notification(String clientId, String sid, String token, Instant now) {
            this.clientId = clientId; this.sid = sid; this.token = token; this.nextRetryAt = now;
        }
    }
    final Map<String, Session> active = new ConcurrentHashMap<>();
    final List<Notification> outbox = new ArrayList<>();
    final List<String> alerts = new ArrayList<>();
    final List<String> audit = new ArrayList<>();
    private final FlowAuthorizationStore store;
    private final JwtEncoder encoder;
    final RestClient.Builder http = RestClient.builder();
    String issuer = FlowSecurity.ISSUER;
    java.util.function.Function<String, String> logoutAddress = FlowSessions::logoutUri;

    FlowSessions(OAuth2AuthorizationService store, JwtEncoder encoder) {
        this.store = (FlowAuthorizationStore) store; this.encoder = encoder;
    }
    void create(HttpSession session, String subject) { active.put(session.getId(), new Session(session, subject)); }

    String bind(OAuth2Authorization grant, String subject, String clientId) {
        String sid = store.grantSessions.get(grant.getId());
        Session session = sid == null ? null : active.get(sid);
        if (session == null || !session.subject().equals(subject)) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_grant"));
        }
        return sid;
    }

    synchronized void invalidate(String sid) {
        Session session = active.remove(sid);
        if (session == null) return;
        new HashMap<>(store.grantSessions).forEach((id, sessionId) -> {
            if (sid.equals(sessionId)) {
                var grant = store.findById(id);
                if (grant != null) store.remove(grant);
            }
        });
        Instant now = Instant.now();
        for (String client : store.participants.getOrDefault(sid, Set.of())) {
            var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(client))
                    .issuedAt(now).id(UUID.randomUUID().toString()).subject(session.subject())
                    .claim("sid", sid).claim("events", Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of())).build();
            String token = encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
            outbox.add(new Notification(client, sid, token, now));
        }
        audit.add("INVALIDATED:" + sid);
        try { session.http().invalidate(); } catch (IllegalStateException alreadyInvalidated) { /* idempotent */ }
    }

    synchronized void dispatch(Instant now) {
        for (Notification notification : outbox) {
            if (!notification.status.equals("PENDING") || notification.nextRetryAt.isAfter(now)) continue;
            notification.attempts++;
            try {
                http.build().post().uri(logoutAddress.apply(notification.clientId))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body("logout_token=" + notification.token).retrieve().toBodilessEntity();
                notification.status = "SENT";
            } catch (org.springframework.web.client.RestClientException failure) {
                if (notification.attempts == 5) {
                    notification.status = "FAILED";
                    alerts.add(notification.clientId);
                } else {
                    long delay = switch (notification.attempts) { case 1 -> 10; case 2 -> 60; default -> 300; };
                    notification.nextRetryAt = now.plusSeconds(delay);
                }
            }
        }
    }
    static String logoutUri(String client) { return "https://" + client.toLowerCase(Locale.ROOT) + ".example/backchannel-logout"; }
}

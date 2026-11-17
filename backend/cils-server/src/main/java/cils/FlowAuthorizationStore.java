package cils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.web.context.request.*;

/** In-memory stand-in for the DCD's authorization store and GrantSessionIndex. */
class FlowAuthorizationStore implements OAuth2AuthorizationService {
    private final OAuth2AuthorizationService delegate = new InMemoryOAuth2AuthorizationService();
    final Map<String, String> grantSessions = new ConcurrentHashMap<>();
    final Map<String, Set<String>> participants = new ConcurrentHashMap<>();

    public void save(OAuth2Authorization authorization) {
        String sid = authorization.getAttribute("cils.sid");
        if (sid == null && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var session = attributes.getRequest().getSession(false);
            if (session != null) sid = session.getId();
        }
        if (sid != null) {
            authorization = OAuth2Authorization.from(authorization).attribute("cils.sid", sid).build();
            grantSessions.put(authorization.getId(), sid);
            participants.computeIfAbsent(sid, ignored -> ConcurrentHashMap.newKeySet())
                    .add(authorization.getRegisteredClientId());
        }
        delegate.save(authorization);
    }
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
        grantSessions.remove(authorization.getId());
    }
    public OAuth2Authorization findById(String id) { return delegate.findById(id); }
    public OAuth2Authorization findByToken(String token, OAuth2TokenType type) { return delegate.findByToken(token, type); }
}

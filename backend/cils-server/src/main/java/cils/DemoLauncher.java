package cils;

import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import demo.PortalApplication;

/** One command, four independent HTTP applications and cookie/session stores. */
public class DemoLauncher {
    public static void main(String[] args) {
        var auth = new SpringApplicationBuilder(AuthApplication.class).properties(Map.of(
                "server.port", "9000", "server.address", "127.0.0.1",
                "server.servlet.session.cookie.name", "CILS_SESSION",
                "spring.application.name", "cils-demo", "cils.demo", "true",
                "cils.issuer", "http://localhost:9000")).run(args);
        var running = new ArrayList<ConfigurableApplicationContext>();
        running.add(auth);
        try {
            for (String portal : FlowSecurity.PORTALS) {
                running.add(new SpringApplicationBuilder(PortalApplication.class).properties(Map.of(
                        "server.port", FlowSecurity.portalPort(portal), "server.address", "127.0.0.1",
                        "server.servlet.session.cookie.name", portal + "_SESSION",
                        "spring.application.name", portal, "demo.client-id", portal)).run(args));
            }
            System.out.println("\nOpen http://localhost:9000 in your browser. Stop: Ctrl+C\n");
        } catch (RuntimeException failure) {
            running.reversed().forEach(ConfigurableApplicationContext::close);
            throw failure;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableScheduling
    @Import({FlowSecurity.class, AuthHome.class})
    static class AuthApplication {}

    @RestController
    static class AuthHome {
        private final FlowSessions sessions;
        AuthHome(FlowSessions sessions) { this.sessions = sessions; }

        @Scheduled(fixedDelay = 1000)
        void sendLogoutNotifications() { sessions.dispatch(Instant.now()); }

        @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
        String home(Authentication authentication, CsrfToken csrf) {
            boolean loggedIn = authentication != null && authentication.isAuthenticated();
            String links = "";
            for (String portal : FlowSecurity.PORTALS) {
                links += "<a class='card' href='http://localhost:" + FlowSecurity.portalPort(portal)
                        + "'>" + DemoPages.label(portal) + " <span>열기 →</span></a>";
            }
            String logout = loggedIn ? "<form method='post' action='/logout'><input type='hidden' name='"
                    + csrf.getParameterName() + "' value='" + csrf.getToken()
                    + "'><button>중앙 로그아웃</button></form>" : "";
            return DemoPages.page("CILS 중앙 인증 서버", """
                    <p class='eyebrow'>LOCAL DEMO · CENTRAL AUTHENTICATION</p>
                    <h1>통합 로그인 흐름을 직접 확인하세요</h1>
                    <p>포털에서 로그인을 시작하면 이 인증 서버로 이동합니다.</p>
                    """ + "<p class='status'>중앙 로그인: " + (loggedIn ? DemoPages.escape(authentication.getName()) : "로그인 전")
                    + "</p><div class='cards'>" + links + "</div>" + logout + """
                    <h2>이렇게 확인하세요</h2>
                    <ol><li>학생포털을 열고 <strong>student</strong>로 로그인합니다.</li>
                    <li>교수포털을 열어 같은 학생 계정의 접근이 거부되는지 확인합니다.</li>
                    <li>중앙 로그아웃 후 <strong>admin</strong>으로 로그인합니다.</li>
                    <li>다른 포털을 열면 비밀번호 입력 없이 로그인됩니다.</li>
                    <li>중앙 로그아웃 후 각 포털을 새로고침하면 로그인 상태가 종료됩니다.</li></ol>
                    <p>계정: <code>student</code>, <code>professor</code>, <code>staff</code>, <code>admin</code><br>
                    공통 비밀번호: <code>test-password</code></p>
                    <p class='muted'>서버를 종료하면 메모리의 계정 외 세션·토큰·통지 기록은 초기화됩니다.</p>
                    """);
        }
    }
}

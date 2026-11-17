# CILS 흐름 확인용 데모와 자동 테스트

Java 21 / Spring Boot 3.5.8 / Spring Security / Spring Authorization Server를 사용한다.
문서의 동작을 직접 확인하는 로컬 데모다. 운영용 서버 구현은 아니다.
Redis, DB, Docker는 필요하지 않다.

## 브라우저에서 직접 실행

프로젝트 루트의 PowerShell에서 실행한다.

```powershell
cd backend/cils-server
.\gradlew.bat --gradle-user-home .gradle-user-home runDemo
```

터미널을 켜 둔 상태에서 **http://localhost:9000** 에 접속한다. 종료는 `Ctrl+C`다.
다음 네 서버가 한 프로세스에서 시작된다. 각 서버의 HTTP 세션과 쿠키는 독립적이다.

| 화면 | 주소 |
|---|---|
| 중앙 인증 서버 / 시작 화면 | http://localhost:9000 |
| 학생포털 | http://localhost:8081 |
| 교수포털 | http://localhost:8082 |
| 교직원포털 | http://localhost:8083 |

1. 학생포털 → 중앙 인증 서버로 로그인 → `student` / `test-password` 입력.
2. 완료 화면에서 사용자, `sid`, 발급자, 대상, 만료 시각 확인.
3. 교수포털에서 로그인을 누르면 학생의 접근이 거부됨.
4. 중앙 홈에서 중앙 로그아웃 후 `admin` / `test-password`로 학생포털 로그인.
5. 교수·교직원포털에서 로그인을 누르면 비밀번호 입력 없이 완료됨. 각 완료 화면의 `sid`는 같음.
6. 중앙 로그아웃 후 잠시 기다리고 각 포털을 새로고침하면 로그인 상태가 종료됨.

주소는 모두 `localhost`로 사용한다. `127.0.0.1`과 섞어 쓰면 등록된 callback 주소·쿠키가 달라진다.
로그인 화면과 로그아웃 확인 화면은 Spring Security의 기본 화면을 사용한다.
화면의 포털 버튼은 실제 OIDC Authorization Code + PKCE 흐름을 수행한다.

## 데모 코드 읽는 순서

1. `src/main/java/cils/DemoLauncher.java`: 인증 서버·포털 세 개 실행, 중앙 홈, 로그아웃 통지 전송.
2. `src/main/java/cils/FlowSecurity.java`: 로그인과 ADR-001 포털 인가, 클라이언트 등록, JWT.
3. `src/main/java/demo/PortalApplication.java`: 같은 코드를 세 포털에서 재사용. OAuth2 로그인, 결과 화면, Back-channel Logout 수신.
4. `src/main/java/cils/FlowSessions.java`: 중앙 세션과 grant 폐기, 로그아웃 토큰과 재시도.

개발자 도구의 Network에서 Preserve log를 켜면 리다이렉트를 볼 수 있다.
토큰 교환은 포털 서버가 인증 서버에 보내는 요청이라 브라우저 Network에는 나오지 않는다.

## 자동 테스트 실행

PowerShell에서 이 디렉터리로 이동한 뒤 실행한다.

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home test
```

최초 실행에는 Gradle과 Maven Central 의존성 다운로드가 필요하다.
결과는 콘솔 및 `build/reports/tests/test/index.html`에서 확인한다.

## 읽는 순서

1. `OidcFlowTest.java`: 요청 → 응답 → 상태 검증. 한 테스트씩 실행하며 흐름을 확인한다.
2. `FlowSecurity.java`: 두 SecurityFilterChain, 테스트 계정·클라이언트, ADR의 포털 인가, JWT 서명 설정.
3. `FlowAuthorizationStore.java`: 메모리 grant 저장과 grant ↔ HTTP 세션 연결.
4. `FlowSessions.java`: 메모리 세션, 로그아웃 시 grant 폐기, 통지 생성·재시도.

로그인, 코드 발급, PKCE, 토큰 교환은 MockMvc가 실제 Spring Security 필터를 통과한다.
인증 성공을 가정하는 `@WithMockUser`로 로그인 절차를 생략하지 않는다.
외부 앱에 보내는 HTTP 요청만 MockRestServiceServer로 대체한다.

## 설계 기준과 검증 범위

| 기준 | 확인하는 동작 |
|---|---|
| ADR-001 | STUDENT/PROFESSOR/STAFF는 각 포털만 허용, ADMIN은 세 포털 허용. 총 12개 조합 |
| FR-001, 로그인 시퀀스 | 정상 로그인, 잘못된 비밀번호, 비활성 계정, 로그인 시 세션 ID 변경 |
| FR-002, FR-008 / S2, S4 | 같은 중앙 세션으로 여러 포털에 재로그인 없이 코드·토큰 발급 |
| FR-003 | 미등록 클라이언트, 등록되지 않은 redirect URI, 허용되지 않은 scope, 누락 PKCE 거부 |
| FR-004~006 / S1, S5 | 코드·PKCE 교환, 코드 재사용/만료/다른 클라이언트/잘못된 secret 거부, 서명된 JWT와 JWKS |
| 통합 DCD 로그아웃 | HTTP 세션 무효화, 세션에 속한 코드·토큰 grant 폐기, Refresh Token 재사용 거부, 다른 세션 보존 |
| 통합 DCD 통지 | 참여 앱 목록, 서명된 Logout Token의 sid/events/nonce 제외, POST 전송, 중복 무효화 방지 |
| 통합 DCD 재시도 | 10초 → 1분 → 5분 → 5분, 총 5회 실패 후 FAILED/알림. 실제 대기 없이 시각 전달 |
| Spring Security | 로그인·로그아웃 CSRF 보호 |

로그아웃 후에도 이미 발급된 JWT의 오프라인 서명 검증은 성공한다.
이는 통합 DCD에 명시된 제한이며 테스트에서도 저장소 폐기와 구분한다.

## 테스트용 단순화

- 사용자·클라이언트·세션·인가·통지·감사 기록은 모두 메모리다. Redis 구현은 없다.
- 계정은 `student`, `professor`, `staff`, `admin`이며 비밀번호는 `test-password`다.
  클라이언트 ID는 ADR의 포털 코드이고 secret은 `test-secret`이다. 모두 테스트 전용이다.
- 설명을 단순하게 유지하기 위해 데모·테스트 사용자의 로그인 ID를 subject로 사용한다.
  운영용 UUID 사용자 저장소나 애플리케이션 등록 관리 기능을 구현한 것은 아니다.
- 자동 테스트의 콜백/로그아웃 URI는 가상의 고정 주소다.
  브라우저 데모에서는 등록된 localhost 포털 주소를 사용한다.
- Refresh Token 허용 및 프레임워크의 기본 만료 시간은 테스트 fixture 설정이다.
  운영 정책을 확정하는 의미가 아니다.
- 통지는 메모리 outbox로 관리한다. 자동 테스트는 `dispatch(now)`로 대기 없이 확인하고,
  브라우저 데모는 1초마다 전송한다. 영속 outbox·장애 복구·운영 알림 연동은 구현하지 않는다.
- 자동 세션 만료, 다중 인스턴스 공유, 관리자 강제 로그아웃 API,
  Role 변경 정책은 이 테스트 범위에 포함하지 않는다.
  브라우저 데모는 서명·issuer·audience·events·시각·jti를 검증한 Logout Token으로 포털 HTTP 세션을 종료한다.
- 앱 내부 Permission 관리는 ADR-001에 따라 추가하지 않는다.

프레임워크 설정 참고: [Spring Authorization Server Configuration Model](https://docs.spring.io/spring-authorization-server/reference/configuration-model.html)

# Centralized Integration Login System

## 프로젝트 구조(Project Structure)

CILS/
│
├── README.md
├── .gitignore
├── .github/
│   ├── workflows/
│   └── ISSUE_TEMPLATE/
│
├── docs/                       # RUP 문서 및 UML
│   ├── 00-project-management/
│   ├── 01-inception/
│   ├── 02-elaboration/
│   ├── 03-construction/
│   ├── 04-transition/
│   └── assets/
│
├── backend/
│   └── cils-server/            # 중앙 인증 서버
│       ├── build.gradle
│       ├── src/
│       │   ├── main/
│       │   └── test/
│       └── ...
│
├── clients/                     # 연동 테스트 애플리케이션
│   └── lms-demo/
│
├── infrastructure/              # 실행 환경
│   ├── docker/
│   ├── database/
│   ├── redis/
│   └── monitoring/
│
├── tests/                       # 통합/보안/장애 테스트
│   ├── integration/
│   ├── security/
│   ├── availability/
│   └── scenarios/
│
└── scripts/                     # 개발 및 배포 자동화

--- 

## Commit Convention

<aside>

#### 🪄Branch Naming Rule

- **kebab-case 활용**
- **기능 중심 작성**

```java
형식 : <작업유형(type)>/<기능명 또는 이슈 키워드(description)>
예시 : feature/user-login, fix/null-pointer ...
```

| 타입 | 용도 | 예시 |
| --- | --- | --- |
| `feature` | 새로운 기능 개발 | `feature/user-login` |
| `fix` | 버그 수정 | `fix/null-pointer` |
| `hotfix` | 운영 중 긴급 수정 | `hotfix/login-failure` |
| `docs` | 문서 수정 | `docs/readme-update` |
| `refactor` | 리팩토링 | `refactor/auth-service` |
| `test` | 테스트 코드 | `test/user-service` |
| `chore` | 빌드, 설정 변경 | `chore/github-actions` |
| `build` | 빌드 시스템 변경 | `build/gradle-upgrade` |
| `ci` | CI/CD 수정 | `ci/jenkins-pipeline` |
</aside>
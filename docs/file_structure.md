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
│   └── ccils-server/            # 중앙 인증 서버
│       ├── build.gradle
│       ├── src/
│       │   ├── main/
│       │   └── test/
│       └── ...
│
├── clients/                     # 연동 테스트 애플리케이션
│   ├── lms-demo/
│   ├── academic-demo/
│   └── admin-demo/
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
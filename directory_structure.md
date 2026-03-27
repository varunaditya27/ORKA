com.orka.app/

├── core/                  # Shared foundations (used everywhere)
│   ├── database/
│   ├── models/
│   ├── utils/
│   ├── constants/
│   ├── extensions/
│   └── theme/

├── features/              # Feature-based modules (THIS IS KEY)

│   ├── task/
│   │   ├── data/
│   │   ├── domain/
│   │   └── presentation/

│   ├── capture/
│   │   ├── data/
│   │   ├── domain/
│   │   └── presentation/

│   ├── scheduler/
│   │   ├── engine/
│   │   ├── rules/
│   │   └── domain/

│   ├── alarm/
│   │   ├── receiver/
│   │   ├── service/
│   │   ├── ui/
│   │   └── manager/

│   ├── behavior/
│   │   ├── tracking/
│   │   ├── analytics/
│   │   └── domain/

│   ├── ai/
│   │   ├── parser/
│   │   ├── model/
│   │   └── prompt/

│   ├── reminder/
│   │   ├── data/
│   │   └── domain/

│   ├── archive/
│   │   └── presentation/

│   └── onboarding/
│       └── presentation/

├── navigation/            # Navigation graph + routes

├── di/                    # Dependency injection (Hilt/Koin)

└── app/                   # Main application setup
plugins {
    // 빌드에 필요한 JDK 21 이 PC 에 없으면 Gradle 이 자동으로 내려받는다(Java 툴체인 자동 설치).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tetris2026"

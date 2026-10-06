import java.nio.file.Files
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// 저장소가 OneDrive 아래 있으면 OneDrive 가 폴더마다 읽기 전용 속성을 붙이고(파일은 그대로),
// Windows 는 읽기 전용 폴더를 지우지 못하게 해서 Gradle 이 "Unable to delete directory" 로 멈춘다.
// 그래서 빌드를 시작할 때 build/ 와 .gradle/ 아래 폴더의 읽기 전용 속성을 먼저 푼다.
// 한 번 푼 폴더에 OneDrive 가 다시 붙이지는 않는다(2026-10-01 확인). Windows 가 아니면 아무것도 하지 않는다.
if (System.getProperty("os.name").startsWith("Windows")) {
    listOf(layout.buildDirectory.get().asFile, file(".gradle"))
        .filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.isDirectory }.toList() }
        .forEach { dir -> runCatching { Files.setAttribute(dir.toPath(), "dos:readonly", false) } }
}

repositories {
    mavenCentral()
}

dependencies {
    // Python 에서 학습해 ONNX 로 내보낸 모델을 게임 안에서 실행한다 (docs/MODEL_IO.md)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
    // JSON: 학습용 대전 계획·기록(docs/TRAINING.md 7장)과 테스트의 픽스처
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")

    // 테스트: JUnit 6 (JUnit 5 와 같은 Jupiter API)
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "kr.ac.jbnu.se.tetris.ui.TetrisApp"
}

// gradlew run -Dtetris.fullscreen=true 처럼 준 tetris.* 설정을 게임 JVM 으로 넘긴다 (docs/GAME.md 4장, TRAINING.md 10.9).
tasks.named<JavaExec>("run") {
    jvmArgs("-Dstdout.encoding=UTF-8")
    systemProperties(System.getProperties().filterKeys { it.toString().startsWith("tetris.") }
        .mapKeys { it.key.toString() })
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

// 공통 픽스처 폴더. 다른 폴더로 돌려 보려면: gradlew test -PfixturesDir=<경로>
val fixturesDir: String = providers.gradleProperty("fixturesDir")
    .getOrElse(layout.projectDirectory.dir("fixtures").asFile.absolutePath)

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("fixtures.dir", fixturesDir)
    // 픽스처 JSON 만 바뀌어도 테스트를 다시 돌리도록 입력으로 등록한다.
    inputs.dir(fixturesDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println("테스트 ${result.testCount}개: 통과 ${result.successfulTestCount}, " +
                    "실패 ${result.failedTestCount}, 건너뜀 ${result.skippedTestCount}")
            }
        }
    })
}

// 공통 픽스처만 돌린다 (docs/FIXTURES.md). `gradlew test` 와 `gradlew build` 에도 포함돼 있다.
tasks.register<Test>("fixtures") {
    group = "verification"
    description = "fixtures/ 의 공통 픽스처만 실행한다 (docs/FIXTURES.md)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("kr.ac.jbnu.se.tetris.fixture.FixtureTest")
    }
}

// 대전 계획 파일(plan-1)대로 AI 끼리 블록 동기 대전을 여러 스레드로 두고 기록(match-1)을 남긴다 (docs/TRAINING.md 6, 7장).
// 학습 루프가 세대마다 부른다. 예: gradlew match --args="runs/r01/gen-000/plan.json"
tasks.register<JavaExec>("match") {
    group = "application"
    description = "대전 계획 파일대로 AI 끼리 대전시키고 기록을 남긴다 (docs/TRAINING.md)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.train.MatchMain"
    jvmArgs("-Dstdout.encoding=UTF-8")
}

// fixtures/match 를 다시 만든다 (docs/FIXTURES.md 5.10). 기록 형식이나 엔진을 바꿨을 때만 쓴다.
tasks.register<JavaExec>("matchFixture") {
    group = "verification"
    description = "Java 대전 러너로 fixtures/match 의 기록 사례를 다시 만든다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.train.MatchFixtureTool"
    args(layout.projectDirectory.dir("fixtures/match").asFile.absolutePath)
    jvmArgs("-Dstdout.encoding=UTF-8")
}

// 배치 탐색 속도 측정과 경로 재생 검증. 예: gradlew bench --args="5000 1" (블록 수, 시드)
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "PlacementFinder 속도를 재고 모든 경로를 재생해 검증한다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.fixture.PlacementBench"
    jvmArgs("-Dstdout.encoding=UTF-8")
}

// 화면을 창 없이 그림 파일(build/screens/*.png)로 그린다. 레이아웃 확인용 (docs/GAME.md 7장).
tasks.register<JavaExec>("screenshot") {
    group = "verification"
    description = "메뉴·대전·결과 화면을 build/screens 에 PNG 로 그린다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ui.ScreenshotTool"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Djava.awt.headless=true")
}

// 게임의 난이도 단계를 가상 시간 모드로 잰다: 실행 실패율, 초당 블록 수, 이웃 단계 사이 승률 (docs/GAME.md 6장).
// 예: gradlew levelBench --args="runs/levels01/levels.json 16 3" (결과 파일, 짝마다 판 수, 스레드)
tasks.register<JavaExec>("levelBench") {
    group = "verification"
    description = "models/ 의 난이도 단계를 가상 시간 모드로 대전시켜 잰다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ai.LevelBench"
    jvmArgs("-Dstdout.encoding=UTF-8")
}

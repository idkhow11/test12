import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
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

// OneDrive 는 build/ 에 새로 쓴 파일을 몇 분 뒤 한꺼번에 클라우드 자리표시로 바꾼다. 그 뒤 몇 분 동안 Java 의 폴더 목록이 그 파일을
// '보통 파일 아님'(isOther)으로 보여 주므로 Gradle 이 "Cannot snapshot …: not a regular file" 로 멈춘다(2026-10-11 재현, 한 번에 약 7분).
// 그래서 빌드를 시작할 때 Gradle 과 같은 방법(Files.walkFileTree)으로 build/ 를 훑어 그런 파일만 같은 바이트로 다시 쓴다.
// 내용이 같으므로 작업은 UP-TO-DATE 로 남는다. 긴 빌드 도중에 그 창이 열리면 그 빌드만 실패할 수 있고, 다음 실행 때 이 단계가 고친다.
// (PowerShell 의 ReparsePoint 속성이나 dos:attributes 로는 고르지 못한다: 잘 될 때도 붙어 있거나, 문제일 때도 보이지 않는다.)
if (System.getProperty("os.name").startsWith("Windows")) {
    val buildRoot = layout.buildDirectory.get().asFile.toPath()
    if (Files.isDirectory(buildRoot)) {
        val leaked = mutableListOf<Path>()
        runCatching {
            Files.walkFileTree(buildRoot, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isOther) leaked.add(file)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        var fixed = 0
        for (f in leaked) {
            val tmp = f.resolveSibling("${f.fileName}.od-tmp")
            try {
                Files.write(tmp, Files.readAllBytes(f))
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                fixed++
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(tmp) } // 쓰는 중인 파일 등은 그대로 두고 다음 실행에 다시 본다
            }
        }
        if (leaked.isNotEmpty()) logger.lifecycle("OneDrive 자리표시 파일 $fixed/${leaked.size}개를 build/ 에서 보통 파일로 되돌림")
    }
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
    // 시험 쪽 TestSandbox 가 LauncherSessionListener 를 구현하므로 실행뿐 아니라 컴파일에도 필요하다 (아래 sandboxProperties).
    testImplementation("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "kr.ac.jbnu.se.tetris.ui.TetrisApp"
}

// 배포 묶음(zip·tar, 합쳐 약 120MB)은 아무도 쓰지 않는데 gradlew build 가 매번 다시 만들어 OneDrive 에 올라간다.
// 그래서 -Pdist 를 줄 때만 만든다 (예: gradlew distZip -Pdist). 게임은 gradlew run 으로 띄우고, 이 묶음에는 models/ 가 없다.
// 학습 러너(python/tetris_ai/runner.py)가 쓰는 installDist 는 그대로 둔다.
val wantDist = providers.gradleProperty("dist").isPresent
tasks.named("distZip") { enabled = wantDist }
tasks.named("distTar") { enabled = wantDist }

// ONNX Runtime 은 JVM 을 띄울 때마다 DLL 을 %TEMP%\onnxruntime-java<수> 에 풀어 쓰는데, Windows 는 쓰는 중인 DLL 을 지우지 못해
// 그 폴더가 16MB 씩 남는다(2026-10-11 에 998개, 15.7GB). 한 번 풀어 둔 build/onnxruntime-native 를 onnxruntime.native.path 로 넘기면
// DLL 을 풀지 않는다(게임 run, 시험, 도구 모두). 이 폴더도 OneDrive 아래라 자리표시가 될 수 있지만 위의 단계가 함께 고친다.
if (System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
    val ortNative = layout.buildDirectory.dir("onnxruntime-native")
    val extractOrtNative = tasks.register<Sync>("extractOrtNative") {
        description = "ONNX Runtime 의 Windows DLL 을 build/onnxruntime-native 에 풀어 둔다 (게임·시험·도구가 함께 쓴다)."
        from({ configurations.runtimeClasspath.get().filter { it.name.startsWith("onnxruntime-") }.map { zipTree(it) } }) {
            include("ai/onnxruntime/native/win-x64/*")
            eachFile { path = name }
            includeEmptyDirs = false
        }
        into(ortNative)
    }
    val ortPath = ortNative.get().asFile.absolutePath
    tasks.withType<Test>().configureEach {
        dependsOn(extractOrtNative)
        systemProperty("onnxruntime.native.path", ortPath)
    }
    tasks.withType<JavaExec>().configureEach {
        dependsOn(extractOrtNative)
        systemProperty("onnxruntime.native.path", ortPath)
    }
}

// 도구가 찍는 한글(경고 포함)이 Gradle 출력에서 깨지지 않게 표준 출력과 오류 출력을 모두 UTF-8 로 쓴다 (gradle.properties 와 같다).
val utf8Output = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

// gradlew run -Dtetris.fullscreen=true 처럼 준 tetris.* 설정을 게임 JVM 으로 넘긴다 (docs/GAME.md 4장, TRAINING.md 10.9).
tasks.named<JavaExec>("run") {
    jvmArgs(utf8Output)
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

// 시험과 화면 확인·벤치 도구가 사용자의 것을 건드리지 않게 하는 JVM 설정 (settings 패키지 설명 3장):
// 창 없이, 진짜 소리 장치를 열지 않고(tetris.sound), 사용자 설정 폴더의 저장 파일을 읽거나 쓰지 않고(tetris.config),
// 사람 대 AI 판을 끝까지 돌려도 runs/human 에 승률 자료를 남기지 않는다(tetris.records). 기록이 필요한 시험은 임시 폴더를 준다.
// IDE(VS Code, IntelliJ)에서 바로 돌린 JUnit 시험에는 이 값이 없으므로, src/test/resources/META-INF/services 에 등록한
// TestSandbox 가 JUnit 이 시험을 찾기 전에 같은 네 값을 준다(창은 언제나 끄고, tetris.* 는 이미 준 값을 덮지 않는다).
// main 으로 바로 띄운 도구에는 그 안전장치가 없으니 이 Gradle 작업으로 돌린다.
val sandboxProperties = mapOf(
    "java.awt.headless" to "true",
    "tetris.sound" to "off",
    "tetris.config" to "off",
    "tetris.records" to "off",
)
val sandboxJvmArgs = sandboxProperties.map { (k, v) -> "-D$k=$v" }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("fixtures.dir", fixturesDir)
    systemProperties(sandboxProperties)
    // 픽스처 JSON 이나 모델 묶음(models/, 묶음을 쓰는 시험)만 바뀌어도 테스트를 다시 돌리도록 입력으로 등록한다.
    inputs.dir(fixturesDir).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("models")).withPropertyName("models").withPathSensitivity(PathSensitivity.RELATIVE)
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
    jvmArgs(utf8Output)
}

// fixtures/match 를 다시 만든다 (docs/FIXTURES.md 5.10). 기록 형식이나 엔진을 바꿨을 때만 쓴다.
tasks.register<JavaExec>("matchFixture") {
    group = "verification"
    description = "Java 대전 러너로 fixtures/match 의 기록 사례를 다시 만든다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.train.MatchFixtureTool"
    args(layout.projectDirectory.dir("fixtures/match").asFile.absolutePath)
    jvmArgs(utf8Output)
}

// 배치 탐색 속도 측정과 경로 재생 검증. 예: gradlew bench --args="5000 1" (블록 수, 시드)
tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "PlacementFinder 속도를 재고 모든 경로를 재생해 검증한다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.fixture.PlacementBench"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 화면을 창 없이 그림 파일(build/screens/*.png)로 그린다. 레이아웃 확인용 (docs/GAME.md 7장).
tasks.register<JavaExec>("screenshot") {
    group = "verification"
    description = "메뉴·대전·결과 화면을 build/screens 에 PNG 로 그린다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ui.ScreenshotTool"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 게임의 난이도 단계를 가상 시간 모드로 잰다: 실행 실패율, 초당 블록 수, 이웃 단계 사이 승률 (docs/GAME.md 6장).
// 예: gradlew levelBench --args="runs/levels01/levels.json 16 3" (결과 파일, 짝마다 판 수, 스레드)
tasks.register<JavaExec>("levelBench") {
    group = "verification"
    description = "models/ 의 난이도 단계를 가상 시간 모드로 대전시켜 잰다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ai.LevelBench"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// AI 한 번의 결정 시간을 잰다 (docs/GAME.md 5장). 예: gradlew decisionBench --args="5 5 4 4 prefetch"
// (P1 단계, P2 단계, 판 수, 연산 스레드, prefetch 를 주면 실제 시간 + 미리 고르기)
tasks.register<JavaExec>("decisionBench") {
    group = "verification"
    description = "AI 의 결정 시간(가상 시간, 또는 실제 시간 + 미리 고르기)을 잰다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ai.DecisionBench"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 사용자 설정 "AI 속도"(50~150%)로 바꾼 AI 를 가상 시간 모드로 잰다: 초당 블록 수, 실행 실패율, 생각 중 고정 (PLAN 4.3, 7장).
// 예: gradlew speedBench --args="build/speed.json 1,3,5 50,100,150 4 120 3" (결과 파일, 단계, 속도, 판 수, 판 상한 초, 스레드)
tasks.register<JavaExec>("speedBench") {
    group = "verification"
    description = "AI 속도마다 AI 의 초당 블록 수와 실행 실패율을 가상 시간 모드로 잰다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ai.SpeedBench"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 실제 시간으로 판을 돌리며 화면이 겪는 멈춤(잠금 대기, 보드 늦음)과 미리 고르기 적중률을 잰다 (docs/GAME.md 5장).
// 예: gradlew realtimeBench --args="60 5 100" (초, 단계, AI 속도 %). 판 기록은 언제나 끈다.
tasks.register<JavaExec>("realtimeBench") {
    group = "verification"
    description = "실제 시간 판에서 화면의 잠금 대기와 보드 늦음을 잰다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ui.RealtimeBench"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 메뉴·설정·도움말 화면, 색 묶음, 아이콘을 창 없이 build/screens-prep 에 PNG 로 그린다 (ui.ScreensPreview).
tasks.register<JavaExec>("screensPreview") {
    group = "verification"
    description = "메뉴·설정·도움말 화면, 색 묶음, 아이콘을 build/screens-prep 에 PNG 로 그린다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ui.ScreensPreview"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 모든 효과음을 WAV 로 build/sfx-preview 에 쓰고 소리마다 들리는 크기를 찍는다 (audio.SoundPreview).
// 들어 보려면: gradlew soundPreview --args="build/sfx-preview play" (그때만 기본 소리 장치를 연다)
tasks.register<JavaExec>("soundPreview") {
    group = "verification"
    description = "효과음을 build/sfx-preview 에 WAV 로 쓰고 크기를 찍는다 (play 를 주면 들려준다)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.audio.SoundPreview"
    jvmArgs(utf8Output)
    jvmArgs("-Dtetris.config=off", "-Dtetris.records=off")
}

// 모래 AI 를 단계마다 가상 시간으로 두며 잰다 (sand/ai 패키지 설명). 예: gradlew sandBench --args="0-5 12 240 6"
// (단계, 판 수, 초, 스레드). -Dsand.weights=… 같은 sand.* 설정은 그대로 넘긴다.
tasks.register<JavaExec>("sandBench") {
    group = "verification"
    description = "모래 AI 를 단계마다 가상 시간으로 두며 잰다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.sand.ai.SandAiBench"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
    systemProperties(System.getProperties().filterKeys { it.toString().startsWith("sand.") }
        .mapKeys { it.key.toString() })
}

// 하드드롭만 하는 손이 모래 모드에서 언제 지는지 가상 시간으로 잰다 (sand.SandDropBench).
// 예: gradlew sandDropBench --args="1,5 8 600 4 150" (시작 단계, 판 수, 초, 스레드, 지연 ms)
tasks.register<JavaExec>("sandDropBench") {
    group = "verification"
    description = "하드드롭만 하는 손이 모래 모드에서 언제 지는지 가상 시간으로 잰다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.sand.SandDropBench"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 모래 화면을 창 없이 그림 파일(build/screens-sand/*.png)로 그린다 (runs/quality/SPEC.md V14). 장면 19가지 × 1280×720·1920×1080.
tasks.register<JavaExec>("sandScreens") {
    group = "verification"
    description = "모래 화면(시작, 판 도중, 지우기, 조각, 단계, 위험, 기다림, 일시정지, 결과, 색약, 효과 끔)을 build/screens-sand 에 PNG 로 그린다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ui.SandScreens"
    args(layout.buildDirectory.dir("screens-sand").get().asFile.absolutePath)
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 모래 판을 실제 진행 스레드와 실제 시간으로 몇 분 둔다(손은 모래 AI, 단계 0 은 하드드롭만 하는 손): 멈춤·오류·그리기 시간을 잰다.
// 예: gradlew sandSoak --args="180 3" (초, AI 단계). 판 기록은 임시 폴더에만 남기고 끝나면 지운다.
tasks.register<JavaExec>("sandSoak") {
    group = "verification"
    description = "모래 판을 실제 시간으로 몇 분 두며 멈춤·오류·그리기 시간을 잰다(기록은 임시 폴더)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ui.SandSoak"
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 대전 화면 효과의 프레임당 비용을 잰다 (runs/quality/SPEC.md 1.4(f): 효과 전부 2ms 이하). 기본은 창 없이 소프트웨어로 그린다.
// 예: gradlew battleBench --args="120 1920 1080" (판 초, 가로, 세로)
// -Pd3d 를 주면 Windows 의 실제 화면 파이프라인(D3D)으로 잰다. 창을 띄우지 않고 화면을 찍지도 않지만 headless 이면 안 되므로,
// 그때만 java.awt.headless 를 빼고 인자 기본값을 "120 1920 1080 d3d" 로 둔다. -Dsun.java2d.trace=count 같은 sun.java2d.* 설정은 넘긴다.
// 예: gradlew battleBench -Pd3d, gradlew battleBench -Pd3d --args="60 1920 1080 d3d" "-Dsun.java2d.trace=count"
tasks.register<JavaExec>("battleBench") {
    group = "verification"
    description = "대전 화면 효과(줄 지우기, 고정 반짝임, 흔들림, K.O. …)의 비용을 효과 강하게와 끔으로 나란히 그려 잰다(-Pd3d: D3D)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ui.BattleFxBench"
    val d3d = providers.gradleProperty("d3d").isPresent
    jvmArgs(utf8Output)
    jvmArgs(if (d3d) sandboxJvmArgs.filterNot { it.startsWith("-Djava.awt.headless=") } else sandboxJvmArgs)
    if (d3d) args("120", "1920", "1080", "d3d")
    systemProperties(System.getProperties().filterKeys { it.toString().startsWith("sun.java2d.") }
        .mapKeys { it.key.toString() })
}

// 글자 크기표·색 견본을 build/look-foundation 에 그린다 (runs/quality/SPEC.md V1·V2).
tasks.register<JavaExec>("lookSpecimen") {
    group = "verification"
    description = "글자 크기표(type-scale.png)와 색 견본(tokens.png)을 build/look-foundation 에 그린다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ui.ThemeSpecimen"
    args(layout.buildDirectory.dir("look-foundation").get().asFile.absolutePath)
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

// 빛 번짐(Theme.glow) 한 번의 비용을 예전 선 다섯 겹과 비교하고 glow.png 를 그린다 (runs/quality/SPEC.md V3(f)).
tasks.register<JavaExec>("glowBench") {
    group = "verification"
    description = "Theme.glow 의 조각 그림과 예전 선 다섯 겹의 비용·모양을 비교하고 build/look-foundation/glow.png 를 그린다."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "kr.ac.jbnu.se.tetris.ui.FxGlowBench"
    args(layout.buildDirectory.dir("look-foundation").get().asFile.absolutePath)
    jvmArgs(utf8Output)
    jvmArgs(sandboxJvmArgs)
}

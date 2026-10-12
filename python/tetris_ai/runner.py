"""Java 대전 러너 부르기 (docs/TRAINING.md 12.3): 실행마다 고정한 러너 사본으로 계획 파일을 돌린다.

학습은 몇 시간씩 걸리고 세대마다 Java 대전 러너를 부른다. 예전처럼 `gradlew match` 로 부르면 부를 때마다 저장소의 Java 를
다시 컴파일하므로, 학습하는 동안 게임 코드를 고치면 컴파일 오류나 엔진 변경이 그대로 학습에 섞였다. 그래서 실행을 처음
시작할 때 `gradlew installDist` 로 만든 jar 들을 runs/<run>/runner/lib 에 복사해 두고, 그 실행은 끝까지 그 사본만
`java -cp` 로 부른다. 이어 하기(--resume)도 같은 사본을 쓴다. 엔진을 일부러 바꿔 다시 돌리려면 runner 폴더를 지운다.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import stat
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "tetris2026"  # settings.gradle.kts 의 rootProject.name
MAIN_CLASS = "kr.ac.jbnu.se.tetris.train.MatchMain"
RUNNER_DIR = "runner"


def rel(path: Path, base: Path) -> str:
    """base 기준 상대 경로(슬래시). 계획 파일 안의 경로는 계획 파일이 있는 폴더 기준이다(MatchMain)."""
    return Path(os.path.relpath(path, base)).as_posix()


def rmtree(path: Path) -> None:
    """OneDrive 가 폴더에 붙이는 읽기 전용 속성을 풀어 가며 지운다 (build.gradle.kts 의 같은 문제)."""

    def clear_and_retry(func, p, _exc):
        os.chmod(p, stat.S_IWRITE)
        func(p)

    if path.exists():
        shutil.rmtree(path, onerror=clear_and_retry)


def java_exe() -> str:
    """JAVA_HOME 의 java, 없으면 PATH 의 java."""
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.exists():
            return str(exe)
    return "java"


def ensure_runner(run_dir: Path, root: Path = ROOT) -> Path:
    """run_dir/runner/lib 에 러너 사본이 있으면 그대로, 없으면 지금 저장소를 빌드해 만든다. lib 폴더를 돌려준다."""
    lib = run_dir / RUNNER_DIR / "lib"
    if any(lib.glob("*.jar")):
        return lib
    gradlew = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    proc = subprocess.run([str(gradlew), "-q", "installDist"], cwd=root, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(f"러너 빌드 실패 ({proc.returncode}):\n{proc.stdout[-2000:]}\n{proc.stderr[-4000:]}")
    built = root / "build" / "install" / PROJECT / "lib"
    jars = sorted(built.glob("*.jar"))
    if not jars:
        raise RuntimeError(f"installDist 결과에 jar 가 없음: {built}")
    tmp = lib.with_name("lib.tmp")
    rmtree(tmp)
    tmp.mkdir(parents=True)
    for jar in jars:
        shutil.copy2(jar, tmp / jar.name)
    rmtree(lib)
    tmp.rename(lib)  # 다 복사한 뒤에 이름을 바꿔, 중간에 멈춰도 반쪽 사본을 쓰지 않는다
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain", "--", "src"], cwd=root, capture_output=True,
                           text=True).stdout.strip()
    (lib.parent / "runner.json").write_text(json.dumps({
        "git_head": head, "src_uncommitted_changes": bool(dirty),
        "jars": {j.name: hashlib.sha256((lib / j.name).read_bytes()).hexdigest()[:16] for j in jars},
    }, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    return lib


def run_java(plan: dict, plan_path: Path, run_dir: Path, root: Path = ROOT, main_class: str = MAIN_CLASS,
             done_prefix: str = "[match] 끝") -> str:
    """계획을 plan_path 에 쓰고 run_dir 의 러너 사본(없으면 지금 만든다)으로 실행한다. 마지막 요약 줄을 돌려준다.
    main_class 를 주면 대전 러너 대신 그 클래스(예: 실시간 자료 생성기 TimedDataMain)를 부른다."""
    lib = ensure_runner(run_dir, root)
    plan_path.parent.mkdir(parents=True, exist_ok=True)
    rmtree(plan_path.parent / plan["out"])
    plan_path.write_text(json.dumps(plan, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    cmd = [java_exe(), "-Dstdout.encoding=UTF-8", "-cp", str(lib.resolve() / "*"), main_class,
           str(plan_path.resolve())]
    proc = subprocess.run(cmd, cwd=root, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError(f"Java 실행 실패 ({proc.returncode}):\n{proc.stdout[-2000:]}\n{proc.stderr[-4000:]}")
    lines = [ln for ln in proc.stdout.splitlines() if ln.startswith(done_prefix)]
    return lines[-1] if lines else proc.stdout.strip().splitlines()[-1]

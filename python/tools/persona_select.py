"""p05 고르기: 미리 정한 규칙(python/configs/p05_selection.json)으로 이어 학습할 파일럿, 그리고 게임에 쓸 세대를 고른다.

    python python/tools/persona_select.py --runs p05a,p05b                       # 파일럿 고르기 (세대 10)
    python python/tools/persona_select.py --run p05a --gens 10,15,20,25,30,35,40   # 세대 고르기 (본 학습 뒤)

[세대 고르기] 세대마다 analysis/gen-XXX/signatures.json 과 probe.json 을 읽는다. 다섯 라벨(원형 넷과 중립)의 세기가 모두
strength 띠 안(첫 AI 와 합친 승률 모두)인 세대 중에서, 통과한 설계 수가 가장 많은 세대를 고른다. 같으면 결정 점수(아래)가
큰 세대, 그래도 같으면 이른 세대. 고른 세대의 최종 확인 명령(쓰지 않은 시드 confirm.seed0~)을 출력한다.

[파일럿 고르기] 파일럿마다 세대 gen 의 세 가지를 본다. persona_probe.py, analyze_personas.py(--reference 규칙의 상대,
--k 5), persona_signatures.py 를 먼저 돌려 둔다.

    결정   persona_probe 의 라벨별 값(runs/<run>/probe.json): decision.labels 마다 '중립과 다른 결정' ≥ vs_reference_min,
           그리고 ≥ noise_ratio_min × '같은 라벨 복제끼리 다른 결정'
    세기   analyze_personas(상대 strength.opponent) + persona_signatures 의 라벨마다 첫 AI 의 승률
           (runs/<run>/analysis/gen-XXX/signatures.json). strength.min 미만이면 탈락. strength.max 초과는 표시만 한다
           (4단계 두뇌보다 강한 것은 멈출 이유가 아니다. 마지막 세대 고르기에서 띠 전체를 다시 본다)
    평가   학습 루프의 기준 AI 상대 평가 승률 평균(log.csv 의 eval_win_rate_mean, gen 까지)이 evaluation.consecutive 번
           연달아 evaluation.floor 미만이면 탈락

통과한 파일럿 중 점수 = min over 라벨 (중립과 다른 결정 − 복제끼리 차이) 가 가장 큰 쪽을 고른다. 가장 큰 점수가 다른
통과 파일럿 점수의 tie_ratio 배 안이고 prefer 가 통과했으면 prefer(τ 0.3, p04 와 같은 온도)를 고른다. 통과한 파일럿이
없으면 잇지 않고, 실패 종류마다 미리 정한 대안(spec.fallback)을 출력한다. 고른 파일럿의 이어 학습 명령도 출력한다.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SPEC = "python/configs/p05_selection.json"
CONFIG_COPIES = "python/configs"  # runs/ 는 git 에 올리지 않으므로 규칙 파일이 가리키는 설정 파일의 사본을 여기 함께 올린다


def config_path(rel: str, root: Path = ROOT) -> str:
    """규칙 파일이 가리키는 설정 파일(저장소 기준, 예: runs/p05-design/configs/p05a.json). 그 파일이 없으면(runs/ 가 없는 새
    저장소) python/configs 에 함께 올린 같은 이름의 사본(같은 바이트)을 쓴다. 둘 다 없으면 받은 그대로 돌려준다."""
    if (root / rel).exists():
        return rel
    copy = f"{CONFIG_COPIES}/{Path(rel).name}"
    return copy if (root / copy).exists() else rel


def judge(probe_gen: dict, wins: dict[str, float], evals: list[float], spec: dict) -> dict:
    """파일럿 하나의 판정. probe_gen 은 persona_probe 의 세대 결과(reference, by_label), wins 는 {라벨: 첫 AI 승률},
    evals 는 세대 순서의 평가 승률 평균."""
    rule = spec["decision"]
    checks, margins = [], []
    if probe_gen.get("reference") != "neutral":
        checks.append({"kind": "decision", "label": "-", "ok": False, "why": f"기준이 중립이 아님: {probe_gen.get('reference')}"})
    for lab in rule["labels"]:
        v = probe_gen.get("by_label", {}).get(lab)
        if v is None:
            checks.append({"kind": "decision", "label": lab, "ok": False, "why": "라벨 없음"})
            margins.append(float("-inf"))
            continue
        ref, within = float(v["vs_reference"]), float(v.get("within_label") or 0.0)
        ok = ref >= rule["vs_reference_min"] and ref >= rule["noise_ratio_min"] * within
        checks.append({"kind": "decision", "label": lab, "vs_reference": ref, "within_label": within, "ok": bool(ok)})
        margins.append(ref - within)
    band = spec["strength"]
    above = []
    for lab, wr in wins.items():
        ok = wr is not None and wr >= band["min"]
        checks.append({"kind": "strength", "label": lab, "win_rate": wr, "ok": bool(ok)})
        if wr is not None and wr > band["max"]:
            above.append(lab)
    ev = spec["evaluation"]
    run, worst_run = 0, 0
    for x in evals:
        run = run + 1 if x < ev["floor"] else 0
        worst_run = max(worst_run, run)
    checks.append({"kind": "evaluation", "values": list(evals), "ok": worst_run < ev["consecutive"]})
    return {"eligible": all(c["ok"] for c in checks), "score": min(margins) if margins else float("-inf"),
            "strength_above_band": above, "checks": checks}


def choose(results: dict[str, dict], spec: dict) -> dict:
    """판정 {파일럿: judge 결과} → 고른 파일럿과 이유. 고를 수 없으면 winner 는 None 이고 fallback 에 대안을 담는다."""
    eligible = {p: r["score"] for p, r in results.items() if r["eligible"]}
    out = {"winner": None, "scores": {p: r["score"] for p, r in results.items()}, "pilots": results, "fallback": []}
    if eligible:
        best = max(eligible, key=eligible.get)
        rest = [s for p, s in eligible.items() if p != best]
        prefer = spec.get("prefer")
        tie = prefer in eligible and best != prefer and eligible[best] <= spec["tie_ratio"] * eligible[prefer]
        out["winner"] = prefer if tie else best
        out["why"] = (f"통과 {sorted(eligible)}; 점수 " + ", ".join(f"{p} {s:.4f}" for p, s in eligible.items())
                      + (f"; 최고 점수가 {prefer} 의 {spec['tie_ratio']} 배 안이라 {prefer}" if tie else "")
                      + ("" if rest or tie else "; 통과한 파일럿이 하나뿐"))
        return out
    kinds = {c["kind"] for r in results.values() for c in r["checks"] if not c["ok"]}
    for kind in ("decision", "strength", "evaluation"):
        if kind in kinds:
            out["fallback"].append(spec.get("fallback", {}).get(kind, kind))
    out["why"] = "통과한 파일럿이 없음: " + ", ".join(sorted(kinds))
    return out


def decision_score(probe_gen: dict | None, labels: list[str]) -> float | None:
    """결정 점수 = min over 라벨 (중립과 다른 결정 − 복제끼리 차이). probe 결과가 없으면 None."""
    if not probe_gen:
        return None
    by = probe_gen.get("by_label", {})
    if any(lab not in by for lab in labels):
        return None
    return min(float(by[lab]["vs_reference"]) - float(by[lab].get("within_label") or 0.0) for lab in labels)


def judge_generation(sig: dict, probe_gen: dict | None, spec: dict) -> dict:
    """세대 하나: 통과한 설계, 다섯 라벨의 세기(첫 AI·합친 승률이 띠 안), 결정 점수. sig 는 persona_signatures 결과."""
    lo, hi = spec["strength"]["min"], spec["strength"]["max"]
    strength = {lab: (d["strength"]["win_rate_first"], d["strength"]["win_rate_pooled"]) for lab, d in sig["designs"].items()}
    ref = sig["reference_strength"]
    strength[sig["reference"]] = (ref["win_rate_first"], ref["win_rate_pooled"])
    return {"passed": sorted(lab for lab, d in sig["designs"].items() if d["pass"]),
            "strength": strength, "strength_ok": all(lo <= a <= hi and lo <= b <= hi for a, b in strength.values()),
            "decision_score": decision_score(probe_gen, spec["decision"]["labels"])}


def choose_generation(rows: dict[int, dict]) -> int | None:
    """세기가 모두 띠 안인 세대 중 (통과한 설계 수, 결정 점수, 이른 세대) 순으로 가장 좋은 세대. 없으면 None."""
    ok = [g for g, r in rows.items() if r["strength_ok"]]
    if not ok:
        return None
    score = {g: rows[g]["decision_score"] if rows[g]["decision_score"] is not None else float("-inf") for g in ok}
    return max(ok, key=lambda g: (len(rows[g]["passed"]), score[g], -g))


def generation_main(run: str, gens: list[int], spec: dict, out_rel: str | None) -> None:
    run_dir = ROOT / "runs" / run
    probe_all = json.loads((run_dir / "probe.json").read_text(encoding="utf-8")) if (run_dir / "probe.json").exists() else {}
    rows = {}
    for g in gens:
        path = run_dir / "analysis" / f"gen-{g:03d}" / "signatures.json"
        if not path.exists():
            raise SystemExit(f"{path} 가 없음: analyze_personas.py 와 persona_signatures.py --run {run} --gen {g} 먼저")
        rows[g] = judge_generation(json.loads(path.read_text(encoding="utf-8")), probe_all.get(str(g)), spec)
    best = choose_generation(rows)
    conf = spec["confirm"]
    out = {"run": run, "chosen_gen": best, "rows": {str(g): r for g, r in rows.items()}, "spec": spec.get("registered")}
    if best is not None:
        ref = spec["strength"]["opponent"]
        out["confirm"] = [
            f"python/.venv/Scripts/python python/tools/analyze_personas.py --run {run} --gen {best} --reference {ref} "
            f"--k 5 --seed0 {conf['seed0']} --seeds {conf['seeds']} --tag {conf['tag']} --no-export",
            f"python/.venv/Scripts/python python/tools/persona_signatures.py --run {run} --gen {best} --tag {conf['tag']}"]
    out_path = ROOT / (out_rel or f"runs/{run}/generation_choice.json")
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for g, r in rows.items():
        s = r["decision_score"]
        print(f"세대 {g:3d}: 통과 {len(r['passed'])} {r['passed']} | 세기 {'띠 안' if r['strength_ok'] else '띠 밖'} ("
              + ", ".join(f"{lab} {a:.3f}/{b:.3f}" for lab, (a, b) in r["strength"].items()) + ") | 결정 점수 "
              + ("-" if s is None else f"{s:.4f}"))
    print(f"→ 고른 세대: {best if best is not None else '없음 (다섯 라벨 세기가 모두 띠 안인 세대가 없음)'}")
    for line in out.get("confirm", []):
        print("  " + line)
    print(f"→ {out_path}")
    sys.exit(0 if best is not None else 2)


def eval_rates(run_dir: Path, gen: int) -> list[float]:
    """log.csv 의 평가 승률 평균 (세대 gen 까지, 세대 순서)."""
    with open(run_dir / "log.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    by_gen = {int(r["gen"]): float(r["eval_win_rate_mean"]) for r in rows if r.get("eval_win_rate_mean")}
    return [by_gen[g] for g in sorted(by_gen) if g <= gen]


def same_path(a: str, b: str) -> bool:
    return Path(a).as_posix().lower() == Path(b).as_posix().lower()


def collect(run: str, gen: int, spec: dict) -> tuple[dict, dict, list[float]]:
    """파일럿 하나의 입력: probe 세대 결과, {라벨: 첫 AI 승률}, 평가 승률들. 빠진 파일이 있으면 무엇을 돌릴지 말하고 멈춘다."""
    run_dir = ROOT / "runs" / run
    probe_path = run_dir / "probe.json"
    if not probe_path.exists() or str(gen) not in json.loads(probe_path.read_text(encoding="utf-8")):
        raise SystemExit(f"{probe_path} 에 세대 {gen} 이 없음: persona_probe.py --run {run} --gens ...{gen} 먼저")
    probe_gen = json.loads(probe_path.read_text(encoding="utf-8"))[str(gen)]
    ana = run_dir / "analysis" / f"gen-{gen:03d}"
    if not (ana / "signatures.json").exists():
        raise SystemExit(f"{ana / 'signatures.json'} 가 없음: analyze_personas.py 와 persona_signatures.py --run {run} "
                         f"--gen {gen} 먼저")
    report = json.loads((ana / "report.json").read_text(encoding="utf-8"))
    if not same_path(report["reference"], spec["strength"]["opponent"]):
        raise SystemExit(f"{ana} 의 고정 상대가 {report['reference']} (규칙은 {spec['strength']['opponent']})")
    s = json.loads((ana / "signatures.json").read_text(encoding="utf-8"))
    wins = {lab: d["strength"]["win_rate_first"] for lab, d in s["designs"].items()}
    wins[s["reference"]] = s["reference_strength"]["win_rate_first"]
    return probe_gen, wins, eval_rates(run_dir, gen)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--runs", default=None, help="[파일럿] 파일럿 실행들, 쉼표로 (기본: 규칙 파일의 pilots)")
    p.add_argument("--gen", type=int, default=None, help="[파일럿] 비교할 세대 (기본: 규칙 파일의 gen)")
    p.add_argument("--run", default=None, help="[세대] 본 학습 실행 이름. --gens 와 함께 주면 세대 고르기")
    p.add_argument("--gens", default=None, help="[세대] 후보 세대들, 쉼표로 (분석·서명이 끝난 세대)")
    p.add_argument("--spec", default=DEFAULT_SPEC)
    p.add_argument("--out", default=None,
                   help="결과 JSON (저장소 기준. 기본: 파일럿 runs/p05-design/selection.json, 세대 runs/<run>/generation_choice.json)")
    args = p.parse_args()

    spec = json.loads((ROOT / args.spec).read_text(encoding="utf-8"))
    if args.run or args.gens:
        if not (args.run and args.gens):
            raise SystemExit("세대 고르기는 --run 과 --gens 를 함께 준다")
        generation_main(args.run, [int(g) for g in args.gens.split(",")], spec, args.out)
        return
    args.out = args.out or "runs/p05-design/selection.json"
    gen = spec["gen"] if args.gen is None else args.gen
    runs = args.runs.split(",") if args.runs else list(spec["pilots"])
    results, inputs = {}, {}
    for run in runs:
        probe_gen, wins, evals = collect(run, gen, spec)
        inputs[run] = {"wins": wins, "evals": evals}
        results[run] = judge(probe_gen, wins, evals, spec)
    out = choose(results, spec)
    out.update({"gen": gen, "spec": args.spec, "inputs": inputs})
    w = out["winner"]
    if w:
        cfg = config_path(spec.get("pilots", {}).get(w, {}).get("config", f"runs/p05-design/configs/{w}.json"))
        # 세대 11~20, 21~30, 31~40 의 청크 셋 (청크마다 10 세대, 경합이 심해도 90 분 안)
        out["continue"] = [f"bash runs/p05-design/run_chunk.sh {w} {g} {cfg} --keep-every 5 >> runs/{w}.chunk{i}.out 2>&1"
                           for i, g in ((2, 21), (3, 31), (4, 41))]
    out_path = ROOT / args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    for run, r in results.items():
        bad = [c for c in r["checks"] if not c["ok"]]
        print(f"[{'통과' if r['eligible'] else '탈락'}] {run}: 점수 {r['score']:.4f} | "
              + ", ".join(f"{c['label']} {c['vs_reference']:.3f}/{c['within_label']:.3f}"
                          for c in r["checks"] if c["kind"] == "decision" and "vs_reference" in c)
              + " | 승률 " + ", ".join(f"{k} {v:.3f}" for k, v in inputs[run]["wins"].items())
              + " | 평가 " + ", ".join(f"{x:.3f}" for x in inputs[run]["evals"])
              + (f" | 띠 위: {', '.join(r['strength_above_band'])}" if r["strength_above_band"] else "")
              + (" | 미달: " + "; ".join(f"{c['kind']} {c.get('label', '')}".strip() for c in bad) if bad else ""))
    print(f"→ 고른 파일럿: {w or '없음'} ({out['why']})")
    for line in out.get("continue", []):
        print("  " + line)
    for line in out["fallback"]:
        print("  대안: " + line)
    print(f"→ {out_path}")
    sys.exit(0 if w else 2)


if __name__ == "__main__":
    main()

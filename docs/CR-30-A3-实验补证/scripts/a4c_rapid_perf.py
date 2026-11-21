# -*- coding: utf-8 -*-
"""A-4-C 快速连点原始性能采集（2.3）：base vs combined 同条件多轮。
用法: python a4c_rapid_perf.py <serial> <build_tag> <round>

每轮：force-stop → 启动 → 预热 6s → gfxinfo reset → 12 次快速连点（计划间隔 250ms，
monotonic 时钟调度并记录计划/实际时间戳）→ 静置 2s → 保存原始 dumpsys gfxinfo 与
framestats 到独立目录 evidence/a4c_perf/<build_tag>_r<round>/。
关键 adb 命令检查 returncode，失败立即中止并记录。
"""
import json
import subprocess
import sys
import time
from pathlib import Path

PKG = "com.screentrace.app"
OUT = Path(r"docs\CR-30-A3-实验补证\evidence\a4c_perf")
TAPS = [("stats", 703, 2493), ("calendar", 885, 2493), ("today", 503, 2493)] * 4
GAP = 0.25  # 计划点击间隔（秒）


def adb(serial, *args, timeout=90):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=timeout)
    return r


def must(r, what, log):
    if r.returncode != 0:
        log.append({"cmd_failed": what, "returncode": r.returncode,
                    "stderr": r.stderr.decode("utf-8", "replace")[-300:]})
        raise RuntimeError(f"adb failed: {what} rc={r.returncode}")


def main():
    serial, tag, round_ = sys.argv[1], sys.argv[2], sys.argv[3]
    d = OUT / f"{tag}_r{round_}"
    d.mkdir(parents=True, exist_ok=True)
    log = []
    try:
        must(adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP"), "wakeup", log)
        must(adb(serial, "shell", "am", "force-stop", PKG), "force-stop", log)
        time.sleep(1)
        must(adb(serial, "shell", "am", "start", "-n", f"{PKG}/.MainActivity"), "start", log)
        time.sleep(6)
        must(adb(serial, "shell", "dumpsys", "gfxinfo", PKG, "reset"), "gfxinfo reset", log)
        taps = []
        t_plan0 = time.monotonic()
        for i, (name, x, y) in enumerate(TAPS):
            t_plan = t_plan0 + i * GAP
            delay = t_plan - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            t_actual = time.monotonic()
            must(adb(serial, "shell", "input", "tap", str(x), str(y)), f"tap{i}", log)
            t_done = time.monotonic()
            taps.append({"i": i, "target": name,
                         "plan_offset_ms": round((t_plan - t_plan0) * 1000, 1),
                         "actual_offset_ms": round((t_actual - t_plan0) * 1000, 1),
                         "deviation_ms": round((t_actual - t_plan) * 1000, 1),
                         "cmd_duration_ms": round((t_done - t_actual) * 1000, 1)})
        time.sleep(2)
        g = adb(serial, "shell", "dumpsys", "gfxinfo", PKG)
        must(g, "gfxinfo dump", log)
        (d / "gfxinfo.txt").write_bytes(g.stdout)
        f = adb(serial, "shell", "dumpsys", "gfxinfo", PKG, "framestats")
        must(f, "framestats dump", log)
        (d / "framestats.txt").write_bytes(f.stdout)
        if len(g.stdout) < 100 or len(f.stdout) < 100:
            raise RuntimeError("gfxinfo/framestats output too small")
        info = {"build": tag, "round": round_, "device": serial,
                "taps": taps, "gap_plan_ms": GAP * 1000,
                "window": "gfxinfo reset 至 dump（12 次计划间隔 250ms 连点 + 2s 静置）",
                "refresh": adb(serial, "shell", "settings", "get", "system", "peak_refresh_rate").stdout.decode().strip(),
                "failures": log}
        (d / "run_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[{tag}_r{round_}] OK taps=12 files=gfxinfo.txt,framestats.txt,run_info.json", flush=True)
    except Exception as e:
        (d / "run_info.json").write_text(json.dumps({"build": tag, "round": round_, "failures": log, "error": str(e)}, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[{tag}_r{round_}] FAILED: {e}", flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()

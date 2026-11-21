# -*- coding: utf-8 -*-
"""A-5 快速 Tab 连点路由一致性因果实验：场景矩阵执行 + 时间线抓取（只读探针）。
用法: python a5_run.py <serial> <hz> <scenario> <rep>

R2 修订（GPT 指令 3.2–3.5）：
- 刷新率（3.2）：实验前读取并保存原值，finally 中恢复，恢复失败明确报告；记录前后值。
- ADB 可靠性（3.3）：关键命令检查 returncode，失败立即中止；录屏检查退出码；视频拉取后
  检查存在/大小/可读性（ftyp 头）；logcat 检查生成且非空；每轮独立目录（拒绝覆盖）；
  失败命令与错误信息写入 run_info.json。
- 设备适配（3.4）：按 ro.product.model 识别机型与坐标表，核对分辨率；未知机型中止，
  不套用任何默认坐标；实际坐标与设备信息写入 run_info.json。
- 点击节奏（3.5）：monotonic 时钟规划点击时间，记录计划/实际时间戳与偏差、命令耗时；
  ADB 耗时导致间隔不准时以偏差字段如实呈现（不称严格固定间隔）。

场景：s1_single / s2_double / s3_triple / s4_reverse / s5_same / s6_other。
每轮输出：logcat 时间线（CR30Probe：A5/*、Route/*、Pager/*）+ 录屏 + run_info.json。
"""
import json
import subprocess
import sys
import time
from pathlib import Path

PKG = "com.screentrace.app"
OUT = Path(r"C:\tmp\cr30_a5\raw")

DEVICES = {
    "V2352A": {  # vivo
        "nav": {"today": (515, 2621), "stats": (725, 2621), "calendar": (921, 2621)},
        "resolution": ("1080x2400",),
    },
    "2407FRK8EC": {  # Redmi
        "nav": {"today": (503, 2493), "stats": (703, 2493), "calendar": (885, 2493)},
        "resolution": ("1220x2712",),
    },
}

SCENARIOS = {
    "s1_single": [("stats", 0)],
    "s2_double": [("stats", 0), ("calendar", 150)],
    "s3_triple": [("stats", 0), ("calendar", 150), ("today", 150)],
    "s4_reverse": [("stats", 0), ("today", 250)],
    "s5_same": [("stats", 0), ("stats", 200)],
    "s6_other": [("stats", 0), ("calendar", 200)],
}


class Abort(Exception):
    pass


def adb(serial, *args, timeout=90):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=timeout)


def must(serial, args, what, log):
    r = adb(serial, *args)
    if r.returncode != 0:
        log.append({"cmd_failed": what, "returncode": r.returncode,
                    "stderr": r.stderr.decode("utf-8", "replace")[-300:]})
        raise Abort(f"adb failed: {what} rc={r.returncode}")
    return r


def main():
    serial, hz, scen, rep = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    taps = SCENARIOS[scen]
    log = []
    run_dir = OUT / f"{scen}_{hz}hz_r{rep}"
    run_dir.mkdir(parents=True, exist_ok=True)
    if (run_dir / "run_info.json").exists():
        raise Abort(f"round dir already exists (refusing to overwrite): {run_dir}")
    info = {"serial": serial, "hz_requested": hz, "scenario": scen, "rep": rep,
            "taps": [], "env": {}, "failures": log}
    try:
        # ---- 设备识别与分辨率核对（3.4）----
        model = must(serial, ["shell", "getprop", "ro.product.model"], "getprop", log).stdout.decode().strip()
        cfg = DEVICES.get(model)
        if cfg is None:
            raise Abort(f"unrecognized device model: {model!r} — refusing to apply any coordinates")
        wm = must(serial, ["shell", "wm", "size"], "wm size", log).stdout.decode().strip()
        if not any(res in wm for res in cfg["resolution"]):
            raise Abort(f"resolution mismatch: got {wm!r}, expected {cfg['resolution']}")
        info["env"].update({"model": model, "wm_size": wm, "nav": cfg["nav"]})

        # ---- 刷新率：读取并保存原值（3.2）----
        for key in ("min_refresh_rate", "peak_refresh_rate"):
            v = must(serial, ["shell", "settings", "get", "system", key], f"get {key}", log).stdout.decode().strip()
            info["env"][f"{key}_before"] = v
        must(serial, ["shell", "settings", "put", "system", "min_refresh_rate", hz], "set min_refresh_rate", log)
        must(serial, ["shell", "settings", "put", "system", "peak_refresh_rate", hz], "set peak_refresh_rate", log)
        time.sleep(0.5)

        must(serial, ["shell", "input", "keyevent", "KEYCODE_WAKEUP"], "wakeup", log)
        must(serial, ["shell", "am", "force-stop", PKG], "force-stop", log)
        time.sleep(1)
        must(serial, ["shell", "am", "start", "-n", f"{PKG}/.MainActivity"], "start", log)
        time.sleep(6)
        must(serial, ["logcat", "-c"], "logcat clear", log)
        rec = subprocess.Popen(["adb", "-s", serial, "shell", "screenrecord",
                                "--time-limit", "10", f"/sdcard/{run_dir.name}.mp4"])
        time.sleep(1)
        # ---- 点击：monotonic 计划时间（3.5）----
        t0 = time.monotonic()
        prev_plan = t0
        for i, (name, gap_ms) in enumerate(taps):
            plan = prev_plan + (gap_ms / 1000.0 if i > 0 else 0)
            delay = plan - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            actual = time.monotonic()
            x, y = cfg["nav"][name]
            must(serial, ["shell", "input", "tap", str(x), str(y)], f"tap{i}:{name}", log)
            done = time.monotonic()
            info["taps"].append({"i": i, "target": name, "x": x, "y": y,
                                 "plan_offset_ms": round((plan - t0) * 1000, 1),
                                 "actual_offset_ms": round((actual - t0) * 1000, 1),
                                 "deviation_ms": round((actual - plan) * 1000, 1),
                                 "cmd_duration_ms": round((done - actual) * 1000, 1)})
            prev_plan = plan
        time.sleep(3)
        # ---- 录屏退出码检查（3.3）----
        rc = rec.wait(timeout=30)
        if rc != 0:
            raise Abort(f"screenrecord exit code {rc}")
        logcat = adb(serial, "logcat", "-d", "-v", "epoch", "-s", "CR30Probe:D")
        if logcat.returncode != 0:
            raise Abort(f"logcat dump failed rc={logcat.returncode}")
        text = logcat.stdout.decode("utf-8", "replace")
        if not text.strip():
            raise Abort("logcat output empty")
        (run_dir / "logcat.txt").write_text(text, encoding="utf-8")
        vid = adb(serial, "exec-out", "cat", f"/sdcard/{run_dir.name}.mp4")
        vp = run_dir / "video.mp4"
        vp.write_bytes(vid.stdout)
        if not vp.exists() or vp.stat().st_size == 0:
            raise Abort("video pull: file missing or zero bytes")
        head = vp.read_bytes()[:12]
        if b"ftyp" not in head:
            raise Abort(f"video unreadable: bad header {head!r}")
        info["video_bytes"] = vp.stat().st_size
        info["logcat_bytes"] = len(text)
    except Abort as e:
        info["error"] = str(e)
    finally:
        # ---- 刷新率恢复（3.2）：失败明确报告，不静默 ----
        for key in ("min_refresh_rate", "peak_refresh_rate"):
            orig = info["env"].get(f"{key}_before", "null")
            r = adb(serial, ["shell", "settings", "put", "system", key, orig])
            if r.returncode != 0:
                info.setdefault("restore_failures", []).append(
                    {"key": key, "orig": orig, "stderr": r.stderr.decode("utf-8", "replace")[-200:]})
            now = adb(serial, ["shell", "settings", "get", "system", key]).stdout.decode().strip()
            info["env"][f"{key}_after"] = now
        (run_dir / "run_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[{run_dir.name}] {'OK' if 'error' not in info else 'FAILED: ' + info['error']}", flush=True)
    if "error" in info:
        sys.exit(1)


if __name__ == "__main__":
    main()

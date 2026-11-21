# -*- coding: utf-8 -*-
"""A-4-C 快速连点原始性能采集（2.3；R3 修订 2.2）：base vs combined 同条件多轮。
用法: python a4c_rapid_perf.py <serial> <build_tag> <round>

R3 修订：
- run_info 记录设备型号、wm size、刷新率（读取命令检查 returncode）与构建标识。
- 设备型号/分辨率核对（Redmi 2407FRK8EC/1220x2712 专用坐标），未知机型或分辨率
  不符即中止，防止 Redmi 坐标误用于其他设备；点击坐标做 0<=x<width、0<=y<height 合法性检查。
- 已存在实验目录拒绝覆盖。
- 时间语义：plan_offset_ms=计划时刻；host_initiated_offset_ms=主机侧发起 ADB 命令前的
  monotonic 时刻，**不等同设备实际接收触摸事件的时间**（设备侧时间无法可靠取得，
  保留为主机调度指标）；cmd_duration_ms=ADB 命令自身耗时。

每轮输出 evidence/a4c_perf/<build_tag>_r<round>/：gfxinfo.txt、framestats.txt、run_info.json。
关键 adb 命令检查 returncode，失败立即中止并记录。
"""
import json
import re
import subprocess
import sys
import time
from pathlib import Path

PKG = "com.screentrace.app"
OUT = Path(r"docs\CR-30-A3-实验补证\evidence\a4c_perf")
TAPS = [("stats", 703, 2493), ("calendar", 885, 2493), ("today", 503, 2493)] * 4
GAP = 0.25  # 计划点击间隔（秒）
EXPECTED = {"model": "2407FRK8EC", "wm": "1220x2712"}  # 本脚本坐标表仅适用于 Redmi


def adb(serial, *args, timeout=90):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=timeout)
    return r


def must(r, what, log):
    if r.returncode != 0:
        log.append({"cmd_failed": what, "returncode": r.returncode,
                    "stderr": r.stderr.decode("utf-8", "replace")[-300:]})
        raise RuntimeError(f"adb failed: {what} rc={r.returncode}")
    return r


def main():
    serial, tag, round_ = sys.argv[1], sys.argv[2], sys.argv[3]
    d = OUT / f"{tag}_r{round_}"
    if d.exists() and any(d.iterdir()):
        print(f"[{tag}_r{round_}] REFUSED: dir exists with content (no overwrite): {d}", flush=True)
        sys.exit(1)
    d.mkdir(parents=True, exist_ok=True)
    log = []
    info = {"build": tag, "round": round_, "device_serial": serial,
            "taps": [], "gap_plan_ms": GAP * 1000,
            "window": "gfxinfo reset 至 dump（12 次计划间隔 250ms 连点 + 2s 静置）",
            "timestamps_semantics": "monotonic 主机侧；host_initiated_offset_ms=发起 ADB 命令前时刻，"
                                    "非设备实际接收触摸事件时间；设备侧输入时间不可靠取得，不虚构",
            "failures": log}
    try:
        # 设备型号/分辨率核对（防 Redmi 坐标误用）
        model = must(adb(serial, "shell", "getprop", "ro.product.model"), "getprop", log).stdout.decode().strip()
        wm = must(adb(serial, "shell", "wm", "size"), "wm size", log).stdout.decode().strip()
        info["model"] = model
        info["wm_size"] = wm
        if model != EXPECTED["model"] or EXPECTED["wm"] not in wm:
            raise RuntimeError(f"device mismatch: model={model!r} wm={wm!r} expected={EXPECTED}")
        wm_m = re.search(r"(\d+)x(\d+)", wm)
        width, height = int(wm_m.group(1)), int(wm_m.group(2))
        for _, x, y in TAPS:
            if not (0 <= x < width and 0 <= y < height):
                raise RuntimeError(f"coordinate out of bounds: ({x},{y}) for {width}x{height}")

        # 刷新率：读取并记录（returncode 检查）
        for key in ("min_refresh_rate", "peak_refresh_rate"):
            r = must(adb(serial, "shell", "settings", "get", "system", key), f"get {key}", log)
            info.setdefault("refresh", {})[key] = r.stdout.decode().strip()
        info["build_ident"] = {"build_tag": tag, "apk_sha256": sys.argv[4] if len(sys.argv) > 4 else "见 SHA256SUMS.txt"}

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
            t_host = time.monotonic()
            must(adb(serial, "shell", "input", "tap", str(x), str(y)), f"tap{i}", log)
            t_done = time.monotonic()
            taps.append({"i": i, "target": name,
                         "plan_offset_ms": round((t_plan - t_plan0) * 1000, 1),
                         "host_initiated_offset_ms": round((t_host - t_plan0) * 1000, 1),
                         "deviation_ms": round((t_host - t_plan) * 1000, 1),
                         "cmd_duration_ms": round((t_done - t_host) * 1000, 1)})
        time.sleep(2)
        g = must(adb(serial, "shell", "dumpsys", "gfxinfo", PKG), "gfxinfo dump", log)
        (d / "gfxinfo.txt").write_bytes(g.stdout)
        f = must(adb(serial, "shell", "dumpsys", "gfxinfo", PKG, "framestats"), "framestats dump", log)
        (d / "framestats.txt").write_bytes(f.stdout)
        if len(g.stdout) < 100 or len(f.stdout) < 100:
            raise RuntimeError("gfxinfo/framestats output too small")
        info["taps"] = taps
        info["status"] = "OK"
        (d / "run_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[{tag}_r{round_}] OK taps=12 files=gfxinfo.txt,framestats.txt,run_info.json", flush=True)
    except Exception as e:
        info["status"] = "FAILED"
        info["error"] = str(e)
        (d / "run_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[{tag}_r{round_}] FAILED: {e}", flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()

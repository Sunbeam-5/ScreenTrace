# -*- coding: utf-8 -*-
"""A-5 快速 Tab 连点路由一致性因果实验：场景矩阵执行 + 时间线抓取（只读探针）。
用法: python a5_run.py <serial> <hz> <scenario> <rep>

场景（点击坐标为设备 nav 中心；vivo 今日515/统计725/日历921 @2621）：
  s1_single   单次点击切换（今日→统计）
  s2_double   两次快速点击不同 Tab（统计→日历，间隔 150ms）
  s3_triple   连续三次点击不同 Tab（统计→日历→今日，间隔 150ms）
  s4_reverse  点击后立即反向点击（统计→今日，间隔 250ms）
  s5_same     动画进行中点击当前目标（统计→200ms→统计）
  s6_other    动画进行中点击另一个目标（统计→200ms→日历）

每轮输出：logcat 时间线（CR30Probe：A5/tabClick、A5/scrollStart|End|Cancel|Finally、
Route/currentPage|guardIgnore|selectMain|align、Pager/scrollStart|End）+ 录屏（UI 对账）。
"""
import re
import subprocess
import sys
import time
from pathlib import Path

PKG = "com.screentrace.app"
OUT = Path(r"C:\tmp\cr30_a5\raw")
NAV = {"today": (515, 2621), "stats": (725, 2621), "calendar": (921, 2621)}

SCENARIOS = {
    "s1_single": [("stats", 0)],
    "s2_double": [("stats", 0), ("calendar", 150)],
    "s3_triple": [("stats", 0), ("calendar", 150), ("today", 150)],
    "s4_reverse": [("stats", 0), ("today", 250)],
    "s5_same": [("stats", 0), ("stats", 200)],
    "s6_other": [("stats", 0), ("calendar", 200)],
}


def adb(serial, *args, timeout=90):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")


def main():
    serial, hz, scen, rep = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    taps = SCENARIOS[scen]
    OUT.mkdir(parents=True, exist_ok=True)
    base = f"{scen}_{hz}hz_r{rep}"
    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb(serial, "shell", "settings", "put", "system", "min_refresh_rate", hz)
    adb(serial, "shell", "settings", "put", "system", "peak_refresh_rate", hz)
    time.sleep(0.5)
    adb(serial, "shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb(serial, "shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(6)
    adb(serial, "logcat", "-c")
    rec = subprocess.Popen(["adb", "-s", serial, "shell", "screenrecord",
                            "--time-limit", "10", f"/sdcard/{base}.mp4"])
    time.sleep(1)
    for name, gap in taps:
        if gap:
            time.sleep(gap / 1000.0)
        x, y = NAV[name]
        adb(serial, "shell", "input", "tap", str(x), str(y))
    time.sleep(3)
    rec.wait(timeout=30)
    log = adb(serial, "logcat", "-d", "-v", "epoch", "-s", "CR30Probe:D")
    (OUT / f"{base}.logcat.txt").write_text(log, encoding="utf-8")
    vid = subprocess.run(["adb", "-s", serial, "exec-out", "cat", f"/sdcard/{base}.mp4"],
                         capture_output=True)
    (OUT / f"{base}.mp4").write_bytes(vid.stdout)
    events = re.findall(r"CR30Probe: (A5/\S+|Route/\S+|Pager/\S+)", log)
    print(f"[{base}] events={len(events)} video={len(vid.stdout)//1024}KB", flush=True)


if __name__ == "__main__":
    main()

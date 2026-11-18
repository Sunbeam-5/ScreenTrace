# -*- coding: utf-8 -*-
"""A-3 二分跑测：far 切换 ×2 轮，gfxinfo + Perfetto。
用法: python a3_run.py <tag> <rep> [--trace]
"""
import re
import subprocess
import sys
import time
from pathlib import Path

SERIAL = "GAQOHMNFQWFUPBQO"
PKG = "com.screentrace.app"
OUT = Path(r"C:\tmp\cr30_a2\a3raw")

def adb(*args, timeout=90):
    r = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace")

def main():
    tag, rep = sys.argv[1], sys.argv[2]
    want_trace = "--trace" in sys.argv
    OUT.mkdir(parents=True, exist_ok=True)
    base = f"{tag}_r{rep}"
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(6)
    adb("shell", "dumpsys", "gfxinfo", PKG, "reset")
    trace_dev = f"/data/misc/perfetto-traces/{base}.pftrace"
    if want_trace:
        subprocess.run(["adb", "-s", SERIAL, "shell", "perfetto", "--background", "--txt",
                        "-c", "/data/misc/perfetto-configs/a2_cfg.txt", "-o", trace_dev],
                       capture_output=True, timeout=30)
        time.sleep(1)
    for _ in range(2):
        adb("shell", "input", "tap", "880", "2500"); time.sleep(1.4)
        adb("shell", "input", "tap", "320", "2500"); time.sleep(1.4)
    time.sleep(1.5)
    gfx = subprocess.run(["adb", "-s", SERIAL, "shell", "dumpsys", "gfxinfo", PKG],
                         capture_output=True).stdout.decode("utf-8", "replace")
    (OUT / f"{base}.gfx.txt").write_text(gfx, encoding="utf-8")
    m = re.search(r"Total frames rendered: (\d+)\s*\n\s*Janky frames: (\d+) \(([\d.]+)%\)", gfx)
    line = f"[{base}] frames={m.group(1) if m else '?'} janky={m.group(2) if m else '?'}({m.group(3) if m else '?'}%)"
    if want_trace:
        time.sleep(9)
        r = subprocess.run(["adb", "-s", SERIAL, "exec-out", "cat", trace_dev], capture_output=True)
        (OUT / f"{base}.pftrace").write_bytes(r.stdout)
        line += f" trace={len(r.stdout)//1024}KB"
    print(line)

if __name__ == "__main__":
    main()

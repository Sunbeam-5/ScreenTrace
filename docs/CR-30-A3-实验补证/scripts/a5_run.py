# -*- coding: utf-8 -*-
"""A-5 快速 Tab 连点路由一致性因果实验：场景矩阵执行 + 时间线抓取（只读探针）。
用法: python a5_run.py <serial> <hz> <scenario> <rep>

R3 修订（GPT 指令 3.1–3.4）：
- 3.1 finally 中 ADB 参数一律展开传递（修复嵌套列表 TypeError）；恢复流程整体 try/except，
  保证 run_info.json 总能写入（finally 不因异常阻止报告落盘）。
- 3.2 刷新率恢复只针对**成功读取**的键；原值为 "null"（设置项不存在）时用 settings delete
  恢复原始「未设置」状态；读取失败的键不覆盖设备设置；恢复后读回核验并记录 restore_ok。
- 3.3 录屏 wait 捕获 TimeoutExpired（kill 后收集退出结果）；视频拉取检查 returncode/存在/
  大小/ftyp 可读性；捕获 TimeoutExpired/FileNotFoundError 等关键异常；已存在目录含任意
  内容即拒绝覆盖；失败轮 run_info 标记 status=FAILED，不产生疑似成功材料。
- 3.4 设备适配：V2352A 坐标/分辨率**待真机核实**（原配置分辨率 1080x2400 与 y=2621 矛盾，
  未核实前分辨率置 PENDING_VERIFICATION 使校验必然失败——强制门禁，禁止 vivo 矩阵）；
  所有坐标做 0<=x<width、0<=y<height 合法性检查；Redmi 2407FRK8EC/1220x2712 校验保留。

场景：s1_single / s2_double / s3_triple / s4_reverse / s5_same / s6_other。
每轮输出：logcat 时间线（CR30Probe：A5/*、Route/*、Pager/*）+ 录屏 + run_info.json。
"""
import json
import re
import subprocess
import sys
import time
from pathlib import Path

PKG = "com.screentrace.app"
OUT = Path(r"C:\tmp\cr30_a5\raw")

DEVICES = {
    "V2352A": {  # vivo：坐标为 A-2/A-4 实测可用值，但分辨率配置曾误填（1080x2400 与 y=2621
        # 矛盾）。R3：置 PENDING_VERIFICATION，真机核实 wm size 与 bounds 后才允许矩阵。
        "nav": {"today": (515, 2621), "stats": (725, 2621), "calendar": (921, 2621)},
        "resolution": ("PENDING_VERIFICATION",),
    },
    "2407FRK8EC": {  # Redmi：坐标经 uiautomator bounds 实测（中心点）
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
    """所有调用一律展开参数传递（禁止嵌套列表）。"""
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=timeout)


def must(serial, args, what, log):
    r = adb(serial, *args)
    if r.returncode != 0:
        log.append({"cmd_failed": what, "returncode": r.returncode,
                    "stderr": r.stderr.decode("utf-8", "replace")[-300:]})
        raise Abort(f"adb failed: {what} rc={r.returncode}")
    return r


def parse_wm(wm_text):
    m = re.search(r"(\d+)x(\d+)", wm_text or "")
    return (int(m.group(1)), int(m.group(2))) if m else None


def restore_refresh(serial, info, log):
    """3.1/3.2：恢复刷新率设置。只动成功读取过的键；null 原值→delete；失败→不覆盖；
    恢复后读回核验；任何异常不向上传播（保证 run_info 可写）。"""
    results = {}
    for key in ("min_refresh_rate", "peak_refresh_rate"):
        before = info["env"].get(f"{key}_before")
        if before is None:
            results[key] = "skipped: 原值未成功读取，不覆盖设备设置"
            continue
        try:
            if before == "null":
                r = adb(serial, "shell", "settings", "delete", "system", key)
                action = "delete(原值不存在，恢复未设置状态)"
            else:
                r = adb(serial, "shell", "settings", "put", "system", key, before)
                action = f"put {before}"
            if r.returncode != 0:
                results[key] = f"FAILED: {action} rc={r.returncode}"
                info.setdefault("restore_failures", []).append(
                    {"key": key, "action": action, "returncode": r.returncode,
                     "stderr": r.stderr.decode("utf-8", "replace")[-200:]})
                continue
            after = adb(serial, "shell", "settings", "get", "system", key)
            now = after.stdout.decode().strip() if after.returncode == 0 else "<read-failed>"
            ok = (now == before) or (before == "null" and now == "null")
            results[key] = f"{action} -> after={now} restore_ok={ok}"
        except Exception as e:  # 3.1/3.3：finally 内异常不阻止 run_info 写入
            results[key] = f"EXCEPTION: {e!r}"
            info.setdefault("restore_failures", []).append({"key": key, "exception": repr(e)})
    info["env"]["refresh_restore"] = results


def main():
    serial, hz, scen, rep = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    taps = SCENARIOS[scen]
    log = []
    run_dir = OUT / f"{scen}_{hz}hz_r{rep}"
    if run_dir.exists() and any(run_dir.iterdir()):
        raise Abort(f"round dir exists with content (refusing to overwrite): {run_dir}")
    run_dir.mkdir(parents=True, exist_ok=True)
    info = {"serial": serial, "hz_requested": hz, "scenario": scen, "rep": rep,
            "taps": [], "env": {}, "failures": log, "status": "FAILED"}
    try:
        # ---- 设备识别与分辨率/坐标核对（3.4）----
        model = must(serial, ["shell", "getprop", "ro.product.model"], "getprop", log).stdout.decode().strip()
        cfg = DEVICES.get(model)
        if cfg is None:
            raise Abort(f"unrecognized device model: {model!r} — refusing to apply any coordinates")
        wm = must(serial, ["shell", "wm", "size"], "wm size", log).stdout.decode().strip()
        info["env"].update({"model": model, "wm_size": wm, "nav": cfg["nav"]})
        if not any(res in wm for res in cfg["resolution"]):
            raise Abort(f"resolution not verified: got {wm!r}, expected one of {cfg['resolution']} "
                        f"（vivo 配置待真机核实，核实前禁止矩阵）")
        size = parse_wm(wm)
        if size is None:
            raise Abort(f"cannot parse wm size: {wm!r}")
        width, height = size
        for name, (x, y) in cfg["nav"].items():
            if not (0 <= x < width and 0 <= y < height):
                raise Abort(f"coordinate out of bounds: nav[{name}]=({x},{y}) for {width}x{height}")

        # ---- 刷新率：读取并保存原值（3.2，读取失败不入恢复表）----
        for key in ("min_refresh_rate", "peak_refresh_rate"):
            r = must(serial, ["shell", "settings", "get", "system", key], f"get {key}", log)
            info["env"][f"{key}_before"] = r.stdout.decode().strip()
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
        # ---- 点击：monotonic 计划时间（3.5 语义：主机侧调度指标）----
        t0 = time.monotonic()
        prev_plan = t0
        for i, (name, gap_ms) in enumerate(taps):
            plan = prev_plan + (gap_ms / 1000.0 if i > 0 else 0)
            delay = plan - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            host = time.monotonic()
            x, y = cfg["nav"][name]
            must(serial, ["shell", "input", "tap", str(x), str(y)], f"tap{i}:{name}", log)
            done = time.monotonic()
            info["taps"].append({"i": i, "target": name, "x": x, "y": y,
                                 "plan_offset_ms": round((plan - t0) * 1000, 1),
                                 "host_initiated_offset_ms": round((host - t0) * 1000, 1),
                                 "deviation_ms": round((host - plan) * 1000, 1),
                                 "cmd_duration_ms": round((done - host) * 1000, 1)})
            prev_plan = plan
        time.sleep(3)
        # ---- 录屏退出码/超时（3.3）----
        try:
            rc = rec.wait(timeout=30)
        except subprocess.TimeoutExpired:
            rec.kill()
            rc = rec.wait(timeout=10)
            raise Abort(f"screenrecord timeout, killed, exit={rc}")
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
        if vid.returncode != 0:
            raise Abort(f"video pull rc={vid.returncode}")
        vp = run_dir / "video.mp4"
        vp.write_bytes(vid.stdout)
        if not vp.exists() or vp.stat().st_size == 0:
            raise Abort("video pull: file missing or zero bytes")
        head = vp.read_bytes()[:12]
        if b"ftyp" not in head:
            raise Abort(f"video unreadable: bad header {head!r}")
        info["video_bytes"] = vp.stat().st_size
        info["logcat_bytes"] = len(text)
        info["status"] = "OK"
    except (Abort, subprocess.TimeoutExpired, FileNotFoundError, OSError) as e:
        info["error"] = repr(e)
    except Exception as e:  # 3.3：其他关键异常也捕获，保证恢复与落盘
        info["error"] = f"UNEXPECTED: {e!r}"
    finally:
        restore_refresh(serial, info, log)  # 3.1/3.2：内部自捕获，不向外抛
        try:
            (run_dir / "run_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
        except Exception as e:
            print(f"[{run_dir.name}] run_info write failed: {e!r}", flush=True)
        print(f"[{run_dir.name}] {info['status']}" + (f": {info.get('error')}" if info.get("error") else ""), flush=True)
    if info["status"] != "OK":
        sys.exit(1)


if __name__ == "__main__":
    main()

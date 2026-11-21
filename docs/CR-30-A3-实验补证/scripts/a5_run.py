# -*- coding: utf-8 -*-
"""A-5 快速 Tab 连点路由一致性因果实验：场景矩阵执行 + 时间线抓取（只读探针）。
用法: python a5_run.py <serial> <hz> <scenario> <rep>

R6 修订（GPT R6 任务一/二/四）：
- 字段语义（真机四快照验证，见 evidence/a5_dumpsys_redmi_{60,120}hz*.txt 与语义分析报告）：
  * PersistentDataStore.DisplayStates.RefreshRate ＝ 用户持久化偏好，**绝非活动刷新率**；
  * DisplayDeviceInfo{内置屏幕 uniqueId… modeId X … supportedModes[id→fps]} ＋
    mActiveModeId ＝ 当前活动显示模式（AOSP 语义；映射得活动 fps）；
  * mActiveSfDisplayMode / mActiveRenderFrameRate / renderFrameRate ＝ SF/渲染侧，非面板；
  * supportedRefreshRates / 模式列表本身 ≠ 活动模式。
- parse_display_refresh 按**显示设备分段**解析：目标内置屏（type INTERNAL/内置屏幕）
  必须唯一；活动模式字段必须绑定该设备；PDS/模式列表/渲染率仅记上下文。
  无法唯一确认活动模式 → INVALID、零点击。
- 证据落盘 fail-closed：dumpsys 命令失败/原始文件写入失败/原始文件为空/关键字段缺失
  → INVALID 或 FAILED、零点击；关键证据缺失绝不输出 OK。
- R5 保持：恢复失败一律 FAILED（primary_status/primary_error 保留）、落盘失败 exit=2。

注：本 ROM 真机验证发现 min/peak_refresh_rate 锁定不改变可观察活动模式（60/120 四快照
全字段静态、SF vsyncRate 恒 120）——60Hz 请求下活动模式判读为 120 时门禁将判 INVALID，
如实拦截（不冒充面板率）。
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
    "V2352A": {  # vivo：PENDING_VERIFICATION，核实前禁止矩阵（R3/R5 门禁保留）
        "nav": {"today": (515, 2621), "stats": (725, 2621), "calendar": (921, 2621)},
        "resolution": ("PENDING_VERIFICATION",),
    },
    "2407FRK8EC": {  # Redmi：bounds 实测中心
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


def parse_wm(wm_text):
    m = re.search(r"(\d+)x(\d+)", wm_text or "")
    return (int(m.group(1)), int(m.group(2))) if m else None


def parse_display_refresh(text):
    """R6 任务二：按显示设备结构分段解析，返回 (actual_fps|None, detail)。

    - 目标设备：DisplayDeviceInfo 块中 type INTERNAL 或名称 含 内置屏幕；必须唯一。
    - 活动模式：该设备块内 modeId，经同块 supportedModes id→fps 映射；mActiveModeId
      作为交叉核对（不一致→None）。缺字段/映射缺失/多内置屏 → None（无法确认）。
    - PersistentDataStore.DisplayStates.RefreshRate 仅记为持久化偏好，绝不作活动率。
    - mActiveSfDisplayMode / mActiveRenderFrameRate / renderFrameRate 记为渲染侧上下文。
    """
    detail = {}
    # 分段：以 DisplayDeviceInfo{ 为界的设备块
    chunks = re.split(r"(?=DisplayDeviceInfo\{)", text)
    dev_chunks = [c for c in chunks if c.startswith("DisplayDeviceInfo{")]
    internal = []
    for c in dev_chunks:
        head = c[:c.find("\n") if "\n" in c else len(c)]
        if ("type INTERNAL" in c[:2000]) or ("内置屏幕" in head):
            internal.append(c)
    detail["display_device_blocks"] = len(dev_chunks)
    detail["internal_blocks"] = len(internal)
    m_pds = re.search(r"PersistentDataStore:[\s\S]*?DisplayStates:[\s\S]*?RefreshRate=([\d.]+)", text)
    detail["PDS_DisplayStates.RefreshRate(持久化偏好，非活动率)"] = m_pds.group(1) if m_pds else None
    m_sf = re.search(r"mActiveSfDisplayMode=DisplayMode\{id=(\d+)[^}]*?peakRefreshRate=([\d.]+)", text)
    detail["mActiveSfDisplayMode(SF侧)"] = list(m_sf.groups()) if m_sf else None
    m_rfr = re.search(r"mActiveRenderFrameRate=([\d.]+)", text)
    detail["mActiveRenderFrameRate(渲染侧)"] = m_rfr.group(1) if m_rfr else None
    if len(internal) != 1:
        detail["reason"] = f"目标内置显示设备不唯一（internal_blocks={len(internal)}），无法确认活动刷新率"
        return None, detail
    dev = internal[0]
    m_uid = re.search(r'uniqueId="([^"]+)"', dev)
    detail["target_device"] = {"uniqueId": m_uid.group(1) if m_uid else None,
                               "internal": True}
    m_mode = re.search(r"modeId (\d+)", dev)
    mode_id = m_mode.group(1) if m_mode else None
    ms = re.search(r"supportedModes \[(.*?)\], colorMode", dev)
    mode_map = dict(re.findall(r"\{id=(\d+), width=\d+, height=\d+, fps=([\d.]+)", ms.group(1))) if ms else {}
    detail["device_modeId"] = mode_id
    detail["device_supportedModes_id_fps"] = mode_map
    m_am = re.search(r"mActiveModeId=(\S+)", text)
    active_id = m_am.group(1) if m_am else None
    detail["mActiveModeId(交叉核对)"] = active_id
    if mode_id is None:
        detail["reason"] = "目标设备缺活动模式字段（modeId），无法确认活动刷新率"
        return None, detail
    if active_id is not None and active_id != mode_id:
        detail["reason"] = f"modeId({mode_id}) 与 mActiveModeId({active_id}) 不一致，无法唯一确认"
        return None, detail
    if mode_id not in mode_map:
        detail["reason"] = f"活动模式 id={mode_id} 不在该设备 supportedModes 映射中，无法确认活动刷新率"
        return None, detail
    detail["active_mode_fps"] = mode_map[mode_id]
    detail["semantics"] = ("活动物理显示模式＝设备块 modeId 经本设备 supportedModes 映射；"
                           "PDS/模式列表/SF/渲染率仅上下文")
    return float(mode_map[mode_id]), detail


def restore_refresh(serial, info, log):
    """R5 保持：恢复失败一律视为失败（由调用方 fail-closed）。"""
    results = {}
    ok = True
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
                ok = False
                results[key] = f"FAILED: {action} rc={r.returncode}"
                info.setdefault("restore_failures", []).append(
                    {"key": key, "stage": "restore_cmd", "action": action,
                     "returncode": r.returncode,
                     "stderr": r.stderr.decode("utf-8", "replace")[-200:]})
                continue
            after = adb(serial, "shell", "settings", "get", "system", key)
            if after.returncode != 0:
                ok = False
                results[key] = f"FAILED: readback cmd rc={after.returncode}"
                info.setdefault("restore_failures", []).append(
                    {"key": key, "stage": "restore_readback_cmd",
                     "returncode": after.returncode,
                     "stderr": after.stderr.decode("utf-8", "replace")[-200:]})
                continue
            now = after.stdout.decode().strip()
            match = (now == before) or (before == "null" and now == "null")
            if not match:
                ok = False
                results[key] = f"FAILED: readback mismatch {action} -> after={now}"
                info.setdefault("restore_failures", []).append(
                    {"key": key, "stage": "restore_readback_value",
                     "before": before, "after": now})
            else:
                results[key] = f"{action} -> after={now} restore_ok=True"
        except Exception as e:
            ok = False
            results[key] = f"EXCEPTION: {e!r}"
            info.setdefault("restore_failures", []).append({"key": key, "stage": "restore_exception", "exception": repr(e)})
    info["env"]["refresh_restore"] = results
    return ok


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
    write_failed = False
    try:
        # ---- 设备识别与分辨率/坐标核对 ----
        model = must(serial, ["shell", "getprop", "ro.product.model"], "getprop", log).stdout.decode().strip()
        cfg = DEVICES.get(model)
        if cfg is None:
            raise Abort(f"unrecognized device model: {model!r} — refusing to apply any coordinates")
        wm = must(serial, ["shell", "wm", "size"], "wm size", log).stdout.decode().strip()
        info["env"].update({"model": model, "wm_size": wm, "nav": cfg["nav"]})
        if not any(res in wm for res in cfg["resolution"]):
            raise Abort(f"resolution not verified: got {wm!r}, expected one of {cfg['resolution']}")
        size = parse_wm(wm)
        if size is None:
            raise Abort(f"cannot parse wm size: {wm!r}")
        width, height = size
        for name, (x, y) in cfg["nav"].items():
            if not (0 <= x < width and 0 <= y < height):
                raise Abort(f"coordinate out of bounds: nav[{name}]=({x},{y}) for {width}x{height}")

        # ---- 刷新率：读取原值（成功读取才入恢复表）----
        for key in ("min_refresh_rate", "peak_refresh_rate"):
            r = must(serial, ["shell", "settings", "get", "system", key], f"get {key}", log)
            info["env"][f"{key}_before"] = r.stdout.decode().strip()
        must(serial, ["shell", "settings", "put", "system", "min_refresh_rate", hz], "set min_refresh_rate", log)
        must(serial, ["shell", "settings", "put", "system", "peak_refresh_rate", hz], "set peak_refresh_rate", log)

        # ---- settings 读回（与面板实际分开记录）----
        rb = {}
        for key in ("min_refresh_rate", "peak_refresh_rate"):
            r = must(serial, ["shell", "settings", "get", "system", key], f"readback {key}", log)
            rb[key] = r.stdout.decode().strip()
        info["env"]["settings_readback"] = rb
        if any(v != str(hz) for v in rb.values()):
            info["status"] = "INVALID"
            raise Abort(f"settings readback mismatch: requested={hz} readback={rb} —— 不作为有效样本")

        # ---- 任务四/二：dumpsys 证据采集 fail-closed + 分段解析 ----
        disp = adb(serial, "shell", "dumpsys", "display")
        if disp.returncode != 0:
            info["status"] = "INVALID"
            raise Abort(f"dumpsys display failed rc={disp.returncode} —— 关键证据缺失，零采样")
        disp_text = disp.stdout.decode("utf-8", "replace")
        if not disp_text.strip():
            info["status"] = "INVALID"
            raise Abort("dumpsys display empty —— 关键证据缺失，零采样")
        try:
            (run_dir / "dumpsys_display.txt").write_text(disp_text, encoding="utf-8")
        except Exception as e:
            info["status"] = "FAILED"
            raise Abort(f"dumpsys raw write failed: {e!r} —— 证据未落盘，零采样")
        raw_ok = (run_dir / "dumpsys_display.txt").exists() and (run_dir / "dumpsys_display.txt").stat().st_size > 0
        if not raw_ok:
            info["status"] = "FAILED"
            raise Abort("dumpsys raw file missing/empty after write —— 零采样")
        actual, detail = parse_display_refresh(disp_text)
        info["env"]["display_parse"] = detail
        info["env"]["actual_active_display_fps"] = actual
        info["env"]["refresh_evidence"] = ("dumpsys display 按显示设备分段解析（活动模式＝设备块 "
                                          "modeId→supportedModes 映射，mActiveModeId 交叉核对）；"
                                          "PDS.RefreshRate=持久化偏好；SF/渲染率字段仅上下文")
        match = actual is not None and abs(actual - float(hz)) <= 1.0
        info["env"]["refresh_match_request"] = match
        if actual is None:
            info["status"] = "INVALID"
            raise Abort(f"active display refresh unidentifiable: {detail.get('reason')} —— 零采样")
        if not match:
            info["status"] = "INVALID"
            raise Abort(f"refresh mismatch: requested={hz} settings_readback={rb} "
                        f"actual_active_fps={actual} —— 不作为有效样本")

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
        if info["status"] != "INVALID":
            info["status"] = "FAILED"
        info["error"] = repr(e)
    except Exception as e:
        if info["status"] != "INVALID":
            info["status"] = "FAILED"
        info["error"] = f"UNEXPECTED: {e!r}"
    finally:
        # ---- R5-1 保持：primary 保留 + 恢复失败一律 FAILED ----
        info["primary_status"] = info["status"]
        info["primary_error"] = info.get("error")
        try:
            restore_ok = restore_refresh(serial, info, log)
        except Exception as e:
            restore_ok = False
            info.setdefault("restore_failures", []).append({"key": "*", "stage": "restore_outer", "exception": repr(e)})
        info["restore_ok"] = restore_ok
        if not restore_ok:
            info["status"] = "FAILED"
            info["error"] = ((info.get("error") or "") + " | refresh_restore_failed").strip(" |")
        try:
            (run_dir / "run_info.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
        except Exception as e:
            write_failed = True
            info["status"] = "FAILED"
            try:
                (run_dir / "FAILED_write.txt").write_text(
                    f"run_info write failed: {e!r}\nerror={info.get('error')}\n"
                    f"restore={info['env'].get('refresh_restore')}\n", encoding="utf-8")
            except Exception:
                pass
            print(f"[{run_dir.name}] run_info WRITE FAILED: {e!r}", file=sys.stderr)
        print(f"[{run_dir.name}] {info['status']}" + (f": {info.get('error')}" if info.get("error") else ""), flush=True)
    if write_failed:
        sys.exit(2)
    if info["status"] != "OK":
        sys.exit(1)


if __name__ == "__main__":
    main()

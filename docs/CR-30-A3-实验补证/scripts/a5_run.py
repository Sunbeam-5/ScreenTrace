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
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.screentrace.app"
OUT = Path(r"C:\tmp\cr30_a5\raw")

DEVICES = {
    "V2352A": {  # vivo：PENDING_VERIFICATION，核实前禁止矩阵（R3/R5 门禁保留）
        "nav": {"today": (515, 2621), "stats": (725, 2621), "calendar": (921, 2621)},
        "resolution": ("PENDING_VERIFICATION",),
    },
    "2407FRK8EC": {  # Redmi：bounds 实测中心
        # 「今日」坐标取收起态边界 [251,420]∩展开态 [251,605] 的交集内（x=335 全态命中）；
        # 旧值 x=503 仅在今日展开态命中，收起/他态会误点「统计」（R9 实测发现，见补验报告）。
        "nav": {"today": (335, 2493), "stats": (703, 2493), "calendar": (885, 2493)},
        # 横滑手势：(x1, y1, x2, y2, duration_ms)；y 取内容区中部（避开顶栏与底部导航）。
        # fast_two_page：从右向左大位移快滑（今日→日历方向）；fast_back：反向快滑（打断程序化滚动）。
        "swipe": {
            "fast_two_page": (1150, 1200, 90, 1200, 90),
            "fast_back": (150, 1200, 1150, 1200, 90),
        },
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

# A-5 R10 真机复核（GPT 放行的受控范围）：横滑场景。
#
# 事实约束（真机实测，见 evidence/a5_r10_guard_ownership_real/）：
#   * 屏幕宽 1220px，HorizontalPager 用默认 flingBehavior（snapDistance=atMost(1)），
#     单个人类手势**最多跨一页**；跨两页需要 >=1.5 页宽（~1830px）——超出屏宽，
#     故「跨两页」只能用「单手势连过两个 50%% 阈值」（越界长拖：currentPage 0->1->0）
#     或「连续两次快滑」构造。这是合成手势，与人手不同，报告中如实标注。
#   * y=700 是实测有效的手势区（y=1200 被页面内控件吞掉，无任何页面变化）。
#   * adb 主机往返 ~150ms/命令，会错过 OBS-1 的亚帧时序窗口 → 被测手势改为
#     **设备侧单条 shell 串行执行**（不回主机），串内嵌 date +%%s.%%N 记录设备端时刻。
#
# s7 —— 快速横滑跨两页：检验 OBS-1（快速横滑时 currentPage != target 可能成立 →
#       意外建立程序化 owner / 发射 A5/routeScroll）。
# s8 —— 点 tab 触发程序化滚动后立即横滑 → 打断程序化滚动（检验最终路由是否跟随
#       Pager 实际停留页；判别性：点 tab 的目标页与打断后 Pager 实际停留页不同）。
SETUP_SCENARIOS = {
    # 前置：把 Pager 归一到确定起手页（今日=第 0 页），前置动作不计入被测窗口
    # （录屏与 logcat 都在 setup 之后才启动）。
    "s7_swipe_fast": [("tap", "today", 0)],
    "s8_swipe_interrupt": [("tap", "today", 0)],
}

SWIPE_SCENARIOS = {
    "s7_swipe_fast": [
        ("shell", "date +%s.%N; input swipe 1150 700 -600 700 150; date +%s.%N; "
                  "input swipe 1150 700 90 700 80; date +%s.%N", 0),
    ],
    "s8_swipe_interrupt": [
        ("shell", "date +%s.%N; input tap 885 2493; date +%s.%N; sleep 0.12; "
                  "input swipe 150 700 1150 700 80; date +%s.%N", 0),
    ],
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
    """R7/P1：按显示设备结构分段解析，mActiveModeId **绑定目标设备**，返回 (actual_fps|None, detail)。

    规则（R7）：
    - 以 DisplayDeviceInfo{ 为界分段；每段归属其前方设备；uniqueId 必须存在且非空。
    - 目标设备：type INTERNAL 或名称含内置屏幕，必须唯一；外接/虚拟屏字段不得补全内置屏。
    - mActiveModeId 必须取自**目标设备自己的分段**且恰好一个；全局存在无法归属的
      mActiveModeId（孤儿）→ 无法确认。缺失/多值/归属不明 → None。
    - 目标设备 modeId 与自身 mActiveModeId 必须一致；活动模式 id 必须在**该设备自身**
      supportedModes 中唯一映射（不同设备同 id 不同 fps 时各用各的映射）。
    - PersistentDataStore.DisplayStates.RefreshRate=持久化偏好，绝不作活动率；
      SF/渲染率字段仅上下文。
    """
    detail = {}
    chunks = re.split(r"(?=DisplayDeviceInfo\{)", text)
    dev_chunks = [c for c in chunks if c.startswith("DisplayDeviceInfo{")]
    devices = []
    for c in dev_chunks:
        head = c[:c.find("\n") if "\n" in c else len(c)]
        m_uid = re.search(r'uniqueId="([^"]*)"', c)
        m_mode = re.search(r"\bmodeId (\d+)", c)
        ms = re.search(r"supportedModes \[(.*?)\], colorMode", c)
        # R8：保留原始模式条目列表（不转 dict——dict 会静默吞掉重复 id）
        mode_entries = re.findall(r"\{id=(\d+), width=\d+, height=\d+, fps=([\d.]+)", ms.group(1)) if ms else []
        devices.append({
            "chunk": c,
            "internal": ("type INTERNAL" in c[:3000]) or ("内置屏幕" in head),
            "uniqueId": m_uid.group(1) if m_uid else None,
            "modeId": m_mode.group(1) if m_mode else None,
            "mode_entries": mode_entries,
            "actives": re.findall(r"mActiveModeId=(\S+)", c),
        })
    detail["display_device_blocks"] = len(devices)
    m_pds = re.search(r"PersistentDataStore:[\s\S]*?DisplayStates:[\s\S]*?RefreshRate=([\d.]+)", text)
    detail["PDS_DisplayStates.RefreshRate(持久化偏好，非活动率)"] = m_pds.group(1) if m_pds else None
    m_sf = re.search(r"mActiveSfDisplayMode=DisplayMode\{id=(\d+)[^}]*?peakRefreshRate=([\d.]+)", text)
    detail["mActiveSfDisplayMode(SF侧)"] = list(m_sf.groups()) if m_sf else None
    m_rfr = re.search(r"mActiveRenderFrameRate=([\d.]+)", text)
    detail["mActiveRenderFrameRate(渲染侧)"] = m_rfr.group(1) if m_rfr else None

    # 孤儿 mActiveModeId（无法归属任何设备块）→ 无法确认
    total_active = len(re.findall(r"mActiveModeId=", text))
    bound_active = sum(len(d["actives"]) for d in devices)
    detail["mActiveModeId_total"] = total_active
    detail["mActiveModeId_bound_to_device_blocks"] = bound_active
    if total_active != bound_active:
        detail["reason"] = f"存在无法归属目标设备结构的 mActiveModeId（total={total_active} bound={bound_active}）"
        return None, detail

    internal = [d for d in devices if d["internal"]]
    detail["internal_blocks"] = len(internal)
    if len(internal) != 1:
        detail["reason"] = f"目标内置显示设备不唯一（internal_blocks={len(internal)}），无法确认活动刷新率"
        return None, detail
    tgt = internal[0]
    detail["target_device"] = {"uniqueId": tgt["uniqueId"], "internal": True}
    if not tgt["uniqueId"]:
        detail["reason"] = "目标设备 uniqueId 缺失或为空，无法确认活动刷新率"
        return None, detail
    if len(tgt["actives"]) != 1:
        detail["reason"] = f"目标设备 mActiveModeId 缺失或多值（count={len(tgt['actives'])}），无法确认活动刷新率"
        return None, detail
    active_id = tgt["actives"][0]
    detail["target_device"]["mActiveModeId"] = active_id
    if tgt["modeId"] is None:
        detail["reason"] = "目标设备缺活动模式字段（modeId），无法确认活动刷新率"
        return None, detail
    if active_id != tgt["modeId"]:
        detail["reason"] = f"目标设备 modeId({tgt['modeId']}) 与自身 mActiveModeId({active_id}) 不一致，无法唯一确认"
        return None, detail
    # R8：supportedModes 唯一映射校验——活动 id 必须恰好出现一次（重复即 INVALID，
    # 即使 fps 相同；不以 dict 覆盖/set 去重掩盖重复）
    counts = {}
    for mid, _fp in tgt["mode_entries"]:
        counts[mid] = counts.get(mid, 0) + 1
    dup = {m: n for m, n in counts.items() if n > 1}
    detail["device_mode_entries"] = tgt["mode_entries"]        # 原始条目（含重复）
    detail["device_modeId_counts"] = counts                    # 每个 id 出现次数
    detail["duplicate_mode_ids"] = dup                         # 重复 id 及次数
    n_active = counts.get(tgt["modeId"], 0)
    detail["active_mode_id_occurrences"] = n_active
    if n_active == 0:
        detail["reason"] = f"活动模式 id={tgt['modeId']} 在该设备 supportedModes 出现零次，映射缺失，无法确认"
        return None, detail
    if n_active != 1:
        fps_list = [fp for m, fp in tgt["mode_entries"] if m == tgt["modeId"]]
        detail["active_mode_id_fps_entries"] = fps_list
        detail["reason"] = (f"活动模式 id={tgt['modeId']} 在该设备 supportedModes 出现 {n_active} 次"
                            f"（fps 条目={fps_list}），映射非唯一，无法确认——即使重复条目 fps 相同亦不放行")
        return None, detail
    fps_unique = next(fp for m, fp in tgt["mode_entries"] if m == tgt["modeId"])
    detail["device_modeId"] = tgt["modeId"]
    detail["active_mode_fps"] = fps_unique
    detail["semantics"] = ("活动物理显示模式＝目标设备块 modeId 与自身 mActiveModeId 一致后，"
                           "经该设备自身 supportedModes 映射；PDS/模式列表/SF/渲染率仅上下文")
    return float(fps_unique), detail


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


def parse_selected_tab(xml_text):
    # 从 uiautomator dump 里取当前**选中**的底部 tab（今日/统计/日历）。
    # 选中态落在导航带里那个可点击容器节点上（selected=true），tab 名在其子节点的
    # content-desc 或 text 上；按「导航带容器 + selected + 内部 label」判定，
    # 不依赖 label 一定出现在 content-desc 上（选中项展开时 label 的呈现方式会变）。
    try:
        root = ET.fromstring(xml_text)
    except Exception:
        return None
    tabs = []
    for node in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", "") or "")
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        if 2400 <= y1 <= 2430 and 2550 <= y2 <= 2600 and (x2 - x1) > 100:
            tabs.append((x1, node))
    tabs.sort(key=lambda it: it[0])
    for idx, (x1, node) in enumerate(tabs):
        if node.get("selected") != "true":
            continue
        for child in node.iter("node"):
            d = child.get("content-desc") or child.get("text") or ""
            if d in ("今日", "统计", "日历"):
                return d
        return "nav_slot_%d" % idx
    return None


def main():
    serial, hz, scen, rep = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    if scen in SCENARIOS:
        actions = [("tap", name, gap) for (name, gap) in SCENARIOS[scen]]
        setup_actions = []
    elif scen in SWIPE_SCENARIOS:
        actions = list(SWIPE_SCENARIOS[scen])
        setup_actions = list(SETUP_SCENARIOS.get(scen, []))
    else:
        raise Abort(f"unknown scenario: {scen!r} —— 不执行")
    log = []
    run_dir = OUT / f"{scen}_{hz}hz_r{rep}"
    if run_dir.exists() and any(run_dir.iterdir()):
        raise Abort(f"round dir exists with content (refusing to overwrite): {run_dir}")
    run_dir.mkdir(parents=True, exist_ok=True)
    info = {"serial": serial, "hz_requested": hz, "scenario": scen, "rep": rep,
            "taps": [], "swipes": [], "shell_marks": [], "setup": [],
            "env": {}, "failures": log, "status": "FAILED"}
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

        # ---- R10 真机复核修正：活动态前置采样 ----
        # 面板在 idle 态常驻 60Hz；只有唤醒屏幕并让 App 进入前台后才会切入目标模式。
        # 门禁 dumpsys 必须在**活动态**采集（与后续交互同一时窗），否则会把 60Hz idle
        # 误判成「请求 120Hz 未生效」→ 整轮 INVALID、零采样。门禁语义不变（仍要求活动
        # 模式唯一映射到目标 fps），只改采样时点。
        time.sleep(0.5)
        must(serial, ["shell", "input", "keyevent", "KEYCODE_WAKEUP"], "wakeup", log)
        must(serial, ["shell", "am", "force-stop", PKG], "force-stop", log)
        time.sleep(1)
        must(serial, ["shell", "am", "start", "-n", f"{PKG}/.MainActivity"], "start", log)
        time.sleep(6)

        # ---- 任务四/二：dumpsys 证据采集 fail-closed + 分段解析（活动态）----
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
                                          "PDS.RefreshRate=持久化偏好；SF/渲染率字段仅上下文；"
                                          "采样时点＝活动态（屏幕已唤醒、App 在前台），与交互同一时窗")
        match = actual is not None and abs(actual - float(hz)) <= 1.0
        info["env"]["refresh_match_request"] = match
        if actual is None:
            info["status"] = "INVALID"
            raise Abort(f"active display refresh unidentifiable: {detail.get('reason')} —— 零采样")
        if not match:
            info["status"] = "INVALID"
            raise Abort(f"refresh mismatch: requested={hz} settings_readback={rb} "
                        f"actual_active_fps={actual} —— 不作为有效样本")

        # ---- 场景前置（setup）：归一起手页；不计入被测窗口 ----
        for i, (kind, name, gap_ms) in enumerate(setup_actions):
            if kind != "tap":
                raise Abort(f"setup action must be tap, got {kind!r}")
            x, y = cfg["nav"][name]
            must(serial, ["shell", "input", "tap", str(x), str(y)], f"setup{i}:{name}", log)
            info["setup"].append({"i": i, "target": name, "x": x, "y": y})
            time.sleep(0.2)
        if setup_actions:
            time.sleep(2.5)   # 等程序化滚动落定
            try:
                ui0 = adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui_setup.xml")
                if ui0.returncode == 0:
                    xml0 = adb(serial, "exec-out", "cat", "/sdcard/ui_setup.xml").stdout.decode("utf-8", "replace")
                    (run_dir / "ui_setup.xml").write_text(xml0, encoding="utf-8")
                    info["env"]["setup_tab"] = parse_selected_tab(xml0)
            except Exception as e:
                info.setdefault("ui_dump_failures", []).append("setup:" + repr(e))

        rec = subprocess.Popen(["adb", "-s", serial, "shell", "screenrecord",
                                "--time-limit", "10", f"/sdcard/{run_dir.name}.mp4"])
        time.sleep(1)
        must(serial, ["logcat", "-c"], "logcat clear", log)
        t0 = time.monotonic()
        prev_plan = t0
        for i, (kind, name, gap_ms) in enumerate(actions):
            plan = prev_plan + (gap_ms / 1000.0 if i > 0 else 0)
            delay = plan - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            host = time.monotonic()
            if kind == "tap":
                x, y = cfg["nav"][name]
                must(serial, ["shell", "input", "tap", str(x), str(y)], f"tap{i}:{name}", log)
                done = time.monotonic()
                info["taps"].append({"i": i, "target": name, "x": x, "y": y,
                                     "plan_offset_ms": round((plan - t0) * 1000, 1),
                                     "host_initiated_offset_ms": round((host - t0) * 1000, 1),
                                     "deviation_ms": round((host - plan) * 1000, 1),
                                     "cmd_duration_ms": round((done - host) * 1000, 1)})
            elif kind == "swipe":
                sw = cfg.get("swipe", {}).get(name)
                if sw is None:
                    raise Abort(f"device {model!r} has no swipe gesture {name!r} —— 拒绝盲发坐标")
                x1, y1, x2, y2, dur = sw
                must(serial, ["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(dur)],
                     f"swipe{i}:{name}", log)
                done = time.monotonic()
                info["swipes"].append({"i": i, "gesture": name, "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                                       "duration_ms": dur,
                                       "plan_offset_ms": round((plan - t0) * 1000, 1),
                                       "host_initiated_offset_ms": round((host - t0) * 1000, 1),
                                       "deviation_ms": round((host - plan) * 1000, 1),
                                       "cmd_duration_ms": round((done - host) * 1000, 1)})
            elif kind == "shell":
                cmd = name
                r = must(serial, ["shell", cmd], f"shell{i}", log)
                done = time.monotonic()
                marks = r.stdout.decode("utf-8", "replace").split()
                info["shell_marks"].append({
                    "i": i, "cmd": cmd, "device_marks": marks,
                    "plan_offset_ms": round((plan - t0) * 1000, 1),
                    "host_initiated_offset_ms": round((host - t0) * 1000, 1),
                    "deviation_ms": round((host - plan) * 1000, 1),
                    "cmd_duration_ms": round((done - host) * 1000, 1)})
            else:
                raise Abort(f"unknown action kind: {kind!r}")
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
        # 最终页面与选中 tab（GPT 真机复核要求项）：录屏结束后再 dump，避免干扰录制
        try:
            ui = adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
            if ui.returncode == 0:
                xml = adb(serial, "exec-out", "cat", "/sdcard/ui.xml").stdout.decode("utf-8", "replace")
                (run_dir / "ui_final.xml").write_text(xml, encoding="utf-8")
                info["env"]["selected_tab"] = parse_selected_tab(xml)
        except Exception as e:
            info.setdefault("ui_dump_failures", []).append(repr(e))
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

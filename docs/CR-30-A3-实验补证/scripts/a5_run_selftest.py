# -*- coding: utf-8 -*-
"""a5_run.py R6 自测（mock adb + 真实 Redmi dumpsys fixture）。
用法: python a5_run_selftest.py   （全部通过 exit 0；任一失败 exit 1）

断言最终 status、退出码、失败记录、识别值与设备身份；INVALID 必须零点击。
真实 fixture：scripts/fixtures/dumpsys_redmi_{idle,active}_{60,120}hz.txt（真机原样）。
场景 1–9 由真实 fixture 做单点变异派生（非人工拼接短字符串）。

覆盖：
 t1/t2   正常完成（活动模式识别=120、恢复成功、exit 0）
 t3/t4a/t4b/t5r/t6/t7/t8/t9a/t9b/t10/t12/t13/t14/t15  R5 既有场景（断言不放宽）
 t11/t23 活动模式与请求不符→INVALID 零点击
 t16/t17/t18  INVALID+恢复失败三态→FAILED、primary 保留
 t20/t30  缺活动模式字段/只有模式列表→INVALID
 t21/t34  双内置屏/映射缺失→INVALID
 t22     settings 读回≠请求→INVALID
 t32     PDS=120 但活动=60（fixture 变异）→识别 60（PDS 不冒充）
 t33     内置屏字段缺失（fixture 变异）→INVALID
 t35     modeId 与 mActiveModeId 不一致→INVALID
 t40/t41 真实 fixture 解析（60/120 各两份）：识别 120.00001、设备 local:4627…、PDS=60 仅上下文
 t42     真实 fixture 走完整 main（请求 60）→INVALID 零点击
 t43/t44/t45  dumpsys 命令失败/空输出/原始写入失败→INVALID 或 FAILED 零点击
"""
import json
import re
import sys
import tempfile
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import a5_run  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures"


class FakeResult:
    def __init__(self, rc=0, out=b"", err=b""):
        self.returncode = rc
        self.stdout = out
        self.stderr = err


class FakePopen:
    raise_on_wait = None
    killed = False

    def __init__(self, *a, **k):
        self._rc = 0

    def wait(self, timeout=None):
        if FakePopen.raise_on_wait is not None:
            exc = FakePopen.raise_on_wait
            FakePopen.raise_on_wait = None
            raise exc
        return self._rc

    def kill(self):
        FakePopen.killed = True
        self._rc = -9


class Scripted:
    def __init__(self, script, calls):
        self.script = dict(script)
        self.calls = calls
        self.seq = {}

    def adb(self, serial, *args, timeout=90):
        flat = tuple(args)
        for a in flat:
            assert isinstance(a, str), f"nested/non-str adb arg: {flat!r}"
        self.calls.append(flat)
        joined = " ".join(flat)
        for key, val in self.script.items():
            if all(k in joined for k in key.split("|")):
                n = self.seq.get(key, 0)
                self.seq[key] = n + 1
                v = val[n] if isinstance(val, list) else val
                if isinstance(v, Exception):
                    raise v
                return v
        return FakeResult()


# 合成基线（新字段语义结构；真实兼容性由 fixture 用例证明）
DEV_BASE = (
    b'DisplayDeviceInfo{"built-in", uniqueId="local:1", 1220 x 2712, modeId 3, '
    b'renderFrameRate 120.00001, type INTERNAL, supportedModes '
    b'[{id=1, width=1220, height=2712, fps=60.0}, {id=3, width=1220, height=2712, fps=120.00001}], '
    b'colorMode 0, state ON}\n'
    b'mActiveModeId=3\n'
    b'mActiveSfDisplayMode=DisplayMode{id=2, peakRefreshRate=120.00001}\n'
    b'mActiveRenderFrameRate=120.00001\n'
    b'PersistentDataStore:\n  DisplayStates:\n    0: local:1\n      RefreshRate=60.0\n'
)

BASE = {
    "getprop": FakeResult(out=b"2407FRK8EC"),
    "wm size": FakeResult(out=b"Physical size: 1220x2712"),
    "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"60")],
    "settings|get|peak_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"60")],
    "dumpsys|display": FakeResult(out=DEV_BASE),
    "logcat|-d": FakeResult(out=b"CR30Probe: A5/tabClick=1#1\n"),
    "exec-out|cat": FakeResult(out=b"\x00\x00\x00\x18ftypmp42"),
}


def run_case(script=None, pre=None, argv=None, patch_json=None, popen_raise=None, patch_raw_write=False):
    tmp = tempfile.mkdtemp(prefix="a5st_")
    calls = []
    FakePopen.raise_on_wait = popen_raise
    FakePopen.killed = False
    a5_run.OUT = Path(tmp)
    a5_run.time = types.SimpleNamespace(
        monotonic=(lambda c=[0.0]: (c.__setitem__(0, c[0] + 0.01) or c[0])),
        sleep=lambda s: None)
    import subprocess as _sp
    a5_run.subprocess = types.SimpleNamespace(run=None, Popen=FakePopen, TimeoutExpired=_sp.TimeoutExpired, FileNotFoundError=FileNotFoundError)
    s = dict(BASE)
    s.update(script or {})
    sc = Scripted(s, calls)
    a5_run.adb = sc.adb
    real_json = a5_run.json
    if patch_json:
        a5_run.json = types.SimpleNamespace(dumps=patch_json)
    import pathlib as _pl
    real_write = _pl.Path.write_text
    if patch_raw_write:
        def bad_write(self, data, **k):
            if self.name == "dumpsys_display.txt":
                raise OSError("simulated raw write failure")
            return real_write(self, data, **k)
        _pl.Path.write_text = bad_write
    if pre:
        pre(Path(tmp))
    sys.argv = argv or ["a5_run.py", "fake-serial", "120", "s1_single", "1"]
    code = 0
    try:
        a5_run.main()
    except SystemExit as e:
        code = e.code if e.code is not None else 0
    except a5_run.Abort as e:
        code = f"Abort:{e}"
    except Exception as e:
        code = f"{type(e).__name__}:{e!r}"
    _pl.Path.write_text = real_write
    a5_run.json = real_json
    info = None
    for p in Path(tmp).glob("*/run_info.json"):
        info = json.loads(p.read_text(encoding="utf-8"))
    return code, calls, Path(tmp), info


def timeout_exc():
    import subprocess as sp
    return sp.TimeoutExpired(cmd="screenrecord", timeout=30)


def fx(name):
    return (FIXTURES / name).read_text(encoding="utf-8", errors="replace")


def no_taps(calls):
    return not any((len(c) > 3 and c[2] == "tap") for c in calls)


def main():
    results = []

    code, calls, tmp, info = run_case()
    ok = (code == 0 and info and info["status"] == "OK" and info["restore_ok"] is True
          and info["env"]["actual_active_display_fps"] == 120.00001
          and info["env"]["refresh_match_request"] is True
          and info["env"]["display_parse"]["target_device"]["internal"] is True)
    results.append(("t1/t2 正常完成（识别120.00001+设备身份+恢复+exit0）", ok, code, info))

    code, calls, tmp, info = run_case(script={"input|tap": FakeResult(rc=1, err=b"tap fail")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is True
          and info.get("restore_failures") is None)  # R5 断言恢复：恢复成功且无失败记录
    results.append(("t3 Abort 后恢复 status=FAILED exit=1（R5: 恢复成功+无失败记录）", ok, code, info))

    code, calls, tmp, info = run_case(popen_raise=timeout_exc())
    ok = (code == 1 and info and info["status"] == "FAILED" and FakePopen.killed)
    results.append(("t4a 录屏超时 kill→FAILED exit=1", ok, code, info))

    code, calls, tmp, info = run_case(script={"exec-out|cat": FileNotFoundError("adb gone")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is True)
    results.append(("t4b FileNotFoundError→FAILED exit=1", ok, code, info))

    code, calls, tmp, info = run_case(script={"settings|put|min_refresh_rate|60": FakeResult(rc=1, err=b"denied")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and info["env"]["refresh_match_request"] is True  # R5 断言恢复：主体刷新率匹配
          and any(f["key"] == "min_refresh_rate" for f in info.get("restore_failures", [])))
    results.append(("t5r 恢复put失败→FAILED+restore_failures（R5: 主体匹配+恢复失败）", ok, code, info))

    code, calls, tmp, info = run_case(script={
        "settings|put|min_refresh_rate|60": TypeError("simulated TypeError on restore")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and any(f.get("stage") == "restore_exception" for f in info.get("restore_failures", [])))
    results.append(("t6 恢复异常→FAILED+restore_ok=False+记录 exit=1", ok, code, info))

    code, calls, tmp, info = run_case(script={"settings|get|min_refresh_rate": [FakeResult(out=b"null"), FakeResult(out=b"120"), FakeResult(out=b"null")]})
    ok = (any(c[:4] == ("shell", "settings", "delete", "system") for c in calls)
          and "delete" in info["env"]["refresh_restore"]["min_refresh_rate"])
    results.append(("t7 原值null→settings delete", ok, code, info))

    code, calls, tmp, info = run_case(script={"settings|get|peak_refresh_rate": FakeResult(rc=1, err=b"no read")})
    ok = ("skipped" in info["env"]["refresh_restore"]["peak_refresh_rate"]
          and not any(c[:5] == ("shell", "settings", "put", "system", "peak_refresh_rate") for c in calls))
    results.append(("t8 原值读取失败→跳过恢复", ok, code, info))

    code, calls, tmp, info = run_case(script={"getprop": FakeResult(out=b"V2352A")})
    ok = (code == 1 and info and info["status"] == "FAILED" and no_taps(calls))
    results.append(("t9a vivo PENDING 拒绝零点击", ok, code, info))

    def patch_nav(p):
        a5_run.DEVICES["2407FRK8EC"]["nav"]["today"] = (99999, 0)
    code, calls, tmp, info = run_case(pre=patch_nav)
    a5_run.DEVICES["2407FRK8EC"]["nav"]["today"] = (335, 2493)
    ok = (code == 1 and info and info["status"] == "FAILED" and no_taps(calls))
    results.append(("t9b 越界坐标拒绝零点击", ok, code, info))

    def pre_fill(p):
        d = p / "s1_single_120hz_r1"
        d.mkdir(parents=True)
        (d / "old.txt").write_text("keep me")
    code, calls, tmp, info = run_case(pre=pre_fill)
    ok = (isinstance(code, str) and "Abort" in code
          and (Path(tmp) / "s1_single_120hz_r1" / "old.txt").exists())
    results.append(("t10 防覆盖（材料保留）", ok, code, info))

    # t11/t23 活动模式不符（modeId 1=60fps）→INVALID 零点击
    dev60 = DEV_BASE.replace(b"modeId 3", b"modeId 1").replace(b"mActiveModeId=3", b"mActiveModeId=1")
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=dev60)})
    ok = (code == 1 and info and info["status"] == "INVALID"
          and info["env"]["actual_active_display_fps"] == 60.0
          and info["env"]["refresh_match_request"] is False and no_taps(calls))
    results.append(("t11/t23 活动率不符→INVALID 零点击（识别值=60断言）", ok, code, info))

    # t16/17/18 INVALID+恢复失败三态
    code, calls, tmp, info = run_case(script={
        "dumpsys|display": FakeResult(out=dev60),
        "settings|put|min_refresh_rate|60": FakeResult(rc=1, err=b"denied")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["primary_status"] == "INVALID"
          and "refresh mismatch" in str(info.get("primary_error")) and info["restore_ok"] is False)
    results.append(("t16 INVALID+恢复put失败→FAILED+primary保留", ok, code, info))

    code, calls, tmp, info = run_case(script={
        "dumpsys|display": FakeResult(out=dev60),
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(rc=1, err=b"gone")]})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["primary_status"] == "INVALID"
          and any(f.get("stage") == "restore_readback_cmd" for f in info.get("restore_failures", [])))
    results.append(("t17 INVALID+恢复读回失败→FAILED", ok, code, info))

    code, calls, tmp, info = run_case(script={
        "dumpsys|display": FakeResult(out=dev60),
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"55")]})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["primary_status"] == "INVALID"
          and any(f.get("stage") == "restore_readback_value" for f in info.get("restore_failures", [])))
    results.append(("t18 INVALID+恢复值不一致→FAILED", ok, code, info))

    # t20 缺活动模式字段；t30 只有模式列表
    dev_nomode = DEV_BASE.replace(b"modeId 3, ", b"")  # 仅删 modeId，保留 mActiveModeId（专测活动模式字段缺失）
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=dev_nomode)})
    ok = (code == 1 and info and info["status"] == "INVALID" and no_taps(calls)
          and "活动模式字段" in str(info["env"]["display_parse"].get("reason", "")))
    results.append(("t20/t30 缺活动模式字段/仅模式列表→INVALID 零点击", ok, code, info))

    # t21/t34 双内置屏
    dev_dual = DEV_BASE + DEV_BASE
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=dev_dual)})
    ok = (code == 1 and info and info["status"] == "INVALID" and no_taps(calls)
          and "不唯一" in str(info["env"]["display_parse"].get("reason", "")))
    results.append(("t21/t34 双内置屏→INVALID 零点击", ok, code, info))

    # t22 settings 读回≠请求
    code, calls, tmp, info = run_case(script={
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"55"), FakeResult(out=b"60")]})
    ok = (code == 1 and info and info["status"] == "INVALID" and no_taps(calls)
          and "settings readback mismatch" in str(info.get("primary_error")))
    results.append(("t22 settings 读回≠请求→INVALID 零点击", ok, code, info))

    # t12/t13/t14/t15（R5 保持）
    code, calls, tmp, info = run_case(script={
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"55")]})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and any(f.get("stage") == "restore_readback_value" for f in info.get("restore_failures", [])))
    results.append(("t12 恢复读回不一致→FAILED+restore_ok=False", ok, code, info))

    code, calls, tmp, info = run_case(script={
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(rc=1, err=b"gone")]})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and any(f.get("stage") == "restore_readback_cmd" for f in info.get("restore_failures", [])))
    results.append(("t13 恢复读回命令失败→FAILED+restore_ok=False", ok, code, info))

    code, calls, tmp, info = run_case(patch_json=lambda *a, **k: (_ for _ in ()).throw(TypeError("sim write")))
    fw = list(Path(tmp).glob("*/FAILED_write.txt"))
    ok = (code == 2 and fw and fw[0].exists() and info is None)
    results.append(("t14 run_info 写入失败→exit=2+FAILED_write.txt", ok, code, info))

    code, calls, tmp, info = run_case(script={"settings|put|peak_refresh_rate|60": FakeResult(rc=1, err=b"denied")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and any(f["key"] == "peak_refresh_rate" for f in info.get("restore_failures", []))
          and "restore_ok=True" in info["env"]["refresh_restore"]["min_refresh_rate"])  # R5: 他键恢复成功
    results.append(("t15 单键恢复失败→FAILED+restore_ok=False（他键成功）", ok, code, info))

    # ---- 真实 fixture 用例 ----
    for name in ("dumpsys_redmi_idle_60hz.txt", "dumpsys_redmi_idle_120hz.txt",
                 "dumpsys_redmi_active_60hz.txt", "dumpsys_redmi_active_120hz.txt"):
        actual, detail = a5_run.parse_display_refresh(fx(name))
        ok = (actual == 120.00001 and detail["target_device"]["uniqueId"] == "local:4627039422300187648"
              and detail["PDS_DisplayStates.RefreshRate(持久化偏好，非活动率)"] == "60.0")
        results.append((f"t40/41 真实fixture解析 {name}→识别120.00001+PDS仅上下文", ok, None, detail))

    # t32 PDS=120 但活动=60（真实 fixture 单点变异）——PDS 不冒充
    t = fx("dumpsys_redmi_active_60hz.txt")
    t = re.sub(r"(DisplayStates:[\s\S]*?RefreshRate=)[\d.]+", r"\g<1>120.0", t, count=1)
    t = t.replace("modeId 3, renderFrameRate 120.00001", "modeId 1, renderFrameRate 120.00001", 1)
    t = t.replace("mActiveModeId=3", "mActiveModeId=1", 1)
    actual, detail = a5_run.parse_display_refresh(t)
    ok = (actual == 60.0 and detail["PDS_DisplayStates.RefreshRate(持久化偏好，非活动率)"] == "120.0")
    results.append(("t32 PDS=120/活动=60→识别60（PDS 不冒充）", ok, None, detail))

    # t33 内置屏字段缺失（fixture 变异）
    t = fx("dumpsys_redmi_idle_60hz.txt").replace("DisplayDeviceInfo{\"内置屏幕\"", "DisplayDeviceInfo{\"unknown\"").replace("type INTERNAL", "type EXTERNAL")
    actual, detail = a5_run.parse_display_refresh(t)
    ok = (actual is None and "不唯一" in str(detail.get("reason", "")))
    results.append(("t33 内置屏身份缺失→INVALID", ok, None, detail))

    # t35 modeId 与 mActiveModeId 不一致（fixture 变异）
    t = fx("dumpsys_redmi_idle_60hz.txt").replace("mActiveModeId=3", "mActiveModeId=1", 1)
    actual, detail = a5_run.parse_display_refresh(t)
    ok = (actual is None and "不一致" in str(detail.get("reason", "")))
    results.append(("t35 modeId≠mActiveModeId→INVALID", ok, None, detail))

    # t31 内置+外接双显示（外接块含 PDS 干扰）
    t = fx("dumpsys_redmi_idle_60hz.txt") + '\nDisplayDeviceInfo{"HDMI", uniqueId="local:9", type EXTERNAL, modeId 1, supportedModes [{id=1, width=1, height=1, fps=24.0}], colorMode 0}\n'
    actual, detail = a5_run.parse_display_refresh(t)
    ok = (actual == 120.00001 and detail["target_device"]["uniqueId"] == "local:4627039422300187648"
          and detail["internal_blocks"] == 1)
    results.append(("t31 内置+外接→仍识别内置 120.00001", ok, None, detail))

    # t42 真实 fixture 走完整 main（请求 60、实际 120）→INVALID 零点击
    code, calls, tmp, info = run_case(script={
        "dumpsys|display": FakeResult(out=fx("dumpsys_redmi_active_60hz.txt").encode("utf-8", "replace")),
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"60"), FakeResult(out=b"60")],
        "settings|get|peak_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"60"), FakeResult(out=b"60")]},
                                      argv=["a5_run.py", "fake-serial", "60", "s1_single", "1"])
    ok = (code == 1 and info and info["status"] == "INVALID" and no_taps(calls)
          and info["env"]["actual_active_display_fps"] == 120.00001)
    results.append(("t42 真实fixture走main（请求60/实际120）→INVALID 零点击", ok, code, info))

    # t43 dumpsys 命令失败；t44 空输出；t45 原始写入失败
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(rc=1, err=b"dump fail")})
    ok = (code == 1 and info and info["status"] == "INVALID" and no_taps(calls))
    results.append(("t43 dumpsys 命令失败→INVALID 零点击", ok, code, info))

    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=b"")})
    ok = (code == 1 and info and info["status"] == "INVALID" and no_taps(calls))
    results.append(("t44 dumpsys 空输出→INVALID 零点击", ok, code, info))

    code, calls, tmp, info = run_case(patch_raw_write=True)
    ok = (code == 1 and info and info["status"] == "FAILED" and no_taps(calls)
          and "raw write failed" in str(info.get("error")))
    results.append(("t45 原始证据写入失败→FAILED 零点击", ok, code, info))

    # ---- R7-P1：mActiveModeId 设备绑定 ----
    EXT_DIFF = ('DisplayDeviceInfo{"HDMI", uniqueId="local:9", 1x1, modeId 1, '
                'supportedModes [{id=1, width=1, height=1, fps=24.0}], colorMode 0, type EXTERNAL}\n'
                'mActiveModeId=2\n')  # 外接自身 modeId=1 与其 mActiveModeId=2 亦不一致（干扰源）
    EXT_SAME_ID = ('DisplayDeviceInfo{"HDMI", uniqueId="local:9", 1x1, modeId 3, '
                   'supportedModes [{id=3, width=1, height=1, fps=24.0}], colorMode 0, type EXTERNAL}\n'
                   'mActiveModeId=3\n')

    def run_invalid(name, dev_text, reason_part):
        code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=dev_text)})
        ok = (code == 1 and info and info["status"] == "INVALID" and info["primary_status"] == "INVALID"
              and no_taps(calls) and info["restore_ok"] is True
              and "restore_ok=True" in info["env"]["refresh_restore"]["min_refresh_rate"]
              and reason_part in str(info["env"]["display_parse"].get("reason", "")))
        results.append((name, ok, code, info))

    run_invalid("R7-1 mActiveModeId 缺失→INVALID 零点击+恢复", DEV_BASE.replace(b"mActiveModeId=3\n", b""), "mActiveModeId")
    run_invalid("R7-2 目标设备 mActiveModeId×2→INVALID", DEV_BASE.replace(b"mActiveModeId=3\n", b"mActiveModeId=3\nmActiveModeId=3\n"), "缺失或多值")
    run_invalid("R7-3 uniqueId 缺失→INVALID", DEV_BASE.replace(b'uniqueId=\"local:1\", ', b""), "uniqueId")
    run_invalid("R7-6 内置 modeId≠自身 mActiveModeId→INVALID", DEV_BASE.replace(b"mActiveModeId=3", b"mActiveModeId=1"), "不一致")

    # R7-4 外接在前（mActiveModeId 与内置不同）→ 内置正确绑定
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=EXT_DIFF.encode() + DEV_BASE)})
    ok = (code == 0 and info and info["status"] == "OK"
          and info["env"]["actual_active_display_fps"] == 120.00001
          and info["env"]["display_parse"]["target_device"]["uniqueId"] == "local:1"
          and info["env"]["display_parse"]["target_device"]["mActiveModeId"] == "3")
    results.append(("R7-4 外接在前(异值)→内置正确绑定120.00001", ok, code, info))

    # R7-5 外接在后 → 内置仍绑定
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=DEV_BASE + EXT_DIFF.encode())})
    ok = (code == 0 and info and info["status"] == "OK"
          and info["env"]["actual_active_display_fps"] == 120.00001)
    results.append(("R7-5 外接在后→内置仍绑定", ok, code, info))

    # R7-7 内置一致、外接 mActiveModeId 异值 → 不受干扰
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=DEV_BASE + EXT_DIFF.encode())})
    ok = (code == 0 and info and info["env"]["display_parse"]["target_device"]["mActiveModeId"] == "3"
          and info["env"]["actual_active_display_fps"] == 120.00001)
    results.append(("R7-7 外接 mActiveModeId 异值不干扰内置", ok, code, info))

    # R7-8 两设备同 modeId 不同 fps → 各用自身 supportedModes
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=EXT_SAME_ID.encode() + DEV_BASE)})
    ok = (code == 0 and info and info["env"]["actual_active_display_fps"] == 120.00001)
    results.append(("R7-8 同 modeId 不同 fps→按设备自身映射(120.00001)", ok, code, info))

    # R7-10 真实 120Hz fixture 完整走 main
    code, calls, tmp, info = run_case(script={
        "dumpsys|display": FakeResult(out=fx("dumpsys_redmi_active_120hz.txt").encode("utf-8", "replace")),
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"60")],
        "settings|get|peak_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"60")]},
        argv=["a5_run.py", "fake-serial", "120", "s1_single", "1"])
    ok = (code == 0 and info and info["status"] == "OK" and info["restore_ok"] is True
          and info["env"]["actual_active_display_fps"] == 120.00001
          and "restore_ok=True" in info["env"]["refresh_restore"]["min_refresh_rate"]
          and not no_taps(calls))
    results.append(("R7-10 真实120Hz fixture 走 main→OK exit0+恢复+点击执行", ok, code, info))

    # ---- R8：supportedModes 重复 ID 唯一映射 ----
    LIST_DEV = b"supportedModes [{id=1, width=1220, height=2712, fps=60.0}, {id=3, width=1220, height=2712, fps=120.00001}]"
    DUP_MIX = b"supportedModes [{id=1, width=1220, height=2712, fps=60.0}, {id=3, width=1220, height=2712, fps=60.0}, {id=3, width=1220, height=2712, fps=120.00001}]"
    DUP_SAME = b"supportedModes [{id=1, width=1220, height=2712, fps=60.0}, {id=3, width=1220, height=2712, fps=120.00001}, {id=3, width=1220, height=2712, fps=120.00001}]"
    TRIPLE = b"supportedModes [{id=3, width=1220, height=2712, fps=60.0}, {id=3, width=1220, height=2712, fps=120.00001}, {id=3, width=1220, height=2712, fps=120.00001}, {id=1, width=1220, height=2712, fps=60.0}]"
    DUP_OTHER = b"supportedModes [{id=1, width=1220, height=2712, fps=60.0}, {id=1, width=1220, height=2712, fps=60.0}, {id=3, width=1220, height=2712, fps=120.00001}]"

    def dev_with(list_bytes):
        return DEV_BASE.replace(LIST_DEV, list_bytes)

    run_invalid("R8-1 活动id×2(60/120)→INVALID 零点击+落盘", dev_with(DUP_MIX), "出现 2 次")
    run_invalid("R8-2 活动id×2(同120)→INVALID（fps 相同亦不放行）", dev_with(DUP_SAME), "出现 2 次")
    run_invalid("R8-3 活动id×3→INVALID", dev_with(TRIPLE), "出现 3 次")

    # R8-4 非活动 id 重复、活动 id 唯一 → 允许；证明活动映射唯一且不受影响
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=dev_with(DUP_OTHER))})
    ok = (code == 0 and info and info["status"] == "OK"
          and info["env"]["actual_active_display_fps"] == 120.00001
          and info["env"]["display_parse"]["active_mode_id_occurrences"] == 1
          and info["env"]["display_parse"]["duplicate_mode_ids"] == {"1": 2}
          and not no_taps(calls))
    results.append(("R8-4 非活动id重复/活动id唯一→允许且不受影响", ok, code, info))

    # R8-5 真实 fixture 四份：严格解析不误伤真实格式
    for name in ("dumpsys_redmi_idle_60hz.txt", "dumpsys_redmi_idle_120hz.txt",
                 "dumpsys_redmi_active_60hz.txt", "dumpsys_redmi_active_120hz.txt"):
        actual, detail = a5_run.parse_display_refresh(fx(name))
        ok = (actual == 120.00001 and detail["active_mode_id_occurrences"] == 1
              and detail["duplicate_mode_ids"] == {})
        results.append((f"R8-5 真实fixture {name} 唯一映射通过", ok, None, detail))

    # R8-6 真实 120Hz fixture 完整走 main（门禁/恢复/状态/退出码/run_info 落盘）
    code, calls, tmp, info = run_case(script={
        "dumpsys|display": FakeResult(out=fx("dumpsys_redmi_active_120hz.txt").encode("utf-8", "replace")),
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"60")],
        "settings|get|peak_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"60")]},
        argv=["a5_run.py", "fake-serial", "120", "s1_single", "1"])
    ok = (code == 0 and info is not None and info["status"] == "OK" and info["restore_ok"] is True
          and info["env"]["actual_active_display_fps"] == 120.00001
          and info["env"]["display_parse"]["active_mode_id_occurrences"] == 1
          and "restore_ok=True" in info["env"]["refresh_restore"]["min_refresh_rate"]
          and not no_taps(calls))
    results.append(("R8-6 真实120Hz fixture 走 main→OK/exit0/恢复/落盘", ok, code, info))

    all_ok = True
    for name, ok, code, info in results:
        if not ok:
            all_ok = False
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  (code={code})")
    print("ALL PASS" if all_ok else "HAS FAILURES")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()

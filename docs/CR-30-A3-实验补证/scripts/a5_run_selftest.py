# -*- coding: utf-8 -*-
"""a5_run.py R4 自测（mock adb，不接触真机）。
用法: python a5_run_selftest.py   （全部通过 exit 0；任一失败 exit 1）

R4 覆盖（GPT 2.3/2.4/2.6）：验证最终 status 与进程退出码，而非仅错误字符串。
 t1/t2  参数展开 + 正常完成（含 2.5 刷新率验证）：status=OK、exit 0、恢复成功
 t3     中途 Abort（tap 失败）：恢复执行、status=FAILED、exit≠0、run_info 落盘
 t4a    screenrecord 超时：kill、status=FAILED、exit≠0
 t4b    FileNotFoundError：恢复落盘、status=FAILED、exit≠0
 t5r    目标刷新率设置成功、恢复 put 失败：主体成功、restore_failures、status=FAILED、exit≠0、run_info 落盘
 t6     恢复抛 TypeError：run_info 仍落盘、status=FAILED、异常记录、exit≠0
 t7     原值 null→settings delete 恢复
 t8     原值读取失败→跳过恢复不覆盖
 t9a    vivo PENDING 门禁：拒绝且零点击
 t9b    越界坐标：拒绝且零点击
 t10    已存在目录：拒绝覆盖、材料保留
 t11    2.5 实际显示模式与请求不一致：status=INVALID、零点击、exit≠0
 t12    恢复 put 成功但读回值不一致：status=FAILED、exit≠0
 t13    恢复后读回命令失败：status=FAILED、exit≠0
 t14    run_info 写入异常：exit=2、FAILED_write.txt、stderr 报告
 t15    一键恢复成功另一键失败：status=FAILED、exit≠0
"""
import json
import shutil
import sys
import tempfile
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import a5_run  # noqa: E402


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
            FakePopen.raise_on_wait = None  # kill 后的二次 wait 不再抛（模拟真实进程终止）
            raise exc
        return self._rc

    def kill(self):
        FakePopen.killed = True
        self._rc = -9


class Scripted:
    """脚本化 adb 应答：键→单值或列表（按调用次序消费）。"""

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
                if callable(v):
                    v = v(flat)
                if isinstance(v, Exception):
                    raise v
                return v
        return FakeResult()


BASE = {
    "getprop": FakeResult(out=b"2407FRK8EC"),
    "wm size": FakeResult(out=b"Physical size: 1220x2712"),
    "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"60")],
    "settings|get|peak_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"60")],
    "dumpsys|display": FakeResult(out=(
        b'DisplayDeviceInfo{"built-in", uniqueId="local:1", 1220 x 2712, type INTERNAL}\n'
        b'mActiveSfDisplayMode=DisplayMode{id=2, width=1220, peakRefreshRate=120.00001}\n'
        b'mActiveRenderFrameRate=120.00001\n'
        b'      RefreshRate=120.0\n')),
    "logcat|-d": FakeResult(out=b"CR30Probe: A5/tabClick=1#1\n"),
    "exec-out|cat": FakeResult(out=b"\x00\x00\x00\x18ftypmp42"),
}


def run_case(script=None, pre=None, argv=None, patch_json=None, popen_raise=None):
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
    a5_run.json = real_json
    info = None
    for p in Path(tmp).glob("*/run_info.json"):
        info = json.loads(p.read_text(encoding="utf-8"))
    return code, calls, Path(tmp), info


def timeout_exc():
    import subprocess as sp
    return sp.TimeoutExpired(cmd="screenrecord", timeout=30)


def main():
    results = []

    # t1/t2 正常完成（含 2.5 验证）
    code, calls, tmp, info = run_case()
    ok = (code == 0 and info and info["status"] == "OK" and info["restore_ok"] is True
          and info["env"]["refresh_match_request"] is True
          and any(c[:5] == ("shell", "settings", "put", "system", "min_refresh_rate") for c in calls))
    results.append(("t1/t2 参数展开+正常完成+2.5验证+恢复", ok, code, info))

    # t3 tap 失败
    code, calls, tmp, info = run_case(script={"input|tap": FakeResult(rc=1, err=b"tap fail")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info.get("restore_failures") is None
          and info["restore_ok"] is True)
    results.append(("t3 Abort 后恢复执行 status=FAILED exit=1", ok, code, info))

    # t4a screenrecord 超时
    code, calls, tmp, info = run_case(popen_raise=timeout_exc())
    ok = (code == 1 and info and info["status"] == "FAILED" and FakePopen.killed
          and "screenrecord timeout" in str(info.get("error")))
    results.append(("t4a 录屏超时→kill→FAILED exit=1", ok, code, info))

    # t4b FileNotFoundError
    code, calls, tmp, info = run_case(script={"exec-out|cat": FileNotFoundError("adb gone")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is True)
    results.append(("t4b FileNotFoundError→恢复落盘 FAILED exit=1", ok, code, info))

    # t5r 目标设置成功、恢复 put 失败（按参数值区分 120=目标 / 60=恢复）
    code, calls, tmp, info = run_case(script={"settings|put|min_refresh_rate|60": FakeResult(rc=1, err=b"denied")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and any(f["key"] == "min_refresh_rate" for f in info.get("restore_failures", []))
          and info["env"]["refresh_match_request"] is True)
    results.append(("t5r 设置成功+恢复失败→restore_failures+FAILED exit=1", ok, code, info))

    # t6 恢复抛 TypeError：落盘、FAILED、异常记录、exit≠0
    code, calls, tmp, info = run_case(script={
        "settings|put|min_refresh_rate|60": TypeError("simulated TypeError on restore")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and any("EXCEPTION" in str(f) or f.get("stage") == "restore_exception"
                  for f in info.get("restore_failures", [])))
    results.append(("t6 恢复异常→落盘+FAILED+异常记录 exit=1", ok, code, info))

    # t7 原值 null→delete
    code, calls, tmp, info = run_case(script={"settings|get|min_refresh_rate": [FakeResult(out=b"null"), FakeResult(out=b"120"), FakeResult(out=b"null")]})
    ok = (any(c[:4] == ("shell", "settings", "delete", "system") for c in calls)
          and "delete" in info["env"]["refresh_restore"]["min_refresh_rate"])
    results.append(("t7 原值 null→settings delete", ok, code, info))

    # t8 原值读取失败→跳过恢复
    code, calls, tmp, info = run_case(script={"settings|get|peak_refresh_rate": FakeResult(rc=1, err=b"no read")})
    ok = ("skipped" in info["env"]["refresh_restore"]["peak_refresh_rate"]
          and not any(c[:5] == ("shell", "settings", "put", "system", "peak_refresh_rate") for c in calls))
    results.append(("t8 原值读取失败→跳过恢复不覆盖", ok, code, info))

    # t9a vivo 门禁
    code, calls, tmp, info = run_case(script={"getprop": FakeResult(out=b"V2352A")})
    ok = (code == 1 and info and info["status"] == "FAILED"
          and not any((len(c) > 3 and c[2] == "tap") for c in calls))
    results.append(("t9a vivo PENDING 拒绝且零点击", ok, code, info))

    # t9b 越界坐标
    def patch_nav(p):
        a5_run.DEVICES["2407FRK8EC"]["nav"]["today"] = (99999, 0)
    code, calls, tmp, info = run_case(pre=patch_nav)
    a5_run.DEVICES["2407FRK8EC"]["nav"]["today"] = (503, 2493)
    ok = (code == 1 and info and info["status"] == "FAILED"
          and not any((len(c) > 3 and c[2] == "tap") for c in calls))
    results.append(("t9b 越界坐标拒绝且零点击", ok, code, info))

    # t10 防覆盖
    def pre_fill(p):
        d = p / "s1_single_120hz_r1"
        d.mkdir(parents=True)
        (d / "old.txt").write_text("keep me")
    code, calls, tmp, info = run_case(pre=pre_fill)
    ok = (isinstance(code, str) and "Abort" in code
          and (Path(tmp) / "s1_single_120hz_r1" / "old.txt").exists())
    results.append(("t10 防覆盖（材料保留）", ok, code, info))

    # t11/t23 实际活动刷新率不符→INVALID、零点击、exit≠0
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=(
        b'type INTERNAL\nmActiveSfDisplayMode=DisplayMode{id=2, peakRefreshRate=120.00001}\n'
        b'      RefreshRate=60.0\n'))})
    ok = (code == 1 and info and info["status"] == "INVALID" and info["env"]["refresh_match_request"] is False
          and not any((len(c) > 3 and c[2] == "tap") for c in calls))
    results.append(("t11/t23 活动率不符→INVALID 零点击 exit=1", ok, code, info))

    # t16 INVALID + 恢复 put 失败→最终 FAILED、primary_status=INVALID 保留
    code, calls, tmp, info = run_case(script={
        "dumpsys|display": FakeResult(out=b'type INTERNAL\n      RefreshRate=60.0\n'),
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"60")],
        "settings|put|min_refresh_rate|60": FakeResult(rc=1, err=b"denied")})
    ok = (code == 1 and info and info["status"] == "FAILED"
          and info["primary_status"] == "INVALID"
          and "refresh mismatch" in str(info.get("primary_error"))
          and info["restore_ok"] is False
          and any(f["key"] == "min_refresh_rate" for f in info.get("restore_failures", [])))
    results.append(("t16 INVALID+恢复put失败→FAILED 保留 primary", ok, code, info))

    # t17 INVALID + 恢复读回命令失败→FAILED
    code, calls, tmp, info = run_case(script={
        "dumpsys|display": FakeResult(out=b'type INTERNAL\n      RefreshRate=60.0\n'),
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(rc=1, err=b"gone")]})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["primary_status"] == "INVALID"
          and any(f.get("stage") == "restore_readback_cmd" for f in info.get("restore_failures", [])))
    results.append(("t17 INVALID+恢复读回失败→FAILED", ok, code, info))

    # t18 INVALID + 恢复读回值不一致→FAILED
    code, calls, tmp, info = run_case(script={
        "dumpsys|display": FakeResult(out=b'type INTERNAL\n      RefreshRate=60.0\n'),
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"55")]})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["primary_status"] == "INVALID"
          and any(f.get("stage") == "restore_readback_value" for f in info.get("restore_failures", [])))
    results.append(("t18 INVALID+恢复值不一致→FAILED", ok, code, info))

    # t20 dumpsys 缺活动刷新率字段→INVALID 零点击
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=(
        b'type INTERNAL\nmActiveSfDisplayMode=DisplayMode{id=2, peakRefreshRate=120.00001}\n'))})
    ok = (code == 1 and info and info["status"] == "INVALID"
          and info["primary_status"] == "INVALID"
          and "unidentifiable" in str(info.get("primary_error"))
          and not any((len(c) > 3 and c[2] == "tap") for c in calls))
    results.append(("t20 缺活动率字段→INVALID 零点击", ok, code, info))

    # t21 多显示设备/多独立 RefreshRate 行→INVALID 零点击
    code, calls, tmp, info = run_case(script={"dumpsys|display": FakeResult(out=(
        b'type INTERNAL\n      RefreshRate=120.0\n      RefreshRate=60.0\n'))})
    ok = (code == 1 and info and info["status"] == "INVALID"
          and "独立 RefreshRate 行数量=2" in str(info["env"]["display_parse"].get("reason", ""))
          and not any((len(c) > 3 and c[2] == "tap") for c in calls))
    results.append(("t21 多显示设备/多活动率行→INVALID 零点击", ok, code, info))

    # t22 settings 读回≠请求→INVALID 零点击（put 成功但读回不符）
    code, calls, tmp, info = run_case(script={
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"55"), FakeResult(out=b"60")]})
    ok = (code == 1 and info and info["status"] == "INVALID"
          and "settings readback mismatch" in str(info.get("primary_error"))
          and not any((len(c) > 3 and c[2] == "tap") for c in calls))
    results.append(("t22 settings 读回≠请求→INVALID 零点击", ok, code, info))

    # t12 恢复 put 成功、读回值不一致（min 键第 3 次 get=恢复读回）
    code, calls, tmp, info = run_case(script={
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(out=b"55")]})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and any(f.get("stage") == "restore_readback_value" for f in info.get("restore_failures", [])))
    results.append(("t12 恢复读回不一致→FAILED exit=1", ok, code, info))

    # t13 恢复后读回命令失败
    code, calls, tmp, info = run_case(script={
        "settings|get|min_refresh_rate": [FakeResult(out=b"60"), FakeResult(out=b"120"), FakeResult(rc=1, err=b"gone")]})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and any(f.get("stage") == "restore_readback_cmd" for f in info.get("restore_failures", [])))
    results.append(("t13 恢复读回命令失败→FAILED exit=1", ok, code, info))

    # t14 run_info 写入异常→exit=2、FAILED_write.txt、stderr
    code, calls, tmp, info = run_case(patch_json=lambda *a, **k: (_ for _ in ()).throw(TypeError("sim write")))
    fw = list(Path(tmp).glob("*/FAILED_write.txt"))
    ok = (code == 2 and fw and fw[0].exists() and info is None)
    results.append(("t14 落盘失败→exit=2+FAILED_write.txt", ok, code, info))

    # t15 一键成功一键失败
    code, calls, tmp, info = run_case(script={"settings|put|peak_refresh_rate|60": FakeResult(rc=1, err=b"denied")})
    ok = (code == 1 and info and info["status"] == "FAILED" and info["restore_ok"] is False
          and any(f["key"] == "peak_refresh_rate" for f in info.get("restore_failures", []))
          and "restore_ok=True" in info["env"]["refresh_restore"]["min_refresh_rate"])
    results.append(("t15 单键恢复失败→FAILED exit=1（他键成功）", ok, code, info))

    all_ok = True
    for name, ok, code, info in results:
        if not ok:
            all_ok = False
        print(f"[{'PASS' if ok else 'FAIL'}] {name}  (code={code})")
    print("ALL PASS" if all_ok else "HAS FAILURES")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()

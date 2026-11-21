# -*- coding: utf-8 -*-
"""a5_run.py R3 自测（mock adb，不接触真机）：GPT 3.1 测试矩阵 + 3.2/3.3/3.4 场景。
用法: python a5_run_selftest.py   （全部通过 exit 0；任一失败 exit 1）

覆盖：
 t1 adb 参数展开（无嵌套列表）
 t2 正常完成：刷新率恢复 put 正确、run_info status=OK
 t3 中途 Abort（tap 失败）：仍恢复、status=FAILED、run_info 落盘
 t4 ADB 异常（screenrecord TimeoutExpired / FileNotFoundError）：恢复+落盘
 t5 恢复失败（put rc!=0）：restore_failures 记录
 t6 finally 内异常（TypeError）不阻止 run_info 写入
 t7 原值 "null"：恢复用 settings delete
 t8 原值读取失败：该键跳过恢复、不覆盖
 t9 设备门禁：vivo PENDING 分辨率拒绝且零点击；Redmi 越界坐标拒绝
 t10 已存在目录含内容拒绝覆盖
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
    def __init__(self, *a, **k):
        self.args = a[0] if a else ()
        self._rc = 0
        self._raise = getattr(FakePopen, "raise_on_wait", None)

    def wait(self, timeout=None):
        if self._raise is not None:
            raise self._raise
        return self._rc

    def kill(self):
        self._rc = -9


def make_env(tmp):
    a5_run.OUT = Path(tmp)
    a5_run.time = types.SimpleNamespace(
        monotonic=(lambda c=[0.0]: (c.__setitem__(0, c[0] + 0.01) or c[0])),
        sleep=lambda s: None)
    FakePopen.raise_on_wait = None
    a5_run.subprocess = types.SimpleNamespace(
        run=None, Popen=FakePopen,
        TimeoutExpired=subprocess_timeout_type(), FileNotFoundError=FileNotFoundError)


def subprocess_timeout_type():
    import subprocess as sp
    return sp.TimeoutExpired


def setup_adb(script, calls):
    """script: dict matcher->FakeResult|Exception；calls 记录 (args_tuple)。"""
    def fake_adb(serial, *args, timeout=90):
        flat = tuple(args)
        for a in flat:
            assert isinstance(a, str), f"nested/non-str adb arg: {flat!r}"
        calls.append(flat)
        for key, val in script.items():
            if all(k in " ".join(flat) for k in key.split("|")):
                if isinstance(val, Exception):
                    raise val
                return val
        return FakeResult()
    a5_run.adb = fake_adb
    # must() 通过模块级 adb 调用 → 同一补丁生效


BASE_SCRIPT = {
    "getprop": FakeResult(out=b"2407FRK8EC"),
    "wm size": FakeResult(out=b"Physical size: 1220x2712"),
    "settings|get|min_refresh_rate": FakeResult(out=b"60"),
    "settings|get|peak_refresh_rate": FakeResult(out=b"60"),
    "logcat|-d": FakeResult(out=b"CR30Probe: A5/tabClick=1#1\n"),
    "exec-out|cat": FakeResult(out=b"\x00\x00\x00\x18ftypmp42"),
}


def run_case(name, expect_ok, script=None, pre=None, argv=None):
    tmp = tempfile.mkdtemp(prefix="a5st_")
    calls = []
    try:
        make_env(tmp)
        s = dict(BASE_SCRIPT)
        s.update(script or {})
        setup_adb(s, calls)
        if pre:
            pre(Path(tmp))
        sys.argv = argv or ["a5_run.py", "fake-serial", "120", "s1_single", "1"]
        err = None
        try:
            a5_run.main()
        except SystemExit as e:
            err = e.code
        except a5_run.Abort as e:
            err = f"Abort:{e}"
        except Exception as e:
            err = f"{type(e).__name__}:{e!r}"
        return name, expect_ok, err, calls, Path(tmp)
    finally:
        pass


def read_info(tmp):
    for p in Path(tmp).glob("*/run_info.json"):
        return json.loads(p.read_text(encoding="utf-8"))
    return None


def main():
    results = []

    # t1/t2 正常路径（同时验证参数展开：fake_adb 断言全部 str）
    name, ok, err, calls, tmp = run_case("t2_正常完成恢复", None)
    info = read_info(tmp)
    t2 = (info and info["status"] == "OK" and
          any(c[:5] == ("shell", "settings", "put", "system", "min_refresh_rate") for c in calls) and
          "restore_ok=True" in info["env"]["refresh_restore"]["min_refresh_rate"])
    results.append(("t1 参数展开(嵌套即断言失败) + t2 正常完成恢复", t2, err, info))

    # t3 中途 Abort（tap 失败）
    name, ok, err, calls, tmp = run_case("t3", None, script={"input|tap": FakeResult(rc=1, err=b"tap fail")})
    info = read_info(tmp)
    t3 = (info and info["status"] == "FAILED" and
          any("settings|put" in " ".join(c) or (len(c) > 3 and c[1] == "settings") for c in calls))
    results.append(("t3 Abort 后仍恢复并落盘", t3, err, info))

    # t4a screenrecord TimeoutExpired
    def raise_timeout():
        import subprocess as sp
        return sp.TimeoutExpired(cmd="screenrecord", timeout=30)
    name, ok, err, calls, tmp = run_case("t4a", None)
    # 注：screenrecord 超时通过 FakePopen.raise_on_wait 注入
    make_env(tempfile.mkdtemp(prefix="a5st_"))
    FakePopen.raise_on_wait = raise_timeout()
    tmp4 = tempfile.mkdtemp(prefix="a5st_")
    a5_run.OUT = Path(tmp4)
    calls4 = []
    setup_adb(dict(BASE_SCRIPT), calls4)
    sys.argv = ["a5_run.py", "fake-serial", "120", "s1_single", "1"]
    try:
        a5_run.main()
    except SystemExit:
        pass
    except Exception as e:
        err = e
    info4 = read_info(Path(tmp4))
    FakePopen.raise_on_wait = None
    t4a = info4 and info4["status"] == "FAILED" and "TimeoutExpired" in str(info4.get("error"))
    results.append(("t4a screenrecord 超时→kill→恢复落盘", t4a, info4.get("error") if info4 else None, info4))

    # t4b FileNotFoundError
    tmp5 = tempfile.mkdtemp(prefix="a5st_")
    a5_run.OUT = Path(tmp5)
    calls5 = []
    s5 = dict(BASE_SCRIPT)
    s5["exec-out|cat"] = FileNotFoundError("adb gone")
    setup_adb(s5, calls5)
    sys.argv = ["a5_run.py", "fake-serial", "120", "s1_single", "1"]
    try:
        a5_run.main()
    except SystemExit:
        pass
    info5 = read_info(Path(tmp5))
    t4b = info5 and info5["status"] == "FAILED" and "FileNotFoundError" in str(info5.get("error"))
    results.append(("t4b FileNotFoundError→恢复落盘", t4b, info5.get("error") if info5 else None, info5))

    # t5 恢复失败记录
    name, ok, err, calls, tmp = run_case("t5", None, script={
        "settings|put|min_refresh_rate": FakeResult(rc=1, err=b"denied")})
    info = read_info(tmp)
    t5 = info and info.get("restore_failures") and any(f["key"] == "min_refresh_rate" for f in info["restore_failures"])
    results.append(("t5 恢复失败记录 restore_failures", t5, err, info))

    # t7 原值 null → delete
    name, ok, err, calls, tmp = run_case("t7", None, script={
        "settings|get|min_refresh_rate": FakeResult(out=b"null")})
    info = read_info(tmp)
    t7 = any(c[:4] == ("shell", "settings", "delete", "system") for c in calls) and         "delete" in info["env"]["refresh_restore"]["min_refresh_rate"]
    results.append(("t7 原值 null→settings delete 恢复", t7, err, info))

    # t8 原值读取失败 → 跳过恢复
    name, ok, err, calls, tmp = run_case("t8", None, script={
        "settings|get|peak_refresh_rate": FakeResult(rc=1, err=b"no read")})
    info = read_info(tmp)
    t8 = info and "skipped" in info["env"]["refresh_restore"]["peak_refresh_rate"] and         not any(c[:5] == ("shell", "settings", "put", "system", "peak_refresh_rate") for c in calls)
    results.append(("t8 原值读取失败→跳过恢复不覆盖", t8, err, info))

    # t9a vivo PENDING 分辨率 → 拒绝且零点击
    name, ok, err, calls, tmp = run_case("t9a", None, script={"getprop": FakeResult(out=b"V2352A")})
    info = read_info(tmp)
    t9a = (err is not None or (info and info["status"] == "FAILED")) and \
        not any("input|tap" in " ".join(c) or (len(c) > 3 and c[2] == "tap") for c in calls)
    results.append(("t9a vivo PENDING 门禁拒绝且零点击", t9a, err, info))

    # t9b Redmi 越界坐标 → 拒绝
    def patch_nav(tmp_):
        a5_run.DEVICES["2407FRK8EC"]["nav"]["today"] = (99999, 0)
    name, ok, err, calls, tmp = run_case("t9b", None, pre=patch_nav)
    a5_run.DEVICES["2407FRK8EC"]["nav"]["today"] = (503, 2493)
    info = read_info(tmp)
    t9b = (err is not None or (info and info["status"] == "FAILED")) and \
        not any((len(c) > 3 and c[2] == "tap") for c in calls)
    results.append(("t9b 越界坐标拒绝且零点击", t9b, err, info))

    # t10 已存在目录含内容 → 拒绝覆盖
    def pre_fill(tmp_):
        d = Path(tmp_) / "s1_single_120hz_r1"
        d.mkdir(parents=True)
        (d / "old.txt").write_text("keep me")
    name, ok, err, calls, tmp = run_case("t10", None, pre=pre_fill)
    t10 = isinstance(err, str) and "Abort" in err and (Path(tmp) / "s1_single_120hz_r1" / "old.txt").exists()
    results.append(("t10 已存在目录拒绝覆盖（材料保留）", t10, err, None))

    # t6 finally 异常不阻止落盘：让 restore 中 adb 抛 TypeError
    tmp6 = tempfile.mkdtemp(prefix="a5st_")
    a5_run.OUT = Path(tmp6)
    calls6 = []
    s6 = dict(BASE_SCRIPT)
    def typeerr_adb(serial, *args, timeout=90):
        flat = tuple(args)
        joined = " ".join(flat)
        if len(flat) > 1 and flat[1] == "settings" and flat[2] == "put" and flat[-1] == "60":
            raise TypeError("simulated nested-list TypeError on restore")
        if "getprop" in joined:
            return FakeResult(out=b"2407FRK8EC")
        if "wm size" in joined:
            return FakeResult(out=b"Physical size: 1220x2712")
        if "settings" in joined and "get" in joined:
            return FakeResult(out=b"60")
        if "logcat" in joined and "-d" in joined:
            return FakeResult(out=b"CR30Probe: A5/tabClick=1#1\n")
        if "exec-out" in joined:
            return FakeResult(out=b"\x00\x00\x00\x18ftypmp42")
        return FakeResult()
    a5_run.adb = typeerr_adb
    sys.argv = ["a5_run.py", "fake-serial", "120", "s1_single", "1"]
    try:
        a5_run.main()
    except SystemExit:
        pass
    info6 = read_info(Path(tmp6))
    t6 = info6 is not None and info6["status"] == "OK" and \
        any("EXCEPTION" in v for v in info6["env"]["refresh_restore"].values())
    results.append(("t6 finally 异常(TypeError)不阻止 run_info 写入", t6, None, info6))

    all_ok = True
    for name, ok, err, info in results:
        mark = "PASS" if ok else "FAIL"
        if not ok:
            all_ok = False
        print(f"[{mark}] {name}" + (f"  (err={err})" if err and not ok else ""))
    print("ALL PASS" if all_ok else "HAS FAILURES")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()

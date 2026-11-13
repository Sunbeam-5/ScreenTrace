#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
屏迹 · 转场动画录屏 / 抽帧工具（开发验证用，不参与 App 构建）

一次调用完成：唤醒设备 → 定位入口按钮 → 录一段「打开二级页 → 返回」→
拉取视频 → 生成全流程 / 进入段 / 返回段三张联系表 + 一个压缩视频 →
自动还原代码与设备设置。

最常用（零参数，自动选唯一在线设备）：
    python scripts/capture_anim.py

看不清动画时双向慢放（临时改常量并构建，finally 必定还原）：
    python scripts/capture_anim.py --slow

指定设备 / 输出目录 / 手动指定按钮坐标：
    python scripts/capture_anim.py -s GAQOHMNFQWFUPBQO --name cr11_recheck
    python scripts/capture_anim.py --tap 1077,263

产物（默认 C:/tmp/screentrace-capture/<时间戳>/）：
    full.png   全流程联系表（打开 → 全屏 → 缩回 → 首页，一图看全，无黑格）
    enter.png  进入段逐帧        exit.png  返回段逐帧
    clip.mp4   裁剪压缩后的完整视频        raw.mp4  原始录屏

依赖：adb（PATH 内）、ffmpeg（优先 imageio_ffmpeg 自带，其次系统 ffmpeg）；
--slow 模式额外需要 JDK 17 与 gradlew。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

# Windows 控制台中文输出保护
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
CONTAINER_TRANSFORM = (
    ROOT / "app" / "src" / "main" / "java" / "com" / "screentrace"
    / "app" / "uikit" / "ContainerTransform.kt"
)
PACKAGE = "com.screentrace.app"
REMOTE_MP4 = "/sdcard/st_capture.mp4"
REMOTE_UI = "/sdcard/st_ui.xml"


def log(msg: str) -> None:
    print(f"[capture] {msg}", flush=True)


def winpath(path: Path) -> str:
    """adb / ffmpeg 在 Windows 上一律收正斜杠路径（反斜杠与 /tmp 形式都踩过坑）。"""
    return path.as_posix()


def adb(serial: str | None, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, check=check)


def sh(serial: str, script: str, check: bool = True) -> subprocess.CompletedProcess:
    """整条脚本作为单个参数交给设备 shell（& / ; / wait 由远端解释）。"""
    return adb(serial, "shell", script, check=check)


def pick_device(preferred: str | None) -> str:
    lines = adb(None, "devices").stdout.splitlines()
    devices = [ln.split("\t")[0] for ln in lines[1:] if ln.endswith("\tdevice")]
    if preferred:
        if preferred not in devices:
            raise SystemExit(f"设备 {preferred} 不在线；当前在线：{devices or '无'}")
        return preferred
    if len(devices) != 1:
        raise SystemExit(
            f"需要恰好一台在线设备，当前 {len(devices)} 台：{devices}。"
            "请用 -s 指定序列号（避免装错机）。"
        )
    return devices[0]


def locate_button(serial: str, label: str, local_ui: Path) -> tuple[int, int]:
    """用 uiautomator dump 找 content-desc 匹配的按钮中心，跨机型不写死坐标。"""
    sh(serial, f"uiautomator dump {REMOTE_UI}", check=False)
    adb(serial, "pull", REMOTE_UI, winpath(local_ui), check=False)
    if not local_ui.is_file():
        raise SystemExit("uiautomator dump 失败，请确认屏幕已解锁且 App 在前台")
    xml = local_ui.read_text(encoding="utf-8", errors="ignore")
    for node in re.findall(r"<node\b[^>]*>", xml):
        if f'content-desc="{label}"' not in node:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    raise SystemExit(
        f'未找到 content-desc="{label}" 的按钮；'
        "可用 --label 换一个描述，或用 --tap X,Y 手动指定坐标。"
    )


def gradle_env() -> dict:
    env = os.environ.copy()
    if not env.get("JAVA_HOME"):
        for candidate in (r"C:\Program Files\Java\jdk-17", r"C:\Program Files\Java\jdk-21"):
            if os.path.isdir(candidate):
                env["JAVA_HOME"] = candidate
                break
    return env


def build_and_install(serial: str) -> None:
    if os.name == "nt":
        cmd = ["cmd", "/c", "gradlew.bat", ":app:assembleDebug", "-q"]
    else:
        cmd = ["./gradlew", ":app:assembleDebug", "-q"]
    subprocess.run(cmd, cwd=ROOT, env=gradle_env(), check=True)
    adb(serial, "install", "-r", winpath(APK))


def slow_durations(enter_ms: int, exit_ms: int) -> tuple[str, str]:
    """返回 (原文, 改后文本)；只改两个时长常量，不动其它代码。"""
    original = CONTAINER_TRANSFORM.read_text(encoding="utf-8")
    patched = re.sub(
        r"(const val ENTER_DURATION_MS = )\d+", rf"\g<1>{enter_ms}", original, count=1
    )
    patched = re.sub(
        r"(const val EXIT_DURATION_MS = )\d+", rf"\g<1>{exit_ms}", patched, count=1
    )
    if patched == original or f"= {enter_ms}" not in patched:
        raise SystemExit("未能替换时长常量，ContainerTransform.kt 结构可能已变")
    return original, patched


def ffmpeg_exe() -> str | None:
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return shutil.which("ffmpeg")


def ff_run(ff: str, args: list[str], out: Path) -> bool:
    r = subprocess.run([ff, "-y"] + args + [winpath(out)], capture_output=True, text=True)
    if r.returncode != 0:
        log(f"ffmpeg 失败（{out.name}）：{r.stderr[-160:]}")
        return False
    return out.is_file()


def video_duration(ff: str, src: Path) -> float | None:
    """
    读 screenrecord 产物的真实时长。长时间静止时 screenrecord 不写帧，
    mp4 容器时长会短于 --time-limit，按名义时长抽帧必然补黑格。
    """
    r = subprocess.run([ff, "-i", winpath(src)], capture_output=True, text=True)
    m = re.search(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)", r.stderr)
    if not m:
        return None
    h, mi, s = m.groups()
    return int(h) * 3600 + int(mi) * 60 + float(s)


def clamp_window(start: float, window: float, duration: float | None) -> float:
    if duration is None:
        return window
    return max(0.2, min(window, duration - start - 0.02))


def contact_sheet(
    ff: str, src: Path, start: float, window: float,
    cols: int, rows: int, scale: int, out: Path
) -> bool:
    """
    按「格子数反推 fps」抽帧：fps = 格子数*1.02/窗口，保证帧数略多于格子，
    第一张 tile 必然填满且窗口被完整覆盖（不会出现末尾黑格或截断）。
    """
    frames = cols * rows
    # 余量 1.15：宁可多抽几帧被 -frames:v 1 丢弃，也不能少一帧补黑格。
    fps = frames * 1.15 / max(window, 0.1)
    vf = f"fps={fps:.4f},scale={scale}:-1,tile={cols}x{rows}"
    # -ss 必须在 -i 之前（输入侧 seek）：tile 滤镜会把 N 帧打包成一张，
    # 打包后时间戳不再是墙钟时间，输出侧 -ss 会直接跳错整段动画。
    return ff_run(
        ff,
        ["-ss", f"{start:.3f}", "-i", winpath(src),
         "-t", f"{window:.3f}", "-vf", vf, "-frames:v", "1"],
        out,
    )


def main() -> int:
    p = argparse.ArgumentParser(
        description="屏迹转场动画录屏 + 抽帧联系表（开发验证用）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="零参数调用：python scripts/capture_anim.py\n"
               "双向慢放：  python scripts/capture_anim.py --slow",
    )
    p.add_argument("-s", "--device", help="设备序列号；省略时要求恰好一台在线")
    p.add_argument("--name", help="输出子目录名，默认时间戳")
    p.add_argument("--out-root", default="C:/tmp/screentrace-capture", help="产物根目录")
    p.add_argument("--label", default="设置", help="入口按钮 content-desc，默认「设置」")
    p.add_argument("--tap", help="手动指定入口坐标 X,Y（跳过自动定位）")
    p.add_argument("--no-back", action="store_true", help="只录进入，不按返回键")
    p.add_argument("--lead", type=float, default=0.8, help="开录到点击的等待秒数")
    p.add_argument("--slow", action="store_true", help="临时双向慢放（自动构建/还原）")
    p.add_argument("--slow-enter", type=int, default=1200, help="--slow 进入时长 ms")
    p.add_argument("--slow-exit", type=int, default=1400, help="--slow 退出时长 ms")
    p.add_argument("--scale", type=int, default=300, help="联系表单帧宽度 px")
    args = p.parse_args()

    serial = pick_device(args.device)
    out_dir = Path(args.out_root) / (args.name or datetime.now().strftime("%Y%m%d_%H%M%S"))
    out_dir.mkdir(parents=True, exist_ok=True)

    # 时间线：慢放时自动拉长停留/尾段，保证动画完整落在录屏窗口内
    enter_s = args.slow_enter / 1000 if args.slow else 0.32
    exit_s = args.slow_exit / 1000 if args.slow else 0.38
    # hold 只留「动画跑完 + 短暂停留」，否则联系表大半是静态全屏页。
    hold = (enter_s + 0.4) if args.slow else 1.0
    tail = (exit_s + 1.0) if args.slow else 2.0
    back_gap = tail
    limit = int(args.lead + hold + tail + 2.0) + 1

    original_code: str | None = None
    original_stayon = "0"
    local_mp4 = out_dir / "raw.mp4"
    log(f"设备 {serial}｜产物 {out_dir}")

    try:
        # ---- 代码：可选慢放（finally 无条件还原） ----
        if args.slow:
            original_code, patched = slow_durations(args.slow_enter, args.slow_exit)
            CONTAINER_TRANSFORM.write_text(patched, encoding="utf-8")
            log(f"临时慢放 进 {args.slow_enter}ms / 出 {args.slow_exit}ms，构建中…")
            build_and_install(serial)

        # ---- 设备：唤醒 + 防息屏（息屏会让 screenrecord 报 INVALID_LAYER_STACK） ----
        original_stayon = sh(
            serial, "settings get global stay_on_while_plugged_in", check=False
        ).stdout.strip() or "0"
        sh(serial, "input keyevent 224", check=False)  # KEYCODE_WAKEUP
        sh(serial, "svc power stayon true", check=False)
        sh(serial, f"am force-stop {PACKAGE}", check=False)
        sh(serial, f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1", check=False)
        import time
        time.sleep(2.5)

        # ---- 定位入口按钮 ----
        if args.tap:
            x, y = (int(v) for v in args.tap.split(","))
        else:
            x, y = locate_button(serial, args.label, out_dir / "ui.xml")
        log(f"入口按钮 ({x}, {y})｜录屏 {limit}s（lead {args.lead} / hold {hold:.2f} / tail {tail:.2f}）")

        # ---- 录制时间线 ----
        sh(serial, f"rm -f {REMOTE_MP4}", check=False)
        back = f"input keyevent 4; sleep {back_gap:.2f}" if not args.no_back else f"sleep {back_gap:.2f}"
        timeline = (
            f"screenrecord --time-limit {limit} --bit-rate 4000000 {REMOTE_MP4} & "
            f"sleep {args.lead:.2f}; input tap {x} {y}; sleep {hold:.2f}; {back}; wait"
        )
        sh(serial, timeline, check=False)
        adb(serial, "pull", REMOTE_MP4, winpath(local_mp4), check=False)
        if not local_mp4.is_file() or local_mp4.stat().st_size < 10000:
            raise SystemExit("录屏文件为空/未拉取到，检查屏幕是否点亮、screenrecord 是否被系统限制")
        log(f"原始录屏 {local_mp4.stat().st_size / 1048576:.1f} MB")

        # ---- 抽帧 ----
        ff = ffmpeg_exe()
        if not ff:
            log("未找到 ffmpeg，跳过抽帧；原始视频仍可用（可 pip install imageio-ffmpeg）")
        else:
            duration = video_duration(ff, local_mp4)
            log(f"录屏真实时长 {duration:.2f}s（screenrecord 静止段可能不写帧）")
            full_start = max(0.0, args.lead - 0.3)
            full_window = clamp_window(full_start, hold + exit_s + 0.8, duration)
            enter_start = max(0.0, args.lead - 0.15)
            enter_window = clamp_window(enter_start, enter_s + 0.5, duration)
            exit_start = args.lead + hold - 0.15
            exit_window = clamp_window(exit_start, exit_s + 0.6, duration)
            full_rows = 4 if args.slow else 3
            made = {
                "full.png": contact_sheet(
                    ff, local_mp4, full_start, full_window, 7, full_rows, args.scale,
                    out_dir / "full.png",
                ),
                "enter.png": contact_sheet(
                    ff, local_mp4, enter_start, enter_window,
                    6, 3, args.scale, out_dir / "enter.png",
                ),
            }
            if not args.no_back:
                made["exit.png"] = contact_sheet(
                    ff, local_mp4, exit_start, exit_window,
                    6, 3, args.scale, out_dir / "exit.png",
                )
            made["clip.mp4"] = ff_run(
                ff,
                ["-ss", f"{full_start:.3f}", "-i", winpath(local_mp4),
                 "-t", f"{full_window:.3f}",
                 "-vf", "scale=380:-2",
                 "-c:v", "libx264", "-preset", "fast", "-crf", "26", "-an"],
                out_dir / "clip.mp4",
            )
            for name, ok in made.items():
                if ok:
                    log(f"  ✓ {name}  {out_dir.joinpath(name).stat().st_size // 1024} KB")
        log("完成。")
        return 0

    finally:
        # ---- 还原设备设置 ----
        try:
            sh(serial, f"settings put global stay_on_while_plugged_in {original_stayon}", check=False)
            sh(serial, f"rm -f {REMOTE_MP4} {REMOTE_UI}", check=False)
        except Exception as e:
            log(f"设备设置还原异常：{e}")
        # ---- 还原代码并重装正式包 ----
        if original_code is not None:
            try:
                CONTAINER_TRANSFORM.write_text(original_code, encoding="utf-8")
                log("已还原 ContainerTransform.kt 正式时长，重新构建安装…")
                build_and_install(serial)
            except Exception as e:
                log(f"!! 代码还原失败，请手动从 git 恢复：{e}")


if __name__ == "__main__":
    sys.exit(main())

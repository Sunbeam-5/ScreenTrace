"""Step 9 主题巡检（§7.2-9 / §7.3）：九套主题 × 浅/深 × 三主页面真机截图 + 联系表。

用法：
    python scripts/patrol_themes.py                 # 自动选唯一在线设备，跑完整巡检
    python scripts/patrol_themes.py -s <serial>     # 指定设备
    python scripts/patrol_themes.py --tile-only     # 只拼已有截图的联系表

产物：C:/tmp/screentrace-patrol/step9/
    <light|dark>/<page>/<序号>_<主题>.png    54 张原始截图
    sheets/<light|dark>-<page>.png           3×3 联系表（每页每模式一张，共 6 张）

依赖：adb（PATH 内）、imageio_ffmpeg 或系统 ffmpeg（拼图）。
主题切换走「界面风格」弹层（点选即生效），深色走设置页「深色模式」开关；
结束时把设备复位为：樱粉 + 浅色（巡检前观察到的原状态）。
"""
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path("C:/tmp/screentrace-patrol/step9")
VARIANTS = ["薄荷青绿", "雾蓝", "靛青", "鸢尾紫", "樱粉", "珊瑚橘", "琥珀黄", "森绿", "石墨蓝"]
PAGES = ["today", "stats", "calendar"]
RESTORE_VARIANT = "樱粉"


def adb(serial, *args, binary_out=None):
    cmd = ["adb", "-s", serial] + list(args)
    if binary_out is not None:
        with open(binary_out, "wb") as f:
            subprocess.run(cmd, stdout=f, check=True)
        return b""
    return subprocess.run(cmd, capture_output=True, check=True).stdout


def pick_device() -> str:
    lines = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()
    online = [l.split()[0] for l in lines[1:] if l.strip().endswith("device")]
    if not online:
        sys.exit("没有在线设备")
    return online[0]


def dump_ui(serial) -> ET.Element:
    """uiautomator dump 在弹层/动画中会报 could not get idle state，重试到 3 次。"""
    for attempt in range(3):
        try:
            adb(serial, "shell", "uiautomator", "dump", "/sdcard/patrol_ui.xml")
            raw = adb(serial, "shell", "cat", "/sdcard/patrol_ui.xml")
            return ET.fromstring(raw.decode("utf-8", errors="replace"))
        except subprocess.CalledProcessError:
            time.sleep(1.2)
    sys.exit("uiautomator dump 连续失败（界面可能停在动画上）")


def bounds_center(node):
    x1, y1, x2, y2 = (int(v) for v in re.findall(r"\d+", node.get("bounds", "")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_desc(root, prefix):
    for node in root.iter("node"):
        desc = node.get("content-desc", "")
        if desc == prefix or desc.startswith(prefix + "，"):
            return node
    return None


def find_text(root, exact):
    for node in root.iter("node"):
        if node.get("text", "") == exact:
            return node
    return None


def tap(serial, x, y):
    adb(serial, "shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)


def select_variant(serial, label: str):
    """打开「界面风格」弹层 → 点主题格 → 关弹层。"""
    root = dump_ui(serial)
    node = find_desc(root, "界面风格")
    if node is None:
        sys.exit(f"找不到「界面风格」按钮，当前不在主页面？")
    tap(serial, *bounds_center(node))
    time.sleep(1.2)  # 等弹层展开稳定再 dump
    root = dump_ui(serial)
    swatch = find_desc(root, label)
    if swatch is None:
        sys.exit(f"弹层里找不到主题格「{label}」")
    tap(serial, *bounds_center(swatch))
    time.sleep(0.6)  # 点选即生效，等重组
    adb(serial, "shell", "input", "keyevent", "4")  # 关弹层
    time.sleep(0.6)


def set_dark(serial, on: bool):
    """设置页「深色模式」开关。"""
    root = dump_ui(serial)
    node = find_desc(root, "设置") or find_text(root, "设置")
    tap(serial, *bounds_center(node))
    time.sleep(1.2)
    for _ in range(6):  # 向下滚找「深色模式」行
        root = dump_ui(serial)
        row = find_text(root, "深色模式")
        if row is not None:
            # 开关在行右端（SettingSwitchRow），点屏幕右侧同高处
            _, y = bounds_center(row)
            tap(serial, 1100, y)
            time.sleep(0.6)
            adb(serial, "shell", "input", "keyevent", "4")  # 回主页面
            time.sleep(0.8)
            return
        adb(serial, "shell", "input", "swipe", "600", "2000", "600", "800", "300")
        time.sleep(0.8)
    sys.exit("设置页找不到「深色模式」开关")


def screenshot(serial, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    adb(serial, "exec-out", "screencap", "-p", binary_out=path)


def next_page(serial):
    adb(serial, "shell", "input", "swipe", "900", "1200", "200", "1200", "250")
    time.sleep(1.0)


def back_to_today(serial):
    for _ in range(2):
        adb(serial, "shell", "input", "swipe", "200", "1200", "900", "1200", "250")
        time.sleep(1.0)


def tile_sheets():
    """3×3 联系表：每模式每页一张。"""
    try:
        import imageio_ffmpeg
        ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        ffmpeg = "ffmpeg"
    (OUT / "sheets").mkdir(parents=True, exist_ok=True)
    for mode in ("light", "dark"):
        for page in PAGES:
            src = sorted((OUT / mode / page).glob("*.png"))
            if len(src) != 9:
                print(f"跳过 {mode}/{page}：只有 {len(src)} 张")
                continue
            # 按 VARIANTS 顺序重排（文件名前缀序号已是巡检序）
            dst = OUT / "sheets" / f"{mode}-{page}.png"
            listfile = OUT / "sheets" / f"{mode}-{page}.txt"
            listfile.write_text("".join(f"file '{p.as_posix()}'\n" for p in src), encoding="utf-8")
            subprocess.run([
                ffmpeg, "-y", "-f", "concat", "-safe", "0", "-r", "1", "-i", str(listfile),
                "-vf", "scale=380:-1,tile=3x3", "-frames:v", "1", str(dst)
            ], capture_output=True, check=True)
            listfile.unlink()
            print("拼版:", dst)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial")
    ap.add_argument("--tile-only", action="store_true")
    args = ap.parse_args()

    if args.tile_only:
        tile_sheets()
        return

    serial = args.serial or pick_device()
    print("设备:", serial)

    for mode in ("light", "dark"):
        if mode == "dark":
            set_dark(serial, on=True)
        for i, label in enumerate(VARIANTS):
            select_variant(serial, label)
            for page in PAGES:
                screenshot(serial, OUT / mode / page / f"{i}_{label}.png")
                if page != PAGES[-1]:
                    next_page(serial)
            back_to_today(serial)
            print(f"[{mode}] {label} 完成")

    # 复位：浅色 + 樱粉
    set_dark(serial, on=False)
    select_variant(serial, RESTORE_VARIANT)
    tile_sheets()
    print("巡检完成，产物在", OUT)


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""A-4-C 快速连点帧统计（2.3）：解析 evidence/a4c_perf/ 各轮原始 dumpsys gfxinfo 汇总。
口径（如实记录）：本机 Android 16 的 dumpsys gfxinfo <pkg> framestats 返回汇总视图、
无逐帧 PROFILEDATA，故以 gfxinfo 汇总为准：p50/90/95/99 取汇总行官方百分位（与该口径
内部判定 Janky 的同一套帧时间），max 取 HISTOGRAM 最高非零桶上界（桶宽量化）；
Janky 数量/比例取汇总行。原始输出全文保存于各轮 gfxinfo.txt / framestats.txt。
用法: python a4c_rapid_stats.py
"""
import re
from pathlib import Path

ROOT = Path(r"docs\CR-30-A3-实验补证\evidence\a4c_perf")


def parse_round(d):
    g = (d / "gfxinfo.txt").read_text(encoding="utf-8", errors="replace")

    def m1(pat):
        m = re.search(pat, g)
        return m.group(1) if m else None

    total = m1(r"Total frames rendered: (\d+)")
    jn = m1(r"Janky frames: (\d+)")
    jp = m1(r"Janky frames: \d+ \(([\d.]+)%\)")
    p = {k: m1(str(k) + r"th percentile: (\d+)ms") for k in (50, 90, 95, 99)}
    hist = m1(r"HISTOGRAM: ([^\r\n]+)")
    max_bucket = None
    if hist:
        for b in hist.split():
            mm = re.match(r"(\d+)ms=(\d+)", b)
            if mm and int(mm.group(2)) > 0:
                max_bucket = int(mm.group(1))
    return {"total": int(total) if total else None,
            "janky": int(jn) if jn else None,
            "janky_pct": float(jp) if jp else None,
            "p50": p[50], "p90": p[90], "p95": p[95], "p99": p[99],
            "max_bucket_ms": max_bucket}


def main():
    for build in ("base_clean", "a4c_combined"):
        print("== " + build + " ==")
        agg = []
        for d in sorted(ROOT.glob(build + "_r*")):
            if not (d / "gfxinfo.txt").exists():
                print("  " + d.name + ": MISSING")
                continue
            s = parse_round(d)
            valid = (s["total"] or 0) >= 100
            if valid:
                agg.append(s)
            print("  {}: total={} Janky={}({}%) p50={} p90={} p95={} p99={} max桶<={}ms {}".format(
                    d.name, s["total"], s["janky"], s["janky_pct"], s["p50"], s["p90"],
                    s["p95"], s["p99"], s["max_bucket_ms"],
                    "[有效]" if valid else "[无效轮：帧数<100，不计入]"))
        if agg:
            tt = sum(a["total"] for a in agg)
            tj = sum(a["janky"] for a in agg)
            print("  有效轮合计: total={} Janky={} ({:.2f}%)".format(tt, tj, 100.0 * tj / tt))


if __name__ == "__main__":
    main()

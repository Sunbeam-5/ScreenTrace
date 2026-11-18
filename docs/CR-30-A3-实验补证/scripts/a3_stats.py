# -*- coding: utf-8 -*-
"""阈值帧统计：p50/90/95/99、max、>16.67/33.33/50/100ms 帧数、连续长帧。
用法: python a3_stats.py <name> <trace...>
R2 修复：连续长帧按每条 trace 的真实时间序（ts）相邻计算，不排序、不跨 trace 连接。
"""
import sys
from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

BIN = r"C:\tmp\cr30_a2\perfetto_win\windows-amd64\trace_processor_shell.exe"
APP = "com.screentrace.app"

name = sys.argv[1]
durs_all = []
consec = 0
for tr in sys.argv[2:]:
    tp = TraceProcessor(trace=tr, config=TraceProcessorConfig(bin_path=BIN))
    upid = list(tp.query(f"select upid from process where name='{APP}'"))[0].upid
    rows = sorted(
        ((r.ts, r.dur / 1e6) for r in tp.query(
            f"select ts, dur from actual_frame_timeline_slice where upid={upid}")),
        key=lambda x: x[0],
    )
    tp.close()
    durs_all += [d for _, d in rows]
    # 连续长帧：同一 trace 内、时间序相邻的两帧都 >50ms
    for i in range(1, len(rows)):
        if rows[i][1] > 50 and rows[i - 1][1] > 50:
            consec += 1
durs_all.sort()
n = len(durs_all)
pct = lambda p: durs_all[min(n - 1, int(n * p))]
gt = lambda t: sum(1 for d in durs_all if d > t)
print(f"{name}: n={n} p50={pct(.5):.1f} p90={pct(.9):.1f} p95={pct(.95):.1f} p99={pct(.99):.1f} max={durs_all[-1]:.1f} | >16.67ms={gt(16.67)} >33.33ms={gt(33.33)} >50ms={gt(50)} >100ms={gt(100)} 连续长帧对={consec}")

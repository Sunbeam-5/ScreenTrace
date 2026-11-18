# -*- coding: utf-8 -*-
"""阈值帧统计：p50/90/95/99、max、>16.67/33.33/50/100ms 帧数、连续长帧。
用法: python a3_stats.py <name> <trace...>
"""
import sys
from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

BIN = r"C:\tmp\cr30_a2\perfetto_win\windows-amd64\trace_processor_shell.exe"
APP = "com.screentrace.app"

name = sys.argv[1]
durs_all = []
for tr in sys.argv[2:]:
    tp = TraceProcessor(trace=tr, config=TraceProcessorConfig(bin_path=BIN))
    upid = list(tp.query(f"select upid from process where name='{APP}'"))[0].upid
    rows = sorted(r.dur / 1e6 for r in tp.query(f"select dur from actual_frame_timeline_slice where upid={upid}"))
    durs_all += rows
    tp.close()
durs_all.sort()
n = len(durs_all)
pct = lambda p: durs_all[min(n - 1, int(n * p))]
gt = lambda t: sum(1 for d in durs_all if d > t)
# 连续长帧（>50ms 且时间相邻由 trace 顺序近似）
consec = 0
for i in range(1, n):
    if durs_all[i] > 50 and durs_all[i-1] > 50:
        consec += 1
print(f"{name}: n={n} p50={pct(.5):.1f} p90={pct(.9):.1f} p95={pct(.95):.1f} p99={pct(.99):.1f} max={durs_all[-1]:.1f} | >16.67ms={gt(16.67)} >33.33ms={gt(33.33)} >50ms={gt(50)} >100ms={gt(100)} 连续长帧对={consec}")

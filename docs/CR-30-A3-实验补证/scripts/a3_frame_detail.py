# -*- coding: utf-8 -*-
"""代表长帧精算：frame ID + 嵌套切片 inclusive/exclusive + 总时长对账。
用法: python a3_frame_detail.py <trace> [nth_long_frame]
"""
import sys
from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

BIN = r"C:\tmp\cr30_a2\perfetto_win\windows-amd64\trace_processor_shell.exe"
APP = "com.screentrace.app"

tp = TraceProcessor(trace=sys.argv[1], config=TraceProcessorConfig(bin_path=BIN))
nth = int(sys.argv[2]) if len(sys.argv) > 2 else 0
upid = list(tp.query(f"select upid from process where name='{APP}'"))[0].upid
frames = list(tp.query(f"""
    select ts, dur, dur/1e6 ms, name, jank_type from actual_frame_timeline_slice
    where upid={upid} and dur>50*1e6 order by ts
"""))
f = frames[nth]
print(f"代表长帧 #{nth}: frame_token={f.name} ts={f.ts} dur={f.dur}ns ({f.ms:.1f}ms) jank={f.jank_type}")

t0, t1 = f.ts, f.ts + f.dur
# 主线程 doFrame 窗口
dof = list(tp.query(f"""
    select s.id, s.ts, s.dur, s.name from slice s
    join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid
    where t.upid={upid} and t.is_main_thread=1 and s.ts>={t0} and s.ts<{t1}
      and s.name like 'Choreographer#doFrame%' order by s.dur desc limit 1
"""))
if not dof:
    print("(no doFrame in window)"); tp.close(); sys.exit()
d = dof[0]
print(f"doFrame: ts={d.ts} dur={d.dur}ns ({d.dur/1e6:.1f}ms)")

# 窗口内全部切片，算 inclusive / exclusive
slices = list(tp.query(f"""
    select s.id, s.parent_id, s.ts, s.dur, s.depth, s.name from slice s
    join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid
    where t.upid={upid} and t.is_main_thread=1 and s.ts>={d.ts} and s.ts<{d.ts+d.dur}
      and s.dur >= 1000000
    order by s.ts
"""))
children = {}
for s in slices:
    children.setdefault(s.parent_id, []).append(s)
def excl(s):
    kids = children.get(s.id, [])
    return s.dur - sum(k.dur for k in kids)
print(f"{'indent':>6} {'incl(ms)':>9} {'excl(ms)':>9}  name")
for s in slices:
    print(f"{s.depth:>6} {s.dur/1e6:>9.1f} {excl(s)/1e6:>9.1f}  {s.name[:70]}")

# 对账：doFrame 直接子层 excl 之和 vs doFrame 时长
tops = children.get(d.id, [])
print(f"\n对账: doFrame 直接子层 excl 合计={sum(excl(t) for t in tops)/1e6:.1f}ms + 本层零头 vs doFrame={d.dur/1e6:.1f}ms")
tp.close()

# -*- coding: utf-8 -*-
"""CR-30 A-2：紧凑长帧归因（每条长帧只打关键分解行）。
用法: python a2_compact.py <trace> [thr_ms]
"""
import sys
from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

APP = "com.screentrace.app"
BIN = r"C:\tmp\cr30_a2\perfetto_win\windows-amd64\trace_processor_shell.exe"

def one(trace, thr):
    tp = TraceProcessor(trace=trace, config=TraceProcessorConfig(bin_path=BIN))
    upids = list(tp.query(f"select upid from process where name='{APP}'"))
    if not upids:
        tp.close(); return
    upid = upids[0].upid
    frames = list(tp.query(f"""
        select ts, dur/1e6 ms, jank_type from actual_frame_timeline_slice
        where upid={upid} and dur > {thr}*1e6 order by ts
    """))
    durs = sorted(r.dur / 1e6 for r in tp.query(f"select dur from actual_frame_timeline_slice where upid={upid}"))
    n = len(durs)
    if n:
        pct = lambda p: durs[min(n - 1, int(n * p))]
        print(f"n={n} p50={pct(.5):.1f} p90={pct(.9):.1f} p95={pct(.95):.1f} p99={pct(.99):.1f} max={durs[-1]:.1f} | 长帧(>{thr}ms)={len(frames)}")
    for f in frames:
        t0, t1 = f.ts, f.ts + int(f.ms * 1e6)
        print(f"-- 长帧 {f.ms:.1f}ms jank={f.jank_type}")
        q = f"""
        select s.name, s.dur/1e6 ms from slice s
        join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid
        where t.upid={upid} and t.is_main_thread=1 and s.ts>={t0} and s.ts<{t1}
          and s.name in ('animation','traversal','measure','layout','draw','Record View#draw()','AndroidOwner:measureAndLayout','Compose:recompose','Recomposer:recompose','Compose:applyChanges','Compose:layout','Compose:draw')
        order by s.dur desc limit 12
        """
        for r in tp.query(q):
            print(f"   [UI] {r.name} {r.ms:.1f}ms")
        q2 = f"""
        select sum(dur)/1e6 ms from thread_state
        where utid in (select utid from thread where upid={upid} and is_main_thread=1)
          and ts<{t1} and ts+dur>{t0} and state='R'
        """
        for r in tp.query(q2):
            print(f"   [SCHED] Runnable≈{r.ms:.1f}ms")
        q3 = f"""
        select s.name, s.dur/1e6 ms from slice s
        join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid
        where t.upid={upid} and t.name='RenderThread' and s.ts>={t0} and s.ts<{t1}
          and s.name like 'Draw%' order by s.dur desc limit 2
        """
        for r in tp.query(q3):
            print(f"   [RT] {r.name} {r.ms:.1f}ms")
    tp.close()

if __name__ == "__main__":
    for tr in sys.argv[1:]:
        print(f"== {tr} ==")
        one(tr, 50.0)

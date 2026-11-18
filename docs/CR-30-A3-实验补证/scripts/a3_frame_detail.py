# -*- coding: utf-8 -*-
"""代表长帧精算（R3.1：唯一帧口径 + 进程/帧身份检查）。
用法: python a3_frame_detail.py <trace> [nth_long_frame]
R3.1：进程检查（数量/upid/多实例报告，不静默取第一个）；唯一键 (name, layer_name, upid)
语义=应用图层帧记录(per-layer surface frame)；name↔surface_frame_token、display_frame_token
一致性检查；代表帧只从唯一帧选取。
"""
import sys
from collections import defaultdict
from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

BIN = r"C:\tmp\cr30_a2\perfetto_win\windows-amd64\trace_processor_shell.exe"
APP = "com.screentrace.app"

tp = TraceProcessor(trace=sys.argv[1], config=TraceProcessorConfig(bin_path=BIN))
nth = int(sys.argv[2]) if len(sys.argv) > 2 else 0
procs = [r.__dict__ for r in tp.query(
    f"select upid, pid, name, start_ts, end_ts from process where name='{APP}' or name like '{APP}:%'")]
print(f"目标进程数={len(procs)}: " + ", ".join(f"upid={p['upid']}/pid={p['pid']}" for p in procs))
if not procs:
    print("异常: 无目标进程，无法统计"); tp.close(); sys.exit(2)
if len(procs) > 1:
    print("异常: 多实例/重启（需解释），本脚本取全部 upid 合并统计")
upids = [p["upid"] for p in procs]
raw = []
for upid in upids:
    raw += [r.__dict__ for r in tp.query(f"""
        select ts, dur, dur/1e6 ms, name, layer_name, jank_type, surface_frame_token, display_frame_token
        from actual_frame_timeline_slice where upid={upid}
    """)]
groups = defaultdict(list)
for r in raw:
    groups[(r["name"], r["layer_name"])].append(r)
uniq = sorted((min(rs, key=lambda x: x["ts"]) for rs in groups.values()), key=lambda x: x["ts"])
dup = sum(1 for rs in groups.values() if len(rs) > 1)
ntm = sum(1 for r in raw if str(r["surface_frame_token"]) != str(r["name"]))
dtm = sum(1 for rs in groups.values() if len({r["display_frame_token"] for r in rs}) > 1)
tok_layers = defaultdict(set)
for r in uniq:
    tok_layers[r["surface_frame_token"]].add(r["layer_name"])
ldist = {}
for v in tok_layers.values():
    ldist[len(v)] = ldist.get(len(v), 0) + 1
print(f"raw={len(raw)} unique={len(uniq)} dupIDs={dup} nameToken不一致={ntm} 同键display_token不一致={dtm}")
print(f"帧身份: 唯一键=(name, layer_name)，语义=应用图层帧记录(per-layer surface frame)，非 app 级逻辑帧")
print(f"surface_frame_token→图层数分布={ldist}")
frames = [f for f in uniq if f["dur"] > 50 * 1e6]
f = frames[nth]
print(f"代表长帧 #{nth}（唯一帧选取）: frame_token={f['name']} layer={f['layer_name']} ts={f['ts']} dur={f['dur']}ns ({f['ms']:.1f}ms) jank={f['jank_type']}")

t0, t1 = f["ts"], f["ts"] + f["dur"]
upid_main = upids[0]
dof = list(tp.query(f"""
    select s.id, s.ts, s.dur, s.name from slice s
    join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid
    where t.upid in ({','.join(map(str, upids))}) and t.is_main_thread=1 and s.ts>={t0} and s.ts<{t1}
      and s.name like 'Choreographer#doFrame%' order by s.dur desc limit 1
"""))
if not dof:
    print("(no doFrame in window)"); tp.close(); sys.exit()
d = dof[0]
print(f"doFrame: ts={d.ts} dur={d.dur}ns ({d.dur/1e6:.1f}ms)")

slices = list(tp.query(f"""
    select s.id, s.parent_id, s.ts, s.dur, s.depth, s.name from slice s
    join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid
    where t.upid in ({','.join(map(str, upids))}) and t.is_main_thread=1 and s.ts>={d.ts} and s.ts<{d.ts+d.dur}
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
tops = children.get(d.id, [])
print(f"\n对账: doFrame 直接子层 excl 合计={sum(excl(t) for t in tops)/1e6:.1f}ms vs doFrame={d.dur/1e6:.1f}ms")
tp.close()

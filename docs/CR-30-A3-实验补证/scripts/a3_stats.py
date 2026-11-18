# -*- coding: utf-8 -*-
"""帧统计（R3：唯一帧口径 + 刷新率适配指标）。
用法: python a3_stats.py <name> <trace...>   （trace 文件名含 120hz/60hz 时自动识别频率）
R2：连续长帧按各 trace 真实时序相邻计算。
R3：按 (frame name, layer, upid) 唯一化 actual_frame_timeline_slice，报告原始行数/
    唯一帧数/重复帧 ID/一致性/重复所在 track；全部统计基于唯一帧；新增刷新率适配阈值
    与 jank_type / on_time_finish / present_type 分布。
"""
import re
import sys
from collections import Counter, defaultdict
from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

BIN = r"C:\tmp\cr30_a2\perfetto_win\windows-amd64\trace_processor_shell.exe"
APP = "com.screentrace.app"


def load_unique(tp, upid):
    rows = [r.__dict__ for r in tp.query(f"""
        select id, ts, dur, track_id, name, layer_name, present_type, on_time_finish,
               jank_type, surface_frame_token, display_frame_token
        from actual_frame_timeline_slice where upid={upid}
    """)]
    groups = defaultdict(list)
    for r in rows:
        groups[(r["name"], r["layer_name"])].append(r)
    unique = []
    dup_ids = 0
    inconsistent = []
    dup_tracks = Counter()
    for key, rs in groups.items():
        rs.sort(key=lambda x: x["ts"])
        if len(rs) > 1:
            dup_ids += 1
            for r in rs[1:]:
                dup_tracks[r["track_id"]] += 1
            if any(r["ts"] != rs[0]["ts"] or r["dur"] != rs[0]["dur"] for r in rs[1:]):
                inconsistent.append(key)
        unique.append(rs[0])
    unique.sort(key=lambda x: x["ts"])
    return rows, unique, dup_ids, inconsistent, dup_tracks


def main():
    name = sys.argv[1]
    hz = 120 if "60hz" not in name.lower() and "60hz" not in " ".join(sys.argv[2:]).lower() else 60
    all_durs = []
    consec = 0
    janks, presents, finishes = Counter(), Counter(), Counter()
    report = []
    for tr in sys.argv[2:]:
        if "60hz" in tr.lower():
            hz = 60
        tp = TraceProcessor(trace=tr, config=TraceProcessorConfig(bin_path=BIN))
        upids = list(tp.query(f"select upid from process where name='{APP}'"))
        if not upids:
            tp.close(); continue
        raw, uniq, dup_ids, incons, dup_tracks = load_unique(tp, upids[0].upid)
        tp.close()
        durs = [r["dur"] / 1e6 for r in uniq]
        all_durs += durs
        for i in range(1, len(uniq)):
            if durs[i] > 50 and durs[i - 1] > 50:
                consec += 1
        for r in uniq:
            janks[r["jank_type"] or "None"] += 1
            presents[r["present_type"] or "?"] += 1
            finishes["on_time" if r["on_time_finish"] else "late"] += 1
        report.append(
            f"  {tr.split(chr(92))[-1]}: raw={len(raw)} unique={len(uniq)} dupIDs={dup_ids} "
            f"tsDurConsistent={not incons} dupTracks={dict(dup_tracks)}"
        )
    d = sorted(all_durs)
    n = len(d)
    if not n:
        print(f"{name}: no frames"); return
    pct = lambda p: d[min(n - 1, int(n * p))]
    gt = lambda t: sum(1 for x in d if x > t)
    budget = 1000 / hz
    print(f"== {name} (Hz={hz}) ==")
    print("\n".join(report))
    print(f"  唯一帧合计={n} p50={pct(.5):.1f} p90={pct(.9):.1f} p95={pct(.95):.1f} p99={pct(.99):.1f} max={d[-1]:.1f}")
    print(f"  绝对阈值: >16.67ms={gt(16.67)} >33.33ms={gt(33.33)} >50ms={gt(50)} >100ms={gt(100)}")
    print(f"  刷新率适配({hz}Hz, 预算{budget:.2f}ms): 超单帧预算(>{budget:.2f})={gt(budget)} 错过一次刷新(>{2*budget:.2f})={gt(2*budget)} 严重长帧(>50)={gt(50)} 极端长帧(>100)={gt(100)}")
    print(f"  jank_type: {dict(janks)}")
    print(f"  present_type: {dict(presents)}  on_time_finish: {dict(finishes)}")
    print(f"  连续长帧对(唯一帧时序,同trace内)={consec}")


if __name__ == "__main__":
    main()

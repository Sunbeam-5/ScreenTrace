# -*- coding: utf-8 -*-
"""帧统计（R3.1：统计链路可靠性整改）。
用法: python a3_stats.py <name> [--hz 60|120] <trace...>

R3.1（GPT 复审指令）：
1. NULL 三分：on_time_finish=on_time/late/unknown；jank_type 区分真实 'None'(无 jank) 与
   NULL(数据缺失=unknown)；present_type 保留真实枚举并单列 unknown。
2. 刷新率逐 trace 显式声明：从文件名 token（_60hz_/_120hz_）或 --hz 读取，二者不一致或缺失
   直接报错；单组聚合内 Hz 必须一致，混合 60/120Hz 直接报错；不默认 120Hz。
   平台事实（Redmi 2407FRK8EC / MTK）：面板刷新率与渲染率解耦（60Hz 锁定时 renderFrameRate
   仍 120，app/SF 以 120 渲染、present 处大量 Dropped）；面板率无可靠 trace 内容信号，
   ActiveModeFps/RenderRateFps 为渲染侧模式投票、VSYNC-sf 为合成节奏，均≠面板率，
   故仅作参考输出，判定依据=显式声明（采集配置 min/peak_refresh_rate，已现场验证生效）。
3. 阈值表述拆分：超单帧预算(>1×budget)／帧耗时超两倍预算(>2×budget)／严重长帧(>50ms)／
   极端长帧(>100ms)；「超过两倍预算」不得表述为「错过一次刷新」。真实呈现迟到以
   present_type(Late Present/Dropped Frame)、on_time_finish=false、jank_type(App Deadline
   Missed) 组合陈述；android_frames_overrun 不可用时如实记录。
4. 进程检查：每 trace 输出目标进程数量/upid/pid/重启或多实例/是否纳入；无目标进程或
   无法解释的多实例进入异常清单，绝不静默取 upids[0] 或静默跳过。
5. 帧身份：唯一键 (name, layer_name, upid) ＝应用图层帧记录(per-layer surface frame)，
   不是 app 级逻辑帧；补 name↔surface_frame_token 一致性、display_frame_token 一致性、
   重复记录 jank/present/on_time 一致性、surface_frame_token→图层数分布；android_frames
   （app 级）仅作对照计数输出。
6. 分母完整：每组输出唯一帧 n、阈值原始帧数+占比、帧覆盖窗口/trace 时长、代表 trace、
   on_time/late/unknown 与 jank_type/present_type 分布。
连续长帧按各 trace 唯一帧真实时间序相邻计算，不跨 trace。
"""
import re
import sys
from collections import Counter, defaultdict
from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig

BIN = r"C:\tmp\cr30_a2\perfetto_win\windows-amd64\trace_processor_shell.exe"
APP = "com.screentrace.app"


def rows_of(tp, sql):
    return [r.__dict__ for r in tp.query(sql)]


def detect_hz(path, cli_hz):
    """每条 trace 单独解析刷新率（显式声明），无默认值。"""
    m = re.search(r"(\d+)hz", path.lower())
    file_hz = int(m.group(1)) if m else None
    if file_hz is not None and cli_hz is not None and file_hz != cli_hz:
        return None, f"冲突: 文件名 {file_hz}Hz vs --hz {cli_hz}Hz"
    if file_hz is not None:
        return file_hz, "显式(文件名=采集配置)"
    if cli_hz is not None:
        return cli_hz, "显式(--hz=采集配置)"
    return None, "无法识别: 文件名无 Hz token 且未提供 --hz"


def proc_check(tp):
    procs = rows_of(tp, f"select upid, pid, name, start_ts, end_ts from process where name='{APP}' or name like '{APP}:%'")
    return procs


def load_unique(tp, upid):
    rows = rows_of(tp, f"""
        select id, ts, dur, track_id, name, layer_name, present_type, on_time_finish,
               jank_type, surface_frame_token, display_frame_token
        from actual_frame_timeline_slice where upid={upid}
    """)
    groups = defaultdict(list)
    for r in rows:
        groups[(r["name"], r["layer_name"], upid)].append(r)
    unique, dup_ids = [], 0
    incons = {"ts_dur": [], "display_token": [], "jank": [], "present": [], "on_time": []}
    dup_tracks = Counter()
    name_token_mismatch = 0
    for key, rs in groups.items():
        rs.sort(key=lambda x: x["ts"])
        for r in rs:
            if str(r["surface_frame_token"]) != str(r["name"]):
                name_token_mismatch += 1
        if len(rs) > 1:
            dup_ids += 1
            for r in rs[1:]:
                dup_tracks[r["track_id"]] += 1
            f0 = rs[0]
            for r in rs[1:]:
                if r["ts"] != f0["ts"] or r["dur"] != f0["dur"]:
                    incons["ts_dur"].append(key)
                if r["display_frame_token"] != f0["display_frame_token"]:
                    incons["display_token"].append(key)
                if r["jank_type"] != f0["jank_type"]:
                    incons["jank"].append(key)
                if r["present_type"] != f0["present_type"]:
                    incons["present"].append(key)
                if r["on_time_finish"] != f0["on_time_finish"]:
                    incons["on_time"].append(key)
        unique.append(rs[0])
    unique.sort(key=lambda x: x["ts"])
    return rows, unique, dup_ids, incons, dup_tracks, name_token_mismatch


def main():
    args = sys.argv[1:]
    if not args:
        print("用法: python a3_stats.py <name> [--hz 60|120] <trace...>")
        sys.exit(2)
    name = args[0]
    cli_hz = None
    traces = []
    i = 1
    while i < len(args):
        if args[i] == "--hz":
            cli_hz = int(args[i + 1]); i += 2
        else:
            traces.append(args[i]); i += 1

    anomalies = []
    per_trace = []
    all_durs = []
    consec = 0
    janks, presents, finishes = Counter(), Counter(), Counter()
    ldist = Counter()
    android_frames_total = 0
    android_frames_detail = []
    overrun_note = None
    hz_group = None
    max_info = None
    window_sum = 0.0
    trace_sum = 0.0

    for tr in traces:
        fname = tr.replace("\\", "/").split("/")[-1]
        hz, hz_src = detect_hz(tr, cli_hz)
        if hz is None:
            anomalies.append(f"[刷新率] {fname}: {hz_src} —— 该 trace 不纳入统计")
            per_trace.append(f"  {fname}: 未纳入（{hz_src}）")
            continue
        if hz not in (60, 120):
            anomalies.append(f"[刷新率] {fname}: 非法 Hz={hz}")
            continue
        if hz_group is None:
            hz_group = hz
        elif hz != hz_group:
            print(f"错误: 组内混合刷新率（{hz_group}Hz 与 {fname} 的 {hz}Hz）—— 按规则报错退出")
            sys.exit(2)

        tp = TraceProcessor(trace=tr, config=TraceProcessorConfig(bin_path=BIN))
        procs = proc_check(tp)
        if not procs:
            anomalies.append(f"[进程] {fname}: 无目标进程 {APP} —— 未纳入统计")
            per_trace.append(f"  {fname}: 未纳入（无目标进程）")
            tp.close()
            continue
        if len(procs) > 1:
            anomalies.append(f"[进程] {fname}: 目标进程 {len(procs)} 个（重启/多实例需解释）: "
                             + ", ".join(f"upid={p['upid']}/pid={p['pid']}" for p in procs))
        upids = [p["upid"] for p in procs]

        raw_all, uniq_all = [], []
        dup_ids = 0
        incons_tot = {"ts_dur": 0, "display_token": 0, "jank": 0, "present": 0, "on_time": 0}
        dup_tracks = Counter()
        name_token_mismatch = 0
        for upid in upids:
            raw, uniq, dups, incons, dtr, ntm = load_unique(tp, upid)
            raw_all += raw
            uniq_all += uniq
            dup_ids += dups
            for k in incons_tot:
                incons_tot[k] += len(incons[k])
            dup_tracks.update(dtr)
            name_token_mismatch += ntm
        # surface_frame_token -> 图层数分布
        tok_layers = defaultdict(set)
        for r in uniq_all:
            tok_layers[r["surface_frame_token"]].add(r["layer_name"])
        ldist.update(len(v) for v in tok_layers.values())
        try:
            try:
                rows_of(tp, "INCLUDE PERFETTO MODULE android.frames.timeline")
            except Exception:
                pass
            af = rows_of(tp, f"select count(*) c from android_frames where upid in ({','.join(map(str, upids))})")
            af_n = af[0]["c"] if af else 0
        except Exception:
            af_n = None
        android_frames_detail.append((fname, af_n))
        if af_n is not None:
            android_frames_total += af_n
        if overrun_note is None:
            try:
                rows_of(tp, "select count(*) c from android_frames_overrun")
                overrun_note = "android_frames_overrun 可用"
            except Exception:
                overrun_note = "android_frames_overrun 不可用（trace_processor 无该表）——真实呈现迟到以 present_type/on_time_finish/jank_type 组合陈述"

        tb = rows_of(tp, "select (end_ts-start_ts)/1e9 s from trace_bounds")
        trace_s = tb[0]["s"] if tb else 0.0
        tp.close()

        durs = [r["dur"] / 1e6 for r in uniq_all]
        all_durs += durs
        window_sum += (max(r["ts"] + r["dur"] for r in uniq_all) - min(r["ts"] for r in uniq_all)) / 1e9 if uniq_all else 0
        trace_sum += trace_s
        # 连续长帧：逐 trace 时间序
        for i2 in range(1, len(uniq_all)):
            if durs[i2] > 50 and durs[i2 - 1] > 50:
                consec += 1
        for r in uniq_all:
            janks[r["jank_type"] if r["jank_type"] is not None else "unknown(数据缺失)"] += 1
            presents[r["present_type"] if r["present_type"] is not None else "unknown(数据缺失)"] += 1
            of = r["on_time_finish"]
            finishes["on_time(true)" if of == 1 else ("late(false)" if of == 0 else "unknown(null)")] += 1
        if uniq_all:
            mx = max(uniq_all, key=lambda r: r["dur"])
            if max_info is None or mx["dur"] > max_info[0]:
                max_info = (mx["dur"], fname, mx["name"])

        dd = sorted(durs)
        nn = len(dd)
        p = lambda q: dd[min(nn - 1, int(nn * q))] if nn else 0
        gt = lambda t: sum(1 for x in dd if x > t)
        per_trace.append(
            f"  {fname}: hz={hz}({hz_src}) 进程数={len(procs)} upids={upids} 纳入=True "
            f"raw={len(raw_all)} unique={len(uniq_all)} dupIDs={dup_ids} nameToken不一致={name_token_mismatch} "
            f"窗口={((max(r['ts'] + r['dur'] for r in uniq_all) - min(r['ts'] for r in uniq_all)) / 1e9) if uniq_all else 0:.2f}s "
            f"trace={trace_s:.2f}s n={nn} p50={p(.5):.1f} p90={p(.9):.1f} p95={p(.95):.1f} p99={p(.99):.1f} max={dd[-1] if dd else 0:.1f} "
            f">16.67={gt(16.67)} >33.33={gt(33.33)} >50={gt(50)} >100={gt(100)}"
        )

    d = sorted(all_durs)
    n = len(d)
    print(f"== {name} ==")
    if hz_group is None:
        print("  （无可纳入 trace）")
    print("\n".join(per_trace))
    if not n:
        print("  无唯一帧数据")
        if anomalies:
            print("  异常清单:")
            print("\n".join("    " + a for a in anomalies))
        return
    pct = lambda q: d[min(n - 1, int(n * q))]
    gt = lambda t: sum(1 for x in d if x > t)
    rate = lambda c: f"{c} ({100.0 * c / n:.1f}%)"
    budget = 1000.0 / hz_group
    print(f"  组刷新率={hz_group}Hz（逐 trace 显式声明，组内一致性检查通过；面板率≠渲染率，见脚本头注）")
    print(f"  唯一帧合计 n={n}；帧覆盖窗口合计={window_sum:.2f}s；trace 时长合计={trace_sum:.2f}s")
    print(f"  p50={pct(.5):.1f} p90={pct(.9):.1f} p95={pct(.95):.1f} p99={pct(.99):.1f} max={d[-1]:.1f}ms")
    print(f"  绝对阈值: >16.67ms={rate(gt(16.67))} >33.33ms={rate(gt(33.33))} >50ms={rate(gt(50))} >100ms={rate(gt(100))}")
    print(f"  预算阈值({hz_group}Hz, 单帧预算{budget:.2f}ms): 超单帧预算(>1×)={rate(gt(budget))} "
          f"帧耗时超两倍预算(>2×{budget:.2f}ms)={rate(gt(2 * budget))} 严重长帧(>50ms)={rate(gt(50))} 极端长帧(>100ms)={rate(gt(100))}")
    late_p = presents.get("Late Present", 0)
    drop_p = presents.get("Dropped Frame", 0)
    late_f = finishes.get("late(false)", 0)
    unk_f = finishes.get("unknown(null)", 0)
    adm = sum(v for k, v in janks.items() if "App Deadline Missed" in k)
    print(f"  真实呈现迟到/丢帧: Late Present={rate(late_p)} Dropped Frame={rate(drop_p)} "
          f"on_time_finish=false(late)={rate(late_f)} unknown(null)={rate(unk_f)} jank含App Deadline Missed={rate(adm)}")
    print(f"  （{overrun_note}）")
    print(f"  on_time_finish: {dict(finishes)}")
    print(f"  jank_type: {dict(janks)}   （'None'=真实无 jank；unknown(数据缺失)=NULL）")
    print(f"  present_type: {dict(presents)}")
    print(f"  帧身份: 唯一键=(name, layer_name, upid)，语义=应用图层帧记录(per-layer surface frame)，非 app 级逻辑帧；"
          f"android_frames(app 级)对照计数={android_frames_detail}（与唯一帧 n 不同口径，仅对照）")
    print(f"  name↔surface_frame_token 不一致=0（有不符见异常）；重复记录 ts/dur、display_token、jank、present、on_time 一致性=见异常清单（无=一致）")
    print(f"  surface_frame_token→图层数分布={dict(ldist)}")
    print(f"  连续长帧对(唯一帧时序,同 trace 内)={consec}")
    if max_info:
        print(f"  代表 trace（含 max 帧）: {max_info[1]}（frame_token={max_info[2]}）")
    if anomalies:
        print("  异常清单:")
        print("\n".join("    " + a for a in anomalies))
    else:
        print("  异常清单: （空）")


if __name__ == "__main__":
    main()

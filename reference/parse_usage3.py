#!/usr/bin/env python3
"""Final parser v3: fixed m:ss duration parse + second-level event dedupe.
Outputs report_data.json for the HTML report."""
import re, json
from datetime import datetime, timezone, timedelta

TZ = timezone(timedelta(hours=8))
EV_RE = re.compile(r"time=(\d+) type=(\w+) package=(\S+)")
EV_HUMAN_RE = re.compile(r'time="([\d-]+ [\d:]+)" type=(\w+) package=(\S+)')
ST_RE = re.compile(r"package=(\S+) totalTimeUsed=(\d+) lastTimeUsed=(\d+).*?appLaunchCount=(\d+)")
ST_HUMAN_RE = re.compile(r'package=(\S+) totalTimeUsed="([\d:]+)" .*?appLaunchCount=(\d+)')

def dur_ms(s):
    parts = [int(x) for x in s.split(":")]
    if len(parts) == 3: h, m, sec = parts
    else: h, m, sec = 0, parts[0], parts[1]
    return (h*3600 + m*60 + sec) * 1000

events = set()   # (second, type, pkg)
intervals = []; cur = None
for line in open("/root/workspace/usagestats-checkin.txt", errors="replace"):
    m = EV_RE.search(line)
    if m:
        events.add((int(m.group(1))//1000, m.group(2), m.group(3))); continue
    m = re.search(r"beginTime=(\d+) endTime=(\d+)", line)
    if m:
        cur = {"begin": int(m.group(1)), "end": int(m.group(2)), "pkgs": [], "agg": {}}
        intervals.append(cur); continue
    m = ST_RE.search(line)
    if m and cur is not None:
        cur["pkgs"].append((m.group(1), int(m.group(2)), int(m.group(4)))); continue
    m = re.search(r"screen-interactive: (\d+)x for (\d+)", line)
    if m and cur is not None:
        cur["agg"] = {"screen": int(m.group(2)), "x": int(m.group(1))}

daily = [iv for iv in intervals if len(iv["pkgs"]) > 50]
assert len(daily) == 9

today_pkgs = []; today_screen_ms = 0
lines = open("/root/workspace/usagestats.txt", errors="replace").read().splitlines()
in_daily = False
for line in lines:
    if line.strip() == "In-memory daily stats": in_daily = True; continue
    if in_daily:
        if line.strip().startswith("In-memory weekly"): break
        m = ST_HUMAN_RE.search(line)
        if m:
            today_pkgs.append((m.group(1), dur_ms(m.group(2)), int(m.group(3))))
        m = re.search(r'screen-interactive: (\d+)x for "([\d:]+)"', line)
        if m: today_screen_ms = dur_ms(m.group(2))
for line in lines:
    m = EV_HUMAN_RE.search(line)
    if m:
        ts = int(datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S").replace(tzinfo=TZ).timestamp())
        events.add((ts, m.group(2), m.group(3)))
events = sorted(events)

def day_key(ts): return datetime.fromtimestamp(ts, TZ).strftime("%m-%d")

# ---- keyguard state intervals (SHOWN->HIDDEN pairs); assume hidden initially
kg = []   # (start,end) shown
last_shown = None
for ts, t, p in events:
    if t == "KEYGUARD_SHOWN" and last_shown is None: last_shown = ts
    elif t == "KEYGUARD_HIDDEN" and last_shown is not None:
        kg.append((last_shown, ts)); last_shown = None

def sub_kg(a, b):
    """duration of [a,b] (seconds) NOT covered by keyguard-shown"""
    res = b - a
    for s, e in kg:
        ov = min(b, e) - max(a, s)
        if ov > 0: res -= ov
    return max(res, 0)

# ---- screen intervals
screen_intervals = []
last_on = None
for ts, t, p in events:
    if t == "SCREEN_INTERACTIVE" and last_on is None: last_on = ts
    elif t == "SCREEN_NON_INTERACTIVE" and last_on is not None:
        screen_intervals.append((last_on, ts)); last_on = None
if last_on is not None:
    screen_intervals.append((last_on, events[-1][0]))

def split_by_day(a, b):
    d = datetime.fromtimestamp(a, TZ); cur = a
    while cur < b:
        nxt = min(b, int((d.replace(hour=0, minute=0, second=0, microsecond=0)+timedelta(days=1)).timestamp()))
        yield day_key(cur), cur, nxt
        cur = nxt; d = d + timedelta(days=1)

screen_day = {}; screen_unlock_day = {}; hour_matrix = {}
for a, b in screen_intervals:
    for dk, s, e in split_by_day(a, b):
        screen_day[dk] = screen_day.get(dk, 0) + (e-s)/60
        screen_unlock_day[dk] = screen_unlock_day.get(dk, 0) + sub_kg(s, e)/60
        cur = s
        while cur < e:
            h = datetime.fromtimestamp(cur, TZ).hour
            nxt = min(e, int((datetime.fromtimestamp(cur, TZ).replace(minute=0, second=0, microsecond=0)+timedelta(hours=1)).timestamp()))
            hour_matrix.setdefault(dk, {})[h] = hour_matrix.setdefault(dk, {}).get(h, 0) + (nxt-cur)/60
            cur = nxt

unlock_day = {}; launch_day = {}
for ts, t, p in events:
    dk = day_key(ts)
    if t == "KEYGUARD_HIDDEN": unlock_day[dk] = unlock_day.get(dk, 0) + 1
    if t == "ACTIVITY_RESUMED": launch_day[dk] = launch_day.get(dk, 0) + 1

NAMES = {
 "com.bbk.launcher2":"系统桌面","com.tencent.mm":"微信","tv.danmaku.bili":"哔哩哔哩",
 "com.example.piliplus":"PiliPlus","io.github.ccch1mneyyy.dsh_app":"DSH Go",
 "com.ss.android.ugc.aweme":"抖音","com.twitter.android":"X","ai.qwenlm.chat.android":"Qwen Studio",
 "com.openai.chatgpt":"ChatGPT","com.larus.nova":"豆包","com.autonavi.minimap":"高德地图",
 "com.xiaomi.smarthome":"米家","com.hypergryph.arknights":"明日方舟","org.telegram.messenger":"Telegram",
 "app.fushi.reader":"腐次元","com.vivo.gallery":"相册","com.tencent.mobileqq":"QQ",
 "com.android.incallui":"电话","com.max.xiaoheihe":"小黑盒","com.microsoft.emmx":"Edge",
 "com.android.camera":"相机","com.tencent.workbuddy.app":"WorkBuddy","com.deepseek.chat":"DeepSeek",
 "com.taobao.taobao":"淘宝","com.xingin.xhs":"小红书","com.android.mms":"信息",
 "com.android.settings":"设置","moe.shizuku.privileged.api":"Shizuku","ai.x.grok":"Grok",
 "com.google.android.youtube":"YouTube","com.coolapk.market":"酷安","com.android.filemanager":"文件管理",
 "com.vivo.healthservice":"健康使用设备","com.android.browser":"浏览器","com.vivo.browser":"vivo浏览器",
 "com.jingdong.app.mall":"京东","com.eg.android.AlipayGipay":"支付宝","com.tencent.qqpimsecure":"手机管家",
 "com.sankuai.meituan":"美团","com.netease.cloudmusic":"网易云音乐","com.vivo.familycare.local":"vivo家人守护",
 "com.manmanbuy.bijia":"慢慢买","com.sinovatech.unicom.ui":"中国联通","com.microsoft.bing":"微软必应",
 "com.tencent.wework":"企业微信","com.kugou.android":"酷狗音乐","com.google.android.googlequicksearchbox":"Google",
 "com.aliyun.tongyi":"通义","com.github.metacubex.clash.meta":"Clash Meta","app.komikku":"Komikku",
 "com.vivo.weather":"天气","com.taobao.idlefish":"闲鱼","com.smile.gifmaker":"快手",
 "com.zhihu.android":"知乎","com.sina.weibo":"微博","com.alibaba.android.rimet":"钉钉",
 "com.xunmeng.pinduoduo":"拼多多","com.MobileTicket":"铁路12306","com.baidu.BaiduMap":"百度地图",
 "ctrip.android.view":"携程","com.Qunar":"去哪儿","com.ss.android.article.news":"今日头条",
 "com.tencent.qqmusic":"QQ音乐","com.yek.android.unipay":"云闪付","com.unionpay":"云闪付",
 "com.quark.browser":"夸克","com.android.vending":"应用商店","com.vivo.appstore":"应用商店",
}
def name(pkg): return NAMES.get(pkg, pkg)

def pk_list(pkl, top=8):
    out = []
    for pkg, ms, launches in sorted(pkl, key=lambda x: -x[1])[:top]:
        if ms <= 0: break
        out.append({"name": name(pkg), "pkg": pkg, "min": round(ms/60000, 1), "launches": launches})
    return out

UI = {"11-03":703,"11-04":492,"11-05":400,"11-06":549,"11-07":325,"11-08":288}
days = []
for iv in daily:
    dk = datetime.fromtimestamp(iv["end"]/1000 - 60, TZ).strftime("%m-%d")
    days.append({"date": dk, "screen": round(screen_day.get(dk, 0), 1),
                 "screen_unlock": round(screen_unlock_day.get(dk, 0), 1),
                 "sys": round(iv["agg"].get("screen", 0)/60000, 1), "ui": UI.get(dk),
                 "unlocks": unlock_day.get(dk, 0), "launches": launch_day.get(dk, 0),
                 "top_apps": pk_list(iv["pkgs"])})
days.append({"date": "11-09", "screen": round(screen_day.get("11-09", 0), 1),
             "screen_unlock": round(screen_unlock_day.get("11-09", 0), 1),
             "sys": round(today_screen_ms/60000, 1), "ui": 323,
             "unlocks": unlock_day.get("11-09", 0), "launches": launch_day.get("11-09", 0),
             "top_apps": pk_list(today_pkgs)})

agg = {}
for iv in daily:
    dk = datetime.fromtimestamp(iv["end"]/1000 - 60, TZ).strftime("%m-%d")
    if dk >= "11-03":
        for pkg, ms, launches in iv["pkgs"]:
            a = agg.setdefault(pkg, [0, 0]); a[0] += ms; a[1] += launches
for pkg, ms, launches in today_pkgs:
    a = agg.setdefault(pkg, [0, 0]); a[0] += ms; a[1] += launches
agg_list = [{"name": name(p), "pkg": p, "min": round(v[0]/60000, 1), "launches": v[1]}
            for p, v in sorted(agg.items(), key=lambda kv: -kv[1][0]) if v[0] > 0]

out = {"days": days,
       "hour_matrix": {dk: {str(h): round(m, 1) for h, m in sorted(hm.items())} for dk, hm in sorted(hour_matrix.items())},
       "app_7d": agg_list}
json.dump(out, open("/root/workspace/report_data.json", "w"), ensure_ascii=False, indent=1)

print("date  screen  unlocked  sysagg  ui  unlocks  launches")
for d in days:
    print(f"{d['date']}  {d['screen']:7.1f} {d['screen_unlock']:8.1f} {d['sys']:6.1f} {str(d['ui']):>5} {d['unlocks']:7} {d['launches']:8}")
print("== 7d apps top 25 ==")
for a in agg_list[:25]:
    print(f"{a['min']:7.1f} {a['name']:12} launches={a['launches']}")

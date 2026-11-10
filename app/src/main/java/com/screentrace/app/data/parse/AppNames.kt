package com.screentrace.app.data.parse

import com.screentrace.app.contract.AppCategory

/**
 * §3-8：展示名内置映射表（`parse_usage3.py` 的 NAMES 表整段搬入，§3-7 明示可搬）。
 * 展示名优先 `PackageManager.getApplicationLabel`，失败回退本表，再回退包名。
 */
object AppNames {

    val NAMES: Map<String, String> = mapOf(
        "com.bbk.launcher2" to "系统桌面",
        "com.tencent.mm" to "微信",
        "tv.danmaku.bili" to "哔哩哔哩",
        "com.example.piliplus" to "PiliPlus",
        "io.github.ccch1mneyyy.dsh_app" to "DSH Go",
        "com.ss.android.ugc.aweme" to "抖音",
        "com.twitter.android" to "X",
        "ai.qwenlm.chat.android" to "Qwen Studio",
        "com.openai.chatgpt" to "ChatGPT",
        "com.larus.nova" to "豆包",
        "com.autonavi.minimap" to "高德地图",
        "com.xiaomi.smarthome" to "米家",
        "com.hypergryph.arknights" to "明日方舟",
        "org.telegram.messenger" to "Telegram",
        "app.fushi.reader" to "腐次元",
        "com.vivo.gallery" to "相册",
        "com.tencent.mobileqq" to "QQ",
        "com.android.incallui" to "电话",
        "com.max.xiaoheihe" to "小黑盒",
        "com.microsoft.emmx" to "Edge",
        "com.android.camera" to "相机",
        "com.tencent.workbuddy.app" to "WorkBuddy",
        "com.deepseek.chat" to "DeepSeek",
        "com.taobao.taobao" to "淘宝",
        "com.xingin.xhs" to "小红书",
        "com.android.mms" to "信息",
        "com.android.settings" to "设置",
        "moe.shizuku.privileged.api" to "Shizuku",
        "ai.x.grok" to "Grok",
        "com.google.android.youtube" to "YouTube",
        "com.coolapk.market" to "酷安",
        "com.android.filemanager" to "文件管理",
        "com.vivo.healthservice" to "健康使用设备",
        "com.android.browser" to "浏览器",
        "com.vivo.browser" to "vivo浏览器",
        "com.jingdong.app.mall" to "京东",
        "com.eg.android.AlipayGipay" to "支付宝",
        "com.tencent.qqpimsecure" to "手机管家",
        "com.sankuai.meituan" to "美团",
        "com.netease.cloudmusic" to "网易云音乐",
        "com.vivo.familycare.local" to "vivo家人守护",
        "com.manmanbuy.bijia" to "慢慢买",
        "com.sinovatech.unicom.ui" to "中国联通",
        "com.microsoft.bing" to "微软必应",
        "com.tencent.wework" to "企业微信",
        "com.kugou.android" to "酷狗音乐",
        "com.google.android.googlequicksearchbox" to "Google",
        "com.aliyun.tongyi" to "通义",
        "com.github.metacubex.clash.meta" to "Clash Meta",
        "app.komikku" to "Komikku",
        "com.vivo.weather" to "天气",
        "com.taobao.idlefish" to "闲鱼",
        "com.smile.gifmaker" to "快手",
        "com.zhihu.android" to "知乎",
        "com.sina.weibo" to "微博",
        "com.alibaba.android.rimet" to "钉钉",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.MobileTicket" to "铁路12306",
        "com.baidu.BaiduMap" to "百度地图",
        "ctrip.android.view" to "携程",
        "com.Qunar" to "去哪儿",
        "com.ss.android.article.news" to "今日头条",
        "com.tencent.qqmusic" to "QQ音乐",
        "com.yek.android.unipay" to "云闪付",
        "com.unionpay" to "云闪付",
        "com.quark.browser" to "夸克",
        "com.android.vending" to "应用商店",
        "com.vivo.appstore" to "应用商店"
    )

    /** 展示名：内置映射表回退包名（PackageManager 版本由调用方优先）。 */
    fun nameOf(pkg: String): String = NAMES[pkg] ?: pkg

    /**
     * §4 内置分类映射（6a 评审 P1-1）：口径唯一出处 = 对账脚本 `parse_usage_v2.py`
     * 的 CATEGORY_RULES（176 个包），移植后与 `screen_usage_report.json` 的
     * `apps_range[].category` 逐包一致——排行图例不会与对账基线打架。
     * 真实 dump 中仍有 15 个包不在表内（桌面 launcher、米家、X、联通、隐私空间组件等），
     * 按 §4 归 OTHER；这 15 个包的归类属于「用户可改」范畴，不在此处另立口径。
     * 用户修改在 SettingsStore.categoryOverrides（读取层叠加，优先于本表）。
     */
    private val CATEGORIES: Map<String, AppCategory> = mapOf(
        // 视频娱乐
        "com.baidu.youavideo" to AppCategory.VIDEO, "com.google.android.youtube" to AppCategory.VIDEO, "com.kugou.android" to AppCategory.VIDEO,
        "com.netease.cloudmusic" to AppCategory.VIDEO, "com.picacomic.fregata" to AppCategory.VIDEO, "com.smile.gifmaker" to AppCategory.VIDEO,
        "com.ss.android.ugc.aweme" to AppCategory.VIDEO, "com.ss.android.ugc.livelite" to AppCategory.VIDEO, "com.tencent.qqmusic" to AppCategory.VIDEO,
        "com.vivo.defaultPlayer" to AppCategory.VIDEO, "tv.danmaku.bili" to AppCategory.VIDEO,
        // 社交沟通
        "com.alibaba.android.rimet" to AppCategory.SOCIAL, "com.android.contacts" to AppCategory.SOCIAL, "com.android.mms" to AppCategory.SOCIAL,
        "com.coolapk.market" to AppCategory.SOCIAL, "com.hupu.games" to AppCategory.SOCIAL, "com.phoenix.read" to AppCategory.SOCIAL,
        "com.sina.weibo" to AppCategory.SOCIAL, "com.ss.android.article.news" to AppCategory.SOCIAL, "com.ss.android.lark" to AppCategory.SOCIAL,
        "com.taou.maimai" to AppCategory.SOCIAL, "com.tencent.mm" to AppCategory.SOCIAL, "com.tencent.mobileqq" to AppCategory.SOCIAL,
        "com.tencent.wework" to AppCategory.SOCIAL, "com.vivo.gallery" to AppCategory.SOCIAL, "com.xingin.xhs" to AppCategory.SOCIAL,
        "com.zhihu.android" to AppCategory.SOCIAL, "org.telegram.messenger" to AppCategory.SOCIAL,
        // AI 助手
        "ai.qwenlm.chat.android" to AppCategory.AI, "ai.x.grok" to AppCategory.AI, "com.adguard.android" to AppCategory.AI,
        "com.agentsanywhere.app" to AppCategory.AI, "com.aliyun.tongyi" to AppCategory.AI, "com.deepseek.chat" to AppCategory.AI,
        "com.larus.nova" to AppCategory.AI, "com.moonshot.kimichat" to AppCategory.AI, "com.openai.chatgpt" to AppCategory.AI,
        "com.tencent.hunyuan.app.chat" to AppCategory.AI, "io.github.ccch1mneyyy.dsh_app" to AppCategory.AI,
        // 游戏
        "com.hypergryph.arknights" to AppCategory.GAME, "com.max.xiaoheihe" to AppCategory.GAME, "com.miHoYo.Nap" to AppCategory.GAME,
        "com.taptap" to AppCategory.GAME, "com.tencent.gamehelper.smoba" to AppCategory.GAME,
        // 购物
        "cmb.pb" to AppCategory.SHOPPING, "cn.com.cmbc.newmbank" to AppCategory.SHOPPING, "com.MobileTicket" to AppCategory.SHOPPING,
        "com.Qunar" to AppCategory.SHOPPING, "com.achievo.vipshop" to AppCategory.SHOPPING, "com.android.bankabc" to AppCategory.SHOPPING,
        "com.antgroup.leopard.android" to AppCategory.SHOPPING, "com.cebbank.mobile.cemb" to AppCategory.SHOPPING, "com.chinamworld.bocmbci" to AppCategory.SHOPPING,
        "com.dianping.v1" to AppCategory.SHOPPING, "com.ecitic.bank.mobile" to AppCategory.SHOPPING, "com.eg.android.AlipayGipay" to AppCategory.SHOPPING,
        "com.eg.android.AlipayGphone" to AppCategory.SHOPPING, "com.hundsun.stockwinner.paqh" to AppCategory.SHOPPING, "com.icbc" to AppCategory.SHOPPING,
        "com.jingdong.app.mall" to AppCategory.SHOPPING, "com.manmanbuy.bijia" to AppCategory.SHOPPING, "com.meituan.retail.v.android" to AppCategory.SHOPPING,
        "com.sankuai.meituan" to AppCategory.SHOPPING, "com.sdu.didi.psnger" to AppCategory.SHOPPING, "com.shizhuang.duapp" to AppCategory.SHOPPING,
        "com.taobao.idlefish" to AppCategory.SHOPPING, "com.taobao.taobao" to AppCategory.SHOPPING, "com.umetrip.android.msky.app" to AppCategory.SHOPPING,
        "com.vivo.card" to AppCategory.SHOPPING, "com.vivo.wallet" to AppCategory.SHOPPING, "com.xunmeng.pinduoduo" to AppCategory.SHOPPING,
        "ctrip.android.view" to AppCategory.SHOPPING,
        // 工具效率
        "app.fushi.reader" to AppCategory.TOOLS, "app.komikku" to AppCategory.TOOLS, "cn.wenyu.bodian" to AppCategory.TOOLS,
        "com.One.WoodenLetter" to AppCategory.TOOLS, "com.android.BBKClock" to AppCategory.TOOLS, "com.android.bbkcalculator" to AppCategory.TOOLS,
        "com.android.browser" to AppCategory.TOOLS, "com.android.camera" to AppCategory.TOOLS, "com.android.chrome" to AppCategory.TOOLS,
        "com.android.documentsui" to AppCategory.TOOLS, "com.android.filemanager" to AppCategory.TOOLS, "com.android.nfc" to AppCategory.TOOLS,
        "com.android.packageinstaller" to AppCategory.TOOLS, "com.android.permissioncontroller" to AppCategory.TOOLS, "com.android.phone" to AppCategory.TOOLS,
        "com.android.photopicker" to AppCategory.TOOLS, "com.android.settings" to AppCategory.TOOLS, "com.android.systemui" to AppCategory.TOOLS,
        "com.android.vending" to AppCategory.TOOLS, "com.android.vivo.tws.vivotws" to AppCategory.TOOLS, "com.android.wifi.dialog" to AppCategory.TOOLS,
        "com.antgroup.aijk.android" to AppCategory.TOOLS, "com.autonavi.minimap" to AppCategory.TOOLS, "com.baidu.BaiduMap" to AppCategory.TOOLS,
        "com.bbk.account" to AppCategory.TOOLS, "com.bbk.appstore" to AppCategory.TOOLS, "com.bbk.calendar" to AppCategory.TOOLS,
        "com.bbk.iqoo.logsystem" to AppCategory.TOOLS, "com.bbk.theme" to AppCategory.TOOLS, "com.bbk.updater" to AppCategory.TOOLS,
        "com.cainiao.wireless" to AppCategory.TOOLS, "com.campmobile.snowcamera" to AppCategory.TOOLS, "com.chif.weather" to AppCategory.TOOLS,
        "com.duowan.kiwi" to AppCategory.TOOLS, "com.eno.xyzq.page" to AppCategory.TOOLS, "com.example.piliplus" to AppCategory.TOOLS,
        "com.fido.client" to AppCategory.TOOLS, "com.google.android.apps.bard" to AppCategory.TOOLS, "com.google.android.gms" to AppCategory.TOOLS,
        "com.google.android.googlequicksearchbox" to AppCategory.TOOLS, "com.google.android.webview" to AppCategory.TOOLS, "com.greenpoint.android.mc10086.activity" to AppCategory.TOOLS,
        "com.huacheng.baiyunuser" to AppCategory.TOOLS, "com.huawei.health" to AppCategory.TOOLS, "com.iqoo.feedback" to AppCategory.TOOLS,
        "com.iqoo.powersaving" to AppCategory.TOOLS, "com.iqoo.secure" to AppCategory.TOOLS, "com.kugou.android.elder" to AppCategory.TOOLS,
        "com.kylin.read" to AppCategory.TOOLS, "com.masseffect.suki" to AppCategory.TOOLS, "com.mfcloudcalculate.networkdisk" to AppCategory.TOOLS,
        "com.microsoft.bing" to AppCategory.TOOLS, "com.microsoft.emmx" to AppCategory.TOOLS, "com.predidit.kazumi" to AppCategory.TOOLS,
        "com.quark.browser" to AppCategory.TOOLS, "com.snda.wifilocating" to AppCategory.TOOLS, "com.sohu.inputmethod.sogou.vivo" to AppCategory.TOOLS,
        "com.tencent.workbuddy.app" to AppCategory.TOOLS, "com.unionpay.tsmservice" to AppCategory.TOOLS, "com.usmile.health" to AppCategory.TOOLS,
        "com.vivo.Tips" to AppCategory.TOOLS, "com.vivo.abe" to AppCategory.TOOLS, "com.vivo.adservices" to AppCategory.TOOLS,
        "com.vivo.appfilter" to AppCategory.TOOLS, "com.vivo.appstore" to AppCategory.TOOLS, "com.vivo.browser" to AppCategory.TOOLS,
        "com.vivo.browser.novel.widget" to AppCategory.TOOLS, "com.vivo.cipherchain" to AppCategory.TOOLS, "com.vivo.doubleinstance" to AppCategory.TOOLS,
        "com.vivo.epm" to AppCategory.TOOLS, "com.vivo.familycare.local" to AppCategory.TOOLS, "com.vivo.fuelsummary" to AppCategory.TOOLS,
        "com.vivo.healthservice" to AppCategory.TOOLS, "com.vivo.healthwidget" to AppCategory.TOOLS, "com.vivo.hiboard" to AppCategory.TOOLS,
        "com.vivo.moodcube" to AppCategory.TOOLS, "com.vivo.pcsuite" to AppCategory.TOOLS, "com.vivo.pem" to AppCategory.TOOLS,
        "com.vivo.permissionmanager" to AppCategory.TOOLS, "com.vivo.pie" to AppCategory.TOOLS, "com.vivo.privacylauncher" to AppCategory.TOOLS,
        "com.vivo.recents" to AppCategory.TOOLS, "com.vivo.safecenter" to AppCategory.TOOLS, "com.vivo.smartmultiwindow" to AppCategory.TOOLS,
        "com.vivo.smartoffice" to AppCategory.TOOLS, "com.vivo.smartshot" to AppCategory.TOOLS, "com.vivo.sos" to AppCategory.TOOLS,
        "com.vivo.space" to AppCategory.TOOLS, "com.vivo.sps" to AppCategory.TOOLS, "com.vivo.systemuiplugin" to AppCategory.TOOLS,
        "com.vivo.telephonyapp" to AppCategory.TOOLS, "com.vivo.vivo3rdalgoservice" to AppCategory.TOOLS, "com.vivo.vms" to AppCategory.TOOLS,
        "com.vivo.vtouch" to AppCategory.TOOLS, "com.vivo.weather" to AppCategory.TOOLS, "com.vivo.weather.provider" to AppCategory.TOOLS,
        "com.vivo.widgetweather" to AppCategory.TOOLS, "com.vivo.xspace" to AppCategory.TOOLS, "com.ylmf.androidclient" to AppCategory.TOOLS,
        "com.yuzhoucloud" to AppCategory.TOOLS, "com.zy.flt_yishang" to AppCategory.TOOLS, "li.songe.gkd" to AppCategory.TOOLS,
        "moe.shizuku.privileged.api" to AppCategory.TOOLS, "nutstore.android" to AppCategory.TOOLS,
        // 其余 → OTHER
    )

    fun categoryOf(pkg: String): AppCategory = CATEGORIES[pkg] ?: AppCategory.OTHER
}

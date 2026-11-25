package com.screentrace.app.ui

/**
 * CR-30 阶段 D（审计项 #7）：全局轻量反馈分级与单槽优先级替换策略。
 *
 * - 同一时刻只展示一条最紧急反馈，避免多条横幅在顶部/底部叠放遮挡内容；
 * - 优先级：`PULL_ERROR (ERROR) > PERSISTENT (INFO) > TRANSIENT (SUCCESS)`；
 * - 保留 CR-17 铁律：不使用系统 `android.widget.Toast`，成功短提示置于底部悬浮导航上方安全区，
 *   需行动/错误提示保留于顶部覆盖层。
 */
enum class FeedbackSeverity { ERROR, INFO, SUCCESS }

enum class FeedbackChannel { PULL_ERROR, PERSISTENT, TRANSIENT }

data class ActiveFeedback(
    val channel: FeedbackChannel,
    val text: String,
    val severity: FeedbackSeverity,
    val actionText: String? = null
)

fun resolveActiveFeedback(
    pullError: String?,
    persistentNotice: String?,
    transientNotice: TransientNotice?
): ActiveFeedback? = when {
    !pullError.isNullOrBlank() -> ActiveFeedback(
        channel = FeedbackChannel.PULL_ERROR,
        text = pullError,
        severity = FeedbackSeverity.ERROR,
        actionText = "重试"
    )
    !persistentNotice.isNullOrBlank() -> ActiveFeedback(
        channel = FeedbackChannel.PERSISTENT,
        text = persistentNotice,
        severity = FeedbackSeverity.INFO
    )
    transientNotice != null && transientNotice.text.isNotBlank() -> ActiveFeedback(
        channel = FeedbackChannel.TRANSIENT,
        text = transientNotice.text,
        severity = FeedbackSeverity.SUCCESS
    )
    else -> null
}

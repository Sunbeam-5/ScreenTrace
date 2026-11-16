package com.screentrace.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.PrimaryButton
import com.screentrace.app.uikit.SecondaryButton

/**
 * 设备行上的两枚**真实动作**按钮 + 合并确认弹层。
 *
 * 顶栏「切换设备」面板与云同步页「设备」卡片是**两处入口**，但行为必须一致——
 * 所以动作按钮与确认弹层都抽到这一份，两边共用（§5.10「共用同一套设备列表数据与
 * 同一套切换 / 合并逻辑」）。
 *
 * 切换不需要二次确认：它只是「换一个角度看数据」，可逆、不破坏任何数据；
 * 合并会往本机库里写数据，必须二次确认（§5.2）。
 *
 * @param stacked 窄容器（顶栏浮层）里纵向排两个按钮——并排时「切回本机」这类四字
 *        文案会被挤到折行（真机实测），纵向一行一个才不折。
 */
@Composable
fun DeviceActionRow(
    switchText: String,
    mergeEnabled: Boolean,
    switchEnabled: Boolean,
    onSwitch: () -> Unit,
    onMerge: () -> Unit,
    stacked: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (stacked) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecondaryButton(
                text = switchText,
                onClick = onSwitch,
                enabled = switchEnabled,
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryButton(
                text = "合并到本机",
                onClick = onMerge,
                enabled = mergeEnabled,
                modifier = Modifier.fillMaxWidth()
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecondaryButton(
                text = switchText,
                onClick = onSwitch,
                enabled = switchEnabled,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "合并到本机",
                onClick = onMerge,
                enabled = mergeEnabled,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * CR-21 ②：合并到本机的**二次确认**弹层。
 *
 * 文案必须把四件事讲清楚（§5.10）：口径是累加、旧设备数据单独保留、本机将同时含有
 * 两台设备的数据、重复合并只并入新日期、合并后会自动上传一次。
 * 讲不清就等于让用户盲操作——合并是唯一会改动本机数据的动作。
 *
 * 强调用「」引号而不是 markdown 星号：Compose 的 Text 不解析 markdown，
 * 写了 `**累加**` 用户看到的就是一串星号（真机实测）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeConfirmSheet(
    deviceLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val palette = LocalFreshPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // CR-20 ③：弹层底色不透明（壁纸模式下卡片会变透明，弹层跟着透明会叠字）
        containerColor = palette.panelSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                // 底部手势条 / 导航键区自己让开，否则「取消」会被压住（与 DateSheet 同口径）
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "合并到本机", style = FreshType.sectionTitle, color = palette.onSurface)
            Text(
                text = "把「$deviceLabel」的最新备份并入本机数据：",
                style = FreshType.body,
                color = palette.onSurface
            )
            Text(
                text = "· 按「累加」口径合并：两台设备同一天的使用量相加，不是取较大值\n" +
                    "· 该设备自己的数据「单独保留」，不会被动到；本机数据里会同时含有两台设备\n" +
                    "· 重复合并只并入「新日期」，同一天不会翻倍\n" +
                    "· 合并完成后会「自动上传一次」，让云端备份立刻含旧数据",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            // CR-21 补记：累加口径的边界——来源若是**同一台机器自己导出的旧文件**
            // （升级前根目录旧备份常见），重叠日期会按累加口径翻倍。必须写明。
            Text(
                text = "注意：若这份备份来自「同一台机器自己导出的旧文件」，" +
                    "重叠的日期会按累加口径翻倍——请确认来源是另一台设备。",
                style = FreshType.label,
                color = palette.onSurfaceVariant
            )
            PrimaryButton(
                text = "确认合并",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            )
            SecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

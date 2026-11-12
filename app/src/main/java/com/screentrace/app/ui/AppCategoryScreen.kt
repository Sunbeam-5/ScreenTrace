package com.screentrace.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.screentrace.app.contract.AppCategory
import com.screentrace.app.contract.AppCategoryRow
import com.screentrace.app.theme.FreshType
import com.screentrace.app.theme.LocalFreshPalette
import com.screentrace.app.uikit.AppIcon
import com.screentrace.app.uikit.EmptyState
import com.screentrace.app.uikit.ErrorRetry
import com.screentrace.app.uikit.LoadingSkeleton

/**
 * §5.8「权限与数据」组：应用分类管理（二级页）。
 *
 * 分类是**用户意图**而不是统计结果：同一个包在不同统计规则下可以算社交也可以算工具，
 * 所以这里改的覆盖值会在读取层叠到排行/详情/导出上，并随备份一起带走（§4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppCategoryScreen(
    state: LoadState<List<AppCategoryRow>>,
    onSetCategory: (String, AppCategory) -> Unit,
    onRetry: () -> Unit
) {
    val palette = LocalFreshPalette.current
    var editing by remember { mutableStateOf<AppCategoryRow?>(null) }

    when {
        state.data != null -> {
            val rows = state.data
            if (rows.isEmpty()) {
                EmptyState(
                    title = "还没有可管理的应用",
                    description = "拉取一次数据后，出现过的应用会列在这里，可以按包名修改分类。"
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "共 ${rows.size} 个应用。修改后排行、详情与导出会立刻按新分类显示。",
                        style = FreshType.label,
                        color = palette.onSurfaceVariant
                    )
                    rows.forEach { row ->
                        CategoryRow(row = row, onClick = { editing = row })
                    }
                }
            }
        }
        state.error != null -> ErrorRetry(message = state.error, onRetry = onRetry)
        else -> LoadingSkeleton()
    }

    val target = editing
    if (target != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = palette.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = target.name, style = FreshType.sectionTitle, color = palette.onSurface)
                Text(
                    text = target.pkg,
                    style = FreshType.label,
                    color = palette.onSurfaceVariant
                )
                AppCategory.entries.forEach { category ->
                    CategoryOption(
                        category = category,
                        selected = category == target.category,
                        onClick = {
                            onSetCategory(target.pkg, category)
                            editing = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(row: AppCategoryRow, onClick: () -> Unit) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.surface)
            .semantics {
                role = Role.Button
                contentDescription = "${row.name}，当前分类 ${row.category.label}，点击修改"
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(pkg = row.pkg, name = row.name, size = 34.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = FreshType.body,
                color = palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = row.pkg,
                style = FreshType.label,
                color = palette.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = row.category.label,
            style = FreshType.label,
            color = palette.primary
        )
    }
}

@Composable
private fun CategoryOption(category: AppCategory, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalFreshPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) palette.primaryContainer else palette.track)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(if (selected) 10.dp else 6.dp)
                .clip(CircleShape)
                .background(if (selected) palette.primary else palette.onSurfaceVariant)
        )
        Text(
            text = category.label,
            style = FreshType.body,
            color = if (selected) palette.onPrimaryContainer else palette.onSurfaceVariant
        )
    }
}

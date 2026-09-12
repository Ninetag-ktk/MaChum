package com.ninetag.machum.theme

import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

internal expect val platformUsesTouchUi: Boolean

/**
 * 글쓰기 캔버스 바깥의 앱 chrome 치수입니다. Android는 터치, Desktop은 조밀한 탐색에 맞춥니다.
 * TopAppBar 콘텐츠 높이와 플랫폼 상태바 여백은 별도로 더합니다.
 * Markdown 본문 크기와 행간은 이 값으로 축소하지 않습니다.
 */
internal object WorkspaceUiMetrics {
    val topBarHeight = if (platformUsesTouchUi) 56.dp else 48.dp
    val drawerMaxWidth = if (platformUsesTouchUi) 320.dp else 304.dp
    val drawerHeaderHeight = topBarHeight
    val navigationRowHeight = 48.dp
    val iconSize = if (platformUsesTouchUi) 24.dp else 20.dp
    val titleFontSize = if (platformUsesTouchUi) 16.sp else 14.sp
    val titleLineHeight = if (platformUsesTouchUi) 24.sp else 20.sp
    val bodyTextStyle: TextStyle
        @Composable get() = if (platformUsesTouchUi) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
    val secondaryTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium
    val labelTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.labelLarge
    val compactHorizontalPadding = 12.dp

    // 커밋 이력은 navigation dependency 없이 실제 가용 폭으로 master-detail 전환을 결정한다.
    val commitHistoryWideMinWidth = 840.dp
    val commitHistoryListWidth = 320.dp

    val hierarchyToolbarHeight = if (platformUsesTouchUi) 48.dp else 40.dp
    val hierarchyFolderRowHeight = if (platformUsesTouchUi) 48.dp else 36.dp
    val hierarchyActionSize = if (platformUsesTouchUi) 48.dp else 36.dp
    val inlineChipActionSize = if (platformUsesTouchUi) 48.dp else 28.dp
    val hierarchyIconSize = if (platformUsesTouchUi) 20.dp else 16.dp
    val hierarchySecondaryIconSize = if (platformUsesTouchUi) 20.dp else 14.dp
    val hierarchyIndentStep = 20.dp
    val hierarchyGuideOffset = if (platformUsesTouchUi) 14.dp else hierarchyActionSize / 2
    val hierarchyGuideStrokeWidth = 1.dp
}

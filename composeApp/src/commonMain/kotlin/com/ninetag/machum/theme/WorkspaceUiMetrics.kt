package com.ninetag.machum.theme

import androidx.compose.ui.unit.dp

/**
 * 글쓰기 캔버스 바깥의 앱 chrome에만 적용하는 조밀한 치수입니다.
 * Markdown 본문 크기와 행간은 이 값으로 축소하지 않습니다.
 */
internal object WorkspaceUiMetrics {
    val topBarHeight = 48.dp
    val drawerMaxWidth = 304.dp
    val drawerHeaderHeight = 48.dp
    val navigationRowHeight = 48.dp
    val iconSize = 20.dp
    val compactHorizontalPadding = 12.dp

    val hierarchyToolbarHeight = 40.dp
    val hierarchyFolderRowHeight = 36.dp
    val hierarchyFileRowHeight = 30.dp
    val hierarchyStageRowHeight = 28.dp
    val hierarchyActionSize = 28.dp
    val hierarchyIconSize = 16.dp
    val hierarchySecondaryIconSize = 14.dp
    val hierarchyIndentStep = 20.dp
    val hierarchyGuideOffset = 14.dp
    val hierarchyGuideStrokeWidth = 1.dp
}

package com.ninetag.machum.external

import androidx.compose.ui.platform.ClipEntry

/**
 * 평문 문자열을 플랫폼별 [ClipEntry] 로 변환한다.
 *
 * 신 [androidx.compose.ui.platform.Clipboard] API 는 저수준 `setClipEntry(ClipEntry)` 만 노출하고,
 * 구 `ClipboardManager.setText(AnnotatedString)` 같은 공통 텍스트 편의 메서드가 없다. Compose 내부에
 * `AnnotatedString.toClipEntry()` 헬퍼가 있지만 `internal` 이라 접근 불가하므로 직접 expect/actual 로 만든다.
 *
 * - Android: `ClipData.newPlainText`
 * - Desktop(JVM): AWT `StringSelection`
 */
expect fun clipEntryOf(text: String): ClipEntry

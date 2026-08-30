package com.ninetag.machum.screen.projectScreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ninetag.machum.external.ProjectIndexState

@Composable
fun ProjectIndexingScreen(
    state: ProjectIndexState.Indexing,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("프로젝트 인덱싱", style = MaterialTheme.typography.headlineSmall)
                Text(
                    state.projectName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val progress = if (state.total == 0) 1f else state.processed.toFloat() / state.total
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                )
                Text(
                    "${state.processed} / ${state.total} 파일",
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "필요한 frontmatter를 적용하고 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

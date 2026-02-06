package com.ninetag.machum

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.screen.TestScreen
import com.sun.tools.javac.util.Context
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.resolve
import org.jetbrains.compose.resources.painterResource

import machum.composeapp.generated.resources.Res
import machum.composeapp.generated.resources.compose_multiplatform
import okio.Path.Companion.toPath

@Composable
@Preview
fun App() {
    val dataStore: DataStore<Preferences> = remember {
        PreferenceDataStoreFactory.createWithPath {
            FileKit.databasesDir.resolve("app.preferences_pb").path.toPath()
        }
    }
    val fileManager = remember { FileManager(dataStore) }
    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
//            Button(onClick = { showContent = !showContent }) {
//                Text("Click me!")
//            }
//            AnimatedVisibility(showContent) {
//                val greeting = remember { Greeting().greet() }
//                Column(
//                    modifier = Modifier.fillMaxWidth(),
//                    horizontalAlignment = Alignment.CenterHorizontally,
//                ) {
//                    Image(painterResource(Res.drawable.compose_multiplatform), null)
//                    Text("Compose: $greeting")
//                }
//            }
            TestScreen(fileManager)
        }
    }
}
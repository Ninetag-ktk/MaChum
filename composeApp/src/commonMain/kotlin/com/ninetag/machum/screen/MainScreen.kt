package com.ninetag.machum.screen

import androidx.compose.runtime.Composable
import com.ninetag.machum.external.FileManager
import org.koin.compose.koinInject

@Composable
fun MainScreen() {
    val fileManager = koinInject<FileManager>()
}
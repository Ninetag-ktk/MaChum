package com.ninetag.machum.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.ninetag.machum.external.FileManager
import com.ninetag.machum.markdown.parser.MarkdownParser
import com.ninetag.machum.markdown.parser.MarkdownParserImpl
import com.ninetag.machum.screen.mainComposition.MainViewModel
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.databasesDir
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.resolve
import okio.Path.Companion.toPath
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val commonModule = module {
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.createWithPath {
            FileKit.databasesDir.resolve("app.preferences_pb").path.toPath()
        }
    }
    single { FileManager(dataStore = get()) }
    single<MarkdownParser> { MarkdownParserImpl() }
    viewModel { MainViewModel(fileManager = get()) }
}
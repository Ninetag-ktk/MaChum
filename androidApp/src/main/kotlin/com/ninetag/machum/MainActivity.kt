package com.ninetag.machum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.ninetag.machum.di.commonModule
import com.ninetag.machum.screen.mainComposition.WorkspaceSaveCoordinator
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.android.inject
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {
    private val workspaceSaveCoordinator: WorkspaceSaveCoordinator by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        startKoin {
            androidContext(this@MainActivity.application)
            modules(commonModule)
        }

        FileKit.init(this)

        setContent {
            App()
        }
    }

    override fun onStop() {
        workspaceSaveCoordinator.flushInBackground()
        super.onStop()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}

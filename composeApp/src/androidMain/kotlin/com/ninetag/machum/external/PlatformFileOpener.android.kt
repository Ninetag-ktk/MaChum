package com.ninetag.machum.external

import android.content.Context
import android.content.Intent
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private object PlatformFileOpenContext : KoinComponent {
    val value: Context by inject()
}

internal actual suspend fun openPlatformFile(file: PlatformFile): Boolean = withContext(Dispatchers.Main) {
    runCatching {
        val context = PlatformFileOpenContext.value
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(file.toAndroidUri("com.ninetag.machum.fileprovider"), "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}

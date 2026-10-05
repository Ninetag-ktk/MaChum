package com.ninetag.machum.external

import io.github.vinceglb.filekit.PlatformFile

internal expect suspend fun openPlatformFile(file: PlatformFile): Boolean

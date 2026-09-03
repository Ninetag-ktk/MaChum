package com.ninetag.machum.backup

import java.util.UUID

internal actual fun newProjectBackupId(): String = UUID.randomUUID().toString()

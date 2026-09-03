package com.ninetag.machum.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 같은 앱 process 안의 service/worker가 동일 프로젝트 백업을 동시에 게시하지 않게 한다. */
internal object BackupKeyedMutexRegistry {
    private val registryMutex = Mutex()
    private val mutexes = mutableMapOf<String, Mutex>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        require(key.isNotBlank()) { "backup lock key must not be blank" }
        val mutex = registryMutex.withLock { mutexes.getOrPut(key) { Mutex() } }
        return mutex.withLock { block() }
    }
}

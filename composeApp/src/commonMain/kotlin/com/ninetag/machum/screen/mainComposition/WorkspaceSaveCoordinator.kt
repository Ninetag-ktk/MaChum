package com.ninetag.machum.screen.mainComposition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 활성 ViewModel의 pending save를 Project/Vault 전환과 플랫폼 lifecycle에 연결하는 작은 앱 수명 경계다.
 * 로컬 저장 실패는 후속 전환을 실행하지 않으며, 앱 종료 요청은 호출자가 결과를 보고 결정한다.
 */
class WorkspaceSaveCoordinator {
    private val operationMutex = Mutex()
    private val lifecycleScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    private var flushAction: suspend () -> Unit = {}

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    internal fun register(flushAction: suspend () -> Unit) {
        this.flushAction = flushAction
    }

    suspend fun flushPendingWrites(): Result<Unit> = operationMutex.withLock {
        runCatching { flushAction() }
            .also(::recordFlushResult)
    }

    suspend fun <T> runAfterFlush(action: suspend () -> T): Result<T> = operationMutex.withLock {
        val flushResult = runCatching { flushAction() }
            .also(::recordFlushResult)
        if (flushResult.isFailure) {
            return@withLock Result.failure(flushResult.exceptionOrNull()!!)
        }
        runCatching { action() }
    }

    /** Android onStop처럼 suspend 완료를 기다릴 수 없는 lifecycle에서 best-effort flush를 시작한다. */
    fun flushInBackground() {
        lifecycleScope.launch { flushPendingWrites() }
    }

    fun clearError() {
        _lastErrorMessage.value = null
    }

    private fun recordFlushResult(result: Result<Unit>) {
        _lastErrorMessage.value = result.exceptionOrNull()?.let { error ->
            "현재 문서를 저장하지 못해 작업 전환을 중단했습니다. ${error.message.orEmpty()}".trim()
        }
    }
}

package com.ninetag.machum.screen.mainScreen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    // Koin singleton과 같은 앱 수명이다. onStop 저장은 ViewModel 취소와 독립적으로 완료한다.
    private val lifecycleScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    private class Registration(val flushAction: suspend () -> Unit)
    private val activeRegistration = MutableStateFlow<Registration?>(null)

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    internal fun register(flushAction: suspend () -> Unit): AutoCloseable {
        val registration = Registration(flushAction)
        activeRegistration.value = registration
        return AutoCloseable {
            // 이전 ViewModel의 정리가 새 ViewModel의 등록을 해제해서는 안 된다.
            activeRegistration.compareAndSet(registration, null)
        }
    }

    suspend fun flushPendingWrites(): Result<Unit> {
        val registration = activeRegistration.value
        return operationMutex.withLock { flush(registration) }
    }

    suspend fun <T> runAfterFlush(action: suspend () -> T): Result<T> {
        val registration = activeRegistration.value
        return operationMutex.withLock {
            val flushResult = flush(registration)
            if (flushResult.isFailure) {
                return@withLock Result.failure(flushResult.exceptionOrNull()!!)
            }
            captureFailure { action() }
        }
    }

    /** Android onStop처럼 suspend 완료를 기다릴 수 없는 lifecycle에서 best-effort flush를 시작한다. */
    fun flushInBackground() {
        // launch/mutex 대기 중 등록이 해제되어도 요청 시점의 pending save를 잃지 않는다.
        val registration = activeRegistration.value ?: return
        lifecycleScope.launch {
            operationMutex.withLock { flush(registration) }
        }
    }

    private suspend fun flush(registration: Registration?): Result<Unit> =
        captureFailure {
            registration?.flushAction?.invoke()
            Unit
        }.also(::recordFlushResult)

    fun clearError() {
        _lastErrorMessage.value = null
    }

    /** 자동 저장 실패도 기존 오류 UI로 전달한다. 알림을 닫아도 ViewModel의 pending 내용은 유지된다. */
    internal fun reportAutoSaveFailure(relativePath: String, error: Exception) {
        _lastErrorMessage.value =
            "문서 자동 저장에 실패했습니다: $relativePath. " +
                "입력 내용은 메모리에 보관 중이며, 다음 편집이나 작업 전환 시 다시 저장합니다. " +
                error.message.orEmpty()
    }

    private suspend fun <T> captureFailure(action: suspend () -> T): Result<T> = try {
        Result.success(action())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun recordFlushResult(result: Result<Unit>) {
        _lastErrorMessage.value = result.exceptionOrNull()?.let { error ->
            "현재 문서를 저장하지 못해 작업 전환을 중단했습니다. ${error.message.orEmpty()}".trim()
        }
    }
}

package com.ninetag.machum.screen.mainScreen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * key별 저장 debounce를 독립적으로 관리한다.
 *
 * 같은 key의 새 요청만 이전 요청을 취소하며, 다른 key의 대기 중인 저장은 유지한다.
 * 외부 변경·삭제·rename이 탐지되면 호출자가 [cancel]로 stale write를 막는다.
 * 호출과 내부 상태 접근은 Main(테스트에서는 단일 dispatcher)에서 수행하며,
 * [save]는 필요한 IO context를 직접 선택한다.
 */
internal class DebouncedSaveCoordinator<K, V>(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val onSaveFailure: (K, Exception) -> Unit = { _, _ -> },
    private val save: suspend (K, V) -> Unit,
) {
    private val jobs = mutableMapOf<K, Job>()
    private val pendingValues = mutableMapOf<K, V>()
    private val writeMutex = Mutex()

    fun schedule(key: K, value: V) {
        jobs.remove(key)?.cancel()
        pendingValues[key] = value
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(debounceMillis.milliseconds)
                val runningJob = currentCoroutineContext().job
                writeMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (jobs[key] !== runningJob || pendingValues[key] != value) {
                        return@withLock
                    }
                    save(key, value)
                    if (jobs[key] === runningJob && pendingValues[key] == value) {
                        pendingValues.remove(key)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // 취소된/교체된 write가 늦게 실패해도 현재 문서의 자동 저장 오류로 오보하지 않는다.
                currentCoroutineContext().ensureActive()
                val runningJob = currentCoroutineContext().job
                if (
                    jobs[key] === runningJob &&
                    pendingValues[key] == value
                ) {
                    onSaveFailure(key, error)
                }
            } finally {
                val runningJob = currentCoroutineContext().job
                if (jobs[key] === runningJob) {
                    jobs.remove(key)
                }
            }
        }
        jobs[key] = job
        job.start()
    }

    /**
     * 미시작 예약을 제거하고 아직 저장하지 않은 값을 반환한다.
     * 이미 진행 중인 provider I/O의 완료를 보장하지 않으므로 물리 rename 전에는 [withWritesPaused]를 사용한다.
     */
    fun cancel(key: K): V? {
        jobs.remove(key)?.cancel()
        val pending = pendingValues.remove(key)
        return pending
    }

    fun cancelMissing(validKeys: Set<K>) {
        (jobs.keys + pendingValues.keys)
            .filterNot { it in validKeys }
            .toSet()
            .forEach(::cancel)
    }

    fun cancelAll() {
        val keys = (jobs.keys + pendingValues.keys).toSet()
        keys.forEach(::cancel)
    }

    suspend fun flush(keys: Set<K>) = writeMutex.withLock {
        currentCoroutineContext().ensureActive()
        keys.forEach { key ->
            while (true) {
                // 실제 write는 이 mutex 안에서만 실행되므로 대기 job은 join하지 않고 취소한다.
                // join하면 같은 mutex를 기다리는 job과 교착될 수 있다.
                jobs.remove(key)?.cancel()
                currentCoroutineContext().ensureActive()
                val value = pendingValues[key] ?: break
                save(key, value)
                if (pendingValues[key] == value) {
                    pendingValues.remove(key)
                    break
                }
            }
        }
    }

    /**
     * 진행 중인 실제 write가 끝난 뒤 [action]을 단독 실행한다.
     * [action] 안에서는 [flush], [flushAll], [withWritesPaused]를 다시 호출하면 안 된다.
     */
    suspend fun <R> withWritesPaused(action: suspend () -> R): R = writeMutex.withLock {
        currentCoroutineContext().ensureActive()
        action()
    }

    /** 현재 대기 중인 모든 key를 즉시 저장한다. flush 도중 추가된 최신 값도 남지 않을 때까지 처리한다. */
    suspend fun flushAll() {
        do {
            flush(pendingValues.keys.toSet())
        } while (pendingValues.isNotEmpty())
    }
}

package com.ninetag.machum.screen.mainComposition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * key별 저장 debounce를 독립적으로 관리한다.
 *
 * 같은 key의 새 요청만 이전 요청을 취소하며, 다른 key의 대기 중인 저장은 유지한다.
 * 외부 변경·삭제·rename이 탐지되면 호출자가 [cancel]로 stale write를 막는다.
 */
internal class DebouncedSaveCoordinator<K, V>(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val save: suspend (K, V) -> Unit,
) {
    private val jobs = mutableMapOf<K, Job>()

    fun schedule(key: K, value: V) {
        jobs.remove(key)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(debounceMillis.milliseconds)
                save(key, value)
            } finally {
                val runningJob = currentCoroutineContext().job
                if (jobs[key] === runningJob) jobs.remove(key)
            }
        }
        jobs[key] = job
        job.start()
    }

    fun cancel(key: K) {
        jobs.remove(key)?.cancel()
    }

    fun cancelMissing(validKeys: Set<K>) {
        jobs.keys.filterNot { it in validKeys }.toList().forEach(::cancel)
    }
}

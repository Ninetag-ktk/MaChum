package com.ninetag.machum.screen.mainComposition

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Project/Folder/File 탐색을 요청 순서대로 직렬화하고, 새 요청이 들어오면 이전 요청의 UI 적용을 막는다.
 * 탐색 API는 UI(Main) 스레드에서 호출되므로 generation 증가는 별도 원자 타입 없이 유지한다.
 */
internal class LatestNavigationGate {
    private val mutex = Mutex()
    private var latestGeneration = 0L

    fun newRequest(): Request = Request(++latestGeneration)

    suspend fun run(
        request: Request,
        action: suspend (isLatest: () -> Boolean) -> Unit,
    ) {
        mutex.withLock {
            if (request.generation != latestGeneration) return@withLock
            action { request.generation == latestGeneration }
        }
    }

    @JvmInline
    value class Request internal constructor(internal val generation: Long)
}

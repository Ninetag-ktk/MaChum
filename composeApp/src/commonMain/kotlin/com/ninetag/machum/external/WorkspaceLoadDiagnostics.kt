package com.ninetag.machum.external

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlin.time.TimeMark

internal expect fun workspaceLoadDiagnosticsEnabled(): Boolean

/** Debug-only, path/body-free spans. Timings are inclusive: parent and child must not be summed. */
internal object WorkspaceLoadDiagnostics {
    private val logger = WorkspaceLoadLogger(::workspaceLoadDiagnosticsEnabled, ::println)
    fun event(stage: String, detail: String = "") = logger.event(stage, detail)
    fun begin(stage: String, detail: String = "") = logger.begin(stage, detail)
    suspend fun start(stage: String, detail: String = "") = logger.begin(stage, detail, currentCoroutineContext()[WorkspaceLoadContext])
    suspend fun <T> within(trace: WorkspaceLoadTrace, reason: String = "", block: suspend () -> T): T = logger.within(trace, reason, block)
    suspend fun <T> measure(stage: String, reason: String = "", block: suspend () -> T): T = logger.measure(stage, reason, block)
    suspend fun <T> time(stage: String, parent: WorkspaceLoadTrace? = null, block: suspend (WorkspaceLoadTrace) -> T): T = logger.time(stage, parent, block)
    suspend fun <T> io(kind: WorkspaceLoadIo, block: suspend () -> T): T = logger.io(kind, block)
    suspend fun <T> withLock(mutex: Mutex, stage: String, block: suspend () -> T): T = logger.withLock(mutex, stage, block)
    suspend fun reason(): String = currentCoroutineContext()[WorkspaceLoadContext]?.reason ?: "unspecified"
    suspend fun operationId(): Long? = currentCoroutineContext()[WorkspaceLoadContext]?.trace?.operation?.id
    suspend fun count(kind: WorkspaceLoadIo) { currentCoroutineContext()[WorkspaceLoadContext]?.trace?.operation?.count(kind) }
}

internal enum class WorkspaceLoadIo(val field: String) {
    DIRECTORY("directoryRequests"), MTIME("mtimeRequests"), READ("markdownReads"),
    WRITE("markdownWrites"), CACHE_HIT("cacheHits"), CACHE_MISS("cacheMisses")
}

@OptIn(ExperimentalAtomicApi::class)
internal class WorkspaceLoadLogger(private val enabled: () -> Boolean, private val output: (String) -> Unit) {
    private val sequence = AtomicLong(0L)
    fun event(stage: String, detail: String = "") { if (enabled()) emit("EVENT", stage, detail) }
    fun begin(stage: String, detail: String = "", parent: WorkspaceLoadContext? = null): WorkspaceLoadTrace {
        if (!enabled()) return WorkspaceLoadTrace(this, stage, null, null, null, null)
        val spanId = sequence.addAndFetch(1L)
        val operation = parent?.trace?.operation ?: WorkspaceLoadOperation(spanId)
        val trace = WorkspaceLoadTrace(this, stage, TimeSource.Monotonic.markNow(), operation, spanId, parent?.trace?.spanId)
        emit("START", stage, trace.ids() + detail.takeIf(String::isNotBlank)?.let { "|$it" }.orEmpty())
        return trace
    }
    suspend fun <T> within(trace: WorkspaceLoadTrace, reason: String = "", block: suspend () -> T): T {
        if (trace.operation == null) return block()
        val parent = currentCoroutineContext()[WorkspaceLoadContext]
        return try {
            withContext(WorkspaceLoadContext(trace, reason.ifBlank { parent?.reason ?: "unspecified" })) {
                trace.markMeasured()
                block()
            }
        } catch (error: Throwable) {
            // withContext can fail on cancellation before entering the block.
            trace.fail(error)
            throw error
        }
    }
    suspend fun <T> measure(stage: String, reason: String = "", block: suspend () -> T): T {
        val parent = currentCoroutineContext()[WorkspaceLoadContext]
        val cause = reason.ifBlank { parent?.reason ?: "unspecified" }
        val trace = begin(stage, "reason=$cause", parent)
        return try {
            val result = within(trace, cause, block)
            trace.complete()
            result
        } catch (error: Throwable) { trace.fail(error); throw error }
    }
    /** Times existing work without introducing a Job or a cancellation checkpoint. */
    suspend fun <T> time(stage: String, parent: WorkspaceLoadTrace? = null, block: suspend (WorkspaceLoadTrace) -> T): T {
        val context = parent?.let { WorkspaceLoadContext(it, "unspecified") }
            ?: currentCoroutineContext()[WorkspaceLoadContext]
        val trace = begin(stage, parent = context)
        return try {
            val result = block(trace)
            trace.complete()
            result
        } catch (error: Throwable) { trace.fail(error); throw error }
    }
    suspend fun <T> io(kind: WorkspaceLoadIo, block: suspend () -> T): T {
        val operation = currentCoroutineContext()[WorkspaceLoadContext]?.trace?.operation ?: return block()
        operation.count(kind)
        val start = TimeSource.Monotonic.markNow()
        return try { block() } finally { operation.addTime(kind, start.elapsedNow().inWholeNanoseconds) }
    }
    suspend fun <T> withLock(mutex: Mutex, stage: String, block: suspend () -> T): T {
        val parent = currentCoroutineContext()[WorkspaceLoadContext]
        if (parent == null) {
            mutex.lock()
            return try { block() } finally { mutex.unlock() }
        }
        val trace = begin(stage, parent = parent)
        try { mutex.lock() } catch (error: Throwable) { trace.fail(error); throw error }
        trace.complete()
        return try { block() } finally { mutex.unlock() }
    }
    internal fun emit(status: String, stage: String, detail: String) {
        // A diagnostics sink failure must not change I/O, cancellation, or rollback behavior.
        runCatching { output("MaChumLoad|$status|$stage" + detail.takeIf(String::isNotBlank)?.let { "|${it.replace('\n', ' ')}" }.orEmpty()) }
    }
}

internal class WorkspaceLoadContext(val trace: WorkspaceLoadTrace, val reason: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceLoadContext>
}

@OptIn(ExperimentalAtomicApi::class)
internal class WorkspaceLoadOperation(val id: Long) {
    private val counts = Array(WorkspaceLoadIo.entries.size) { AtomicLong(0L) }
    private val nanos = Array(WorkspaceLoadIo.entries.size) { AtomicLong(0L) }
    fun count(kind: WorkspaceLoadIo) { counts[kind.ordinal].addAndFetch(1L) }
    fun addTime(kind: WorkspaceLoadIo, value: Long) { nanos[kind.ordinal].addAndFetch(value) }
    fun summary(): String = "metricsScope=operation|ioMs=aggregate" + WorkspaceLoadIo.entries.joinToString("") {
        "|${it.field}=${counts[it.ordinal].load()}|${it.field}Ms=${nanos[it.ordinal].load() / 1_000_000L}"
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class WorkspaceLoadTrace internal constructor(
    private val logger: WorkspaceLoadLogger, private val stage: String, private val startedAt: TimeMark?,
    internal val operation: WorkspaceLoadOperation?, internal val spanId: Long?, private val parentSpanId: Long?,
) {
    private val closed = AtomicBoolean(false)
    private val measured = AtomicBoolean(false)
    internal fun markMeasured() { measured.store(true) }
    internal fun ids(): String = "op=${operation?.id}|span=$spanId|parent=${parentSpanId ?: 0}|timing=inclusive"
    fun complete(detail: String = "") = finish("complete", detail)
    fun fail(error: Throwable) = finish("failed", "error=${error::class.simpleName}")
    private fun finish(status: String, detail: String) {
        val started = startedAt ?: return
        if (!closed.compareAndSet(false, true)) return
        logger.emit("EVENT", "$stage.$status", ids() + "|elapsedMs=${started.elapsedNow().inWholeMilliseconds}" +
            detail.takeIf(String::isNotBlank)?.let { "|$it" }.orEmpty() +
            (if (parentSpanId == null) {
                if (measured.load()) "|${operation!!.summary()}" else "|metricsScope=unmeasured"
            } else ""))
    }
}

package com.ninetag.machum.external

internal fun directoryNameSnapshotIsIncomplete(loadingPresent: Boolean, loading: Any?, errorPresent: Boolean): Boolean =
    (loadingPresent && loading !is Boolean) || loading == true || errorPresent

/** Validates a fresh name cursor completely before authorizing a provider mutation. */
internal fun <C : AutoCloseable> directoryNameSnapshotContains(
    targetName: String,
    query: () -> C?,
    hasNameColumn: (C) -> Boolean,
    isIncomplete: (C) -> Boolean,
    moveToNext: (C) -> Boolean,
    displayName: (C) -> String?,
): Boolean = checkNotNull(query()) { "Directory name query returned no cursor." }.use { cursor ->
    check(hasNameColumn(cursor)) { "Directory name column is missing." }
    check(!isIncomplete(cursor)) { "Directory name snapshot is incomplete." }
    var occupied = false
    while (moveToNext(cursor)) {
        val name = checkNotNull(displayName(cursor)) { "Directory name is missing." }
        check(name.isNotBlank()) { "Directory name is blank." }
        if (name == targetName) occupied = true
    }
    check(!isIncomplete(cursor)) { "Directory name snapshot is incomplete." }
    occupied
}

package com.ninetag.machum.commit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Commit 저장소와 원격 백업이 동일한 canonical JSON을 사용하도록 직렬화를 한곳에 둔다. */
internal object CommitObjectCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeHead(commitId: String): String =
        json.encodeToString(CommitHead.serializer(), CommitHead(commitId))

    fun encodeTree(tree: CommitTree): String = json.encodeToString(
        CommitTree.serializer(),
        tree.copy(
            entries = tree.entries.sortedWith(
                compareBy(CommitTreeEntry::fileId, CommitTreeEntry::relativePath),
            ),
        ),
    )

    fun encodeCommit(commit: ProjectCommit): String =
        json.encodeToString(ProjectCommit.serializer(), commit)

    fun <T> decode(
        serializer: KSerializer<T>,
        content: String,
        description: String,
    ): T = runCatching { json.decodeFromString(serializer, content) }
        .getOrElse { error ->
            throw CommitStorageException("$description 파일을 읽을 수 없습니다.", error)
        }
}

package com.ninetag.machum.external

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.toAndroidUri
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.getValue

private object AndroidFileContext : KoinComponent {
    val value: Context by inject()
}

private fun trashContext(): Context = AndroidFileContext.value
private fun trashDocument(context: Context, file: PlatformFile): DocumentFile =
    DocumentFile.fromTreeUri(context, file.toAndroidUri("com.ninetag.machum.fileprovider"))
        ?: error("저장소 폴더를 읽지 못했습니다.")

private fun hasFreshDirectChildName(context: Context, parent: DocumentFile, targetName: String): Boolean {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        parent.uri,
        DocumentsContract.getDocumentId(parent.uri),
    )
    var nameIndex = -1
    return directoryNameSnapshotContains(
        targetName = targetName,
        query = {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )
        },
        hasNameColumn = { cursor ->
            nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            nameIndex >= 0
        },
        isIncomplete = { cursor ->
            val extras = checkNotNull(cursor.extras) { "Directory cursor extras are missing." }
            @Suppress("DEPRECATION")
            val loading = extras.get(DocumentsContract.EXTRA_LOADING)
            directoryNameSnapshotIsIncomplete(
                loadingPresent = extras.containsKey(DocumentsContract.EXTRA_LOADING),
                loading = loading,
                errorPresent = extras.containsKey(DocumentsContract.EXTRA_ERROR),
            )
        },
        moveToNext = { it.moveToNext() },
        displayName = { cursor -> if (cursor.isNull(nameIndex)) null else cursor.getString(nameIndex) },
    )
}

internal actual suspend fun listDirectoryEntries(
    directory: PlatformFile,
    strict: Boolean,
): List<PlatformDirectoryEntry> = withContext(Dispatchers.IO) {
    WorkspaceLoadDiagnostics.io(WorkspaceLoadIo.DIRECTORY) {
    when (val androidFile = directory.androidFile) {
        is AndroidFile.FileWrapper -> {
            val file = androidFile.file
            if (strict) check(file.isDirectory) { "디렉터리 경로를 읽을 수 없습니다: $file" }
            val children = file.listFiles()
            if (strict) checkNotNull(children) { "디렉터리 파일 목록을 읽을 수 없습니다: $file" }
            children.orEmpty().map { child ->
                PlatformDirectoryEntry(
                    platformFile = PlatformFile(child),
                    name = child.name,
                    isDirectory = child.isDirectory,
                    modifiedAt = child.lastModified().takeIf { it > 0L },
                )
            }
        }

        is AndroidFile.UriWrapper -> {
            val context = trashContext()
            val parentDocumentId = try {
                DocumentsContract.getDocumentId(androidFile.uri)
            } catch (_: IllegalArgumentException) {
                DocumentsContract.getTreeDocumentId(androidFile.uri)
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                androidFile.uri,
                parentDocumentId,
            )
            context.contentResolver.query(
                childrenUri,
                DIRECTORY_ENTRY_PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                check(idIndex >= 0 && nameIndex >= 0 && mimeIndex >= 0) {
                    "저장소가 파일 목록 메타데이터를 제공하지 않았습니다."
                }
                buildList {
                    while (cursor.moveToNext()) {
                        if (strict) {
                            check(
                                !cursor.isNull(idIndex) &&
                                    !cursor.isNull(nameIndex) &&
                                    !cursor.isNull(mimeIndex)
                            ) { "저장소 파일 목록에 필수 메타데이터가 누락되었습니다." }
                        }
                        if (cursor.isNull(idIndex) || cursor.isNull(nameIndex)) continue
                        val documentId = cursor.getString(idIndex)
                        val name = cursor.getString(nameIndex)
                        if (strict) {
                            check(!documentId.isNullOrBlank() && !name.isNullOrBlank()) {
                                "저장소 파일 목록에 빈 식별자 또는 이름이 있습니다."
                            }
                        }
                        if (name.isNullOrBlank()) continue
                        val mimeType = if (cursor.isNull(mimeIndex)) null else cursor.getString(mimeIndex)
                        if (strict) check(!mimeType.isNullOrBlank()) {
                            "저장소 파일 목록에 빈 MIME 형식이 있습니다."
                        }
                        add(
                            PlatformDirectoryEntry(
                                platformFile = PlatformFile(
                                    DocumentsContract.buildDocumentUriUsingTree(
                                        androidFile.uri,
                                        documentId,
                                    ),
                                ),
                                name = name,
                                isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                                modifiedAt = if (modifiedIndex < 0 || cursor.isNull(modifiedIndex)) {
                                    null
                                } else {
                                    cursor.getLong(modifiedIndex).takeIf { it > 0L }
                                },
                            ),
                        )
                    }
                }
            } ?: error("저장소 파일 목록을 읽지 못했습니다.")
        }
    }
    }
}

private val DIRECTORY_ENTRY_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
)

internal actual suspend fun validateWorkspaceTrashChild(parent: PlatformFile, child: PlatformFile): Unit = withContext(Dispatchers.IO) {
    validateWorkspaceTrashDocument(parent, child, directory = true)
}

internal actual suspend fun validateWorkspaceTrashFile(parent: PlatformFile, file: PlatformFile): Unit = withContext(Dispatchers.IO) {
    validateWorkspaceTrashDocument(parent, file, directory = false)
}

private fun validateWorkspaceTrashDocument(parent: PlatformFile, child: PlatformFile, directory: Boolean) {
    val context = trashContext()
    val parentDoc = trashDocument(context, parent)
    val childDoc = trashDocument(context, child)
    check(parentDoc.isDirectory && (if (directory) childDoc.isDirectory else childDoc.isFile) && parentDoc.uri != childDoc.uri)
    check(parentDoc.uri.authority == childDoc.uri.authority && parentDoc.listFiles().any { it.uri == childDoc.uri }) {
        "현재 저장소의 직속 폴더가 아닙니다."
    }
    // A SAF provider owns document identities; do not infer membership from URI path strings.
    val seen = mutableSetOf<String>()
    fun verify(doc: DocumentFile) {
        check(doc.uri.authority == parentDoc.uri.authority && seen.add(doc.uri.toString())) { "중복 또는 외부 문서 경로입니다." }
        check(!doc.isVirtual) { "가상 문서는 휴지통으로 처리할 수 없습니다." }
        if (doc.isDirectory) doc.listFiles().forEach(::verify)
    }
    verify(childDoc)
}

private fun requireTrashProviderFlag(context: Context, doc: DocumentFile, flag: Int) {
    val flags = context.contentResolver.query(doc.uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
        if (it.moveToFirst()) it.getInt(0) else 0
    } ?: 0
    check(flags and flag != 0) { "이 저장소는 안전한 휴지통 이동 또는 영구 삭제를 지원하지 않습니다." }
}

internal actual suspend fun moveWorkspaceItemNative(vault: PlatformFile, sourceParent: PlatformFile, source: PlatformFile, entry: PlatformFile): PlatformFile = withContext(Dispatchers.IO) {
    val context = trashContext()
    val vaultDoc = trashDocument(context, vault)
    val parent = trashDocument(context, sourceParent)
    val target = trashDocument(context, entry)
    val doc = trashDocument(context, source)
    check(parent.uri == vaultDoc.uri || vaultDoc.listFiles().any { workspace ->
        workspace.isDirectory && (workspace.uri == parent.uri || workspace.listFiles().any { it.isDirectory && it.uri == parent.uri })
    }) { "현재 Vault의 문서 폴더가 아닙니다." }
    validateWorkspaceTrashDocument(sourceParent, source, directory = doc.isDirectory)
    val trash = vaultDoc.listFiles().singleOrNull { it.name == WORKSPACE_TRASH_NAME } ?: error("휴지통이 없습니다.")
    validateWorkspaceTrashChild(vault, PlatformFile(trash.uri))
    validateWorkspaceTrashChild(PlatformFile(trash.uri), entry)
    check(target.findFile(doc.name.orEmpty()) == null) { "휴지통 항목이 이미 있습니다." }
    requireTrashProviderFlag(context, doc, DocumentsContract.Document.FLAG_SUPPORTS_MOVE)
    // Provider-native move only. Never emulate it with copy followed by recursive deletion.
    val moved = DocumentsContract.moveDocument(context.contentResolver, doc.uri, parent.uri, target.uri)
        ?: error("저장소가 폴더 이동을 거부했습니다.")
    PlatformFile(moved)
}

internal actual suspend fun purgeWorkspaceTrashEntryNative(trash: PlatformFile, entry: PlatformFile): Unit = withContext(Dispatchers.IO) {
    check(trash.name == WORKSPACE_TRASH_NAME && workspaceTrashIdPattern.matches(entry.name))
    validateWorkspaceTrashChild(trash, entry)
    val context = trashContext()
    val doc = trashDocument(context, entry)
    requireTrashProviderFlag(context, doc, DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
    // SAF cannot atomically bind validation to deletion against external provider writers.
    check(DocumentsContract.deleteDocument(context.contentResolver, doc.uri)) { "저장소가 휴지통 정리를 거부했습니다." }
}

internal actual suspend fun FileManager.createFile(
    parentDirectory: PlatformFile,
    name: String,
    content: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    val context = trashContext()
    val parentDoc = DocumentFile.fromTreeUri(
        context, parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"),
    ) ?: return@withContext null
    val before = parentDoc.listFiles()
    val names = before.map { it.name.orEmpty().lowercase() }.toSet()
    var fileName = name
    var index = 1
    while ("$fileName.md".lowercase() in names) fileName = "${name}_${index++}"
    // Recheck all item types, including directories, immediately before provider creation.
    if (parentDoc.listFiles().any { it.name.orEmpty().equals("$fileName.md", ignoreCase = true) }) {
        return@withContext null
    }
    val created = parentDoc.createFile("text/markdown", "$fileName.md") ?: return@withContext null
    check(before.none { it.uri == created.uri }) {
        "저장소가 기존 파일을 반환했습니다. 목록을 새로고침해 주세요."
    }
    check(created.name == "$fileName.md" && created.isFile && parentDoc.listFiles().any {
        it.uri == created.uri && it.name == "$fileName.md"
    }) { "요청한 이름과 다른 파일이 생성되었습니다: ${created.uri}. 목록에서 확인해 주세요." }
    val file = PlatformFile(created.uri)
    try {
        file.writeString(content)
    } catch (error: Exception) {
        throw incompleteFileCreation(file, content, error)
    }
    file
}

internal actual suspend fun FileManager.createFolder(
    parentDirectory: PlatformFile,
    name: String
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        val context = trashContext()
        val parentDoc = DocumentFile.fromTreeUri(
            context,
            parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"))
            ?:return@withContext null
        val existing = parentDoc.findFile(name)
        if (existing != null && existing.isDirectory) return@withContext  PlatformFile(existing.uri)
        val newDir = parentDoc.createDirectory(name)?:return@withContext  null
        PlatformFile(newDir.uri)
    } catch (e: Exception) {
        println("폴더 생성 실패: $e")
        throw e
    }
}

internal actual suspend fun FileManager.setConfig(
    parentDirectory: PlatformFile,
    fileName: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    val entries = listDirectoryEntries(parentDirectory, strict = false)
    val existing = entries
        .firstOrNull { it.name == fileName }
    if (existing != null) return@withContext existing.platformFile.takeUnless { existing.isDirectory }

    when (val androidFile = parentDirectory.androidFile) {
        is AndroidFile.FileWrapper -> {
            val file = androidFile.file.resolve(fileName)
            if (!file.createNewFile()) return@withContext null
            PlatformFile(file)
        }

        is AndroidFile.UriWrapper -> {
            val parentUri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(
                    androidFile.uri,
                    DocumentsContract.getDocumentId(androidFile.uri),
                )
            }.getOrElse {
                DocumentsContract.buildDocumentUriUsingTree(
                    androidFile.uri,
                    DocumentsContract.getTreeDocumentId(androidFile.uri),
                )
            }
            val resolver = trashContext().contentResolver
            val createdUri = DocumentsContract.createDocument(
                resolver,
                parentUri,
                "application/json",
                fileName,
            ) ?: return@withContext null
            val createdId = DocumentsContract.getDocumentId(createdUri)
            val existedBefore = entries.any { entry ->
                (entry.platformFile.androidFile as? AndroidFile.UriWrapper)?.let { file ->
                    runCatching { DocumentsContract.getDocumentId(file.uri) }.getOrNull() == createdId
                } == true
            }
            try {
                val created = listDirectoryEntries(parentDirectory, strict = true).singleOrNull { entry ->
                    (entry.platformFile.androidFile as? AndroidFile.UriWrapper)?.let { file ->
                        runCatching { DocumentsContract.getDocumentId(file.uri) }.getOrNull() == createdId
                    } == true
                }
                check(!existedBefore && created != null && created.name == fileName && !created.isDirectory) {
                    "저장소가 요청한 설정 파일 이름을 보존하지 않았습니다."
                }
                created.platformFile
            } catch (error: Exception) {
                if (!existedBefore) runCatching { DocumentsContract.deleteDocument(resolver, createdUri) }
                throw error
            }
        }
    }
}

internal actual suspend fun FileManager.validPermission(file: PlatformFile): Boolean {
    return try {
        val context = trashContext()
        when (val androidFile = file.androidFile) {
            is AndroidFile.FileWrapper -> androidFile.file.canRead() && androidFile.file.canWrite()
            is AndroidFile.UriWrapper -> {
                val targetUri = androidFile.uri
                val targetTreeId = runCatching { DocumentsContract.getTreeDocumentId(targetUri) }.getOrNull()
                context.contentResolver.persistedUriPermissions.any { permission ->
                    permission.isReadPermission && permission.isWritePermission &&
                        permission.uri.authority == targetUri.authority &&
                        (permission.uri == targetUri || targetTreeId != null &&
                            runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull() == targetTreeId)
                }
            }
        }
    } catch (e: Exception) {
        println("Config 생성 실패: $e")
        throw e
    }
}

internal actual fun PlatformFile.getLastModified(): Long? {
    return try {
        val context = trashContext()
        val cursor = context.contentResolver.query(
            toAndroidUri("com.ninetag.machum.fileprovider"),
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(0)
            } else null
        }
    } catch (e: Exception) {
        println("파일 메타데이터 쿼리 실패: $e")
        throw e
    }
}

internal actual suspend fun FileManager.createFolderExclusive(
    parentDirectory: PlatformFile,
    name: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    val context = trashContext()
    val parent = DocumentFile.fromTreeUri(
        context, parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"),
    ) ?: return@withContext null
    val before = parent.listFiles()
    if (before.any { it.name.orEmpty().equals(name, ignoreCase = true) }) return@withContext null
    val created = parent.createDirectory(name) ?: return@withContext null
    check(before.none { it.uri == created.uri }) {
        "저장소가 기존 폴더를 반환했습니다. 목록을 새로고침해 주세요."
    }
    // The common boundary verifies the actual provider name before registering it.
    PlatformFile(created.uri)
}

internal actual suspend fun FileManager.deleteEmptyFolderExclusive(directory: PlatformFile): Boolean =
    withContext(Dispatchers.IO) {
        // SAF has no atomic 'delete only if empty'. A recursive provider delete can race an
        // external writer, so retain the unregistered folder and report its actual location.
        false
    }

internal actual suspend fun FileManager.renameMarkdownExact(
    parentDirectory: PlatformFile,
    file: PlatformFile,
    name: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    val trace = WorkspaceLoadDiagnostics.start("file-rename.native")
    try {
        val context = trashContext()
        val parentDoc = DocumentFile.fromTreeUri(
            context,
            parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"),
        ) ?: return@withContext null
        val extension = WorkspaceLoadDiagnostics.time("file-rename.native.extension", parent = trace) {
            file.name.substringAfterLast('.', missingDelimiterValue = "md")
        }
        val targetName = "$name.$extension"
        if (WorkspaceLoadDiagnostics.time("file-rename.native.find-target", parent = trace) {
            hasFreshDirectChildName(context, parentDoc, targetName)
        }) return@withContext null
        val doc = DocumentFile.fromTreeUri(
            context,
            file.toAndroidUri("com.ninetag.machum.fileprovider"),
        ) ?: return@withContext null
        if (!WorkspaceLoadDiagnostics.time("file-rename.native.rename", parent = trace) {
            doc.renameTo(targetName)
        }) return@withContext null
        PlatformFile(doc.uri)
    } catch (error: Exception) {
        trace.fail(error)
        null
    } catch (error: Throwable) {
        trace.fail(error)
        throw error
    } finally {
        trace.complete()
    }
}

internal actual suspend fun moveProjectFileNative(
    project: PlatformFile,
    sourceParent: PlatformFile,
    source: PlatformFile,
    targetParent: PlatformFile,
): PlatformFile = withContext(Dispatchers.IO) {
    val context = trashContext()
    val resolver = context.contentResolver
    val projectDoc = trashDocument(context, project)
    check(projectDoc.isDirectory) { "현재 Project 경로가 올바르지 않습니다." }
    fun validatedProjectDirectory(directory: PlatformFile): DocumentFile {
        val document = trashDocument(context, directory)
        check(document.isDirectory && document.uri.authority == projectDoc.uri.authority) {
            "Project 폴더가 올바르지 않습니다."
        }
        check(document.uri == projectDoc.uri || projectDoc.listFiles().any { child ->
            child.isDirectory && child.uri == document.uri
        }) { "Project 루트 또는 직속 폴더가 아닙니다." }
        return document
    }

    val sourceDirectory = validatedProjectDirectory(sourceParent)
    val targetDirectory = validatedProjectDirectory(targetParent)
    check(sourceDirectory.uri != targetDirectory.uri) { "같은 폴더로 이동할 수 없습니다." }
    val sourceDoc = trashDocument(context, source)
    check(sourceDoc.isFile && !sourceDoc.isVirtual && sourceDoc.uri.authority == projectDoc.uri.authority &&
        sourceDoc.name.orEmpty().endsWith(".md", ignoreCase = true) &&
        sourceDirectory.listFiles().any { child -> child.isFile && child.uri == sourceDoc.uri }) {
        "Project 폴더의 직속 Markdown 파일이 아닙니다."
    }
    val sourceName = sourceDoc.name ?: error("문서 이름을 읽지 못했습니다.")
    check(targetDirectory.listFiles().none { child ->
        child.name.orEmpty().equals(sourceName, ignoreCase = true)
    }) { "대상 폴더에 같은 이름의 항목이 있습니다." }
    requireTrashProviderFlag(context, sourceDoc, DocumentsContract.Document.FLAG_SUPPORTS_MOVE)

    // Provider-native move only. Never emulate it with copy followed by deletion.
    val movedUri = DocumentsContract.moveDocument(
        resolver,
        sourceDoc.uri,
        sourceDirectory.uri,
        targetDirectory.uri,
    ) ?: error("저장소가 문서 이동을 거부했습니다.")
    // Post-move verification belongs to the common transaction so a verification failure still
    // has the returned handle needed for provider-native rollback.
    PlatformFile(movedUri)
}

internal actual suspend fun FileManager.renameDirectoryExact(
    parentDirectory: PlatformFile,
    directory: PlatformFile,
    name: String,
): PlatformFile? = withContext(Dispatchers.IO) {
    try {
        val context = trashContext()
        val parentDoc = DocumentFile.fromTreeUri(
            context,
            parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"),
        ) ?: return@withContext null
        if (parentDoc.findFile(name) != null) return@withContext null
        val directoryDoc = DocumentFile.fromTreeUri(
            context,
            directory.toAndroidUri("com.ninetag.machum.fileprovider"),
        ) ?: return@withContext null
        if (!directoryDoc.isDirectory || !directoryDoc.renameTo(name)) return@withContext null
        PlatformFile(directoryDoc.uri)
    } catch (error: Exception) {
        null
    }
}

internal actual suspend fun FileManager.deleteDirectoryExact(
    directory: PlatformFile,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val context = trashContext()
        val directoryDoc = DocumentFile.fromTreeUri(
            context,
            directory.toAndroidUri("com.ninetag.machum.fileprovider"),
        ) ?: return@withContext false
        val children = directoryDoc.listFiles()
        if (children.any { child -> child.isDirectory ||
                (!child.name.orEmpty().endsWith(".md", ignoreCase = true) && !isManagedFolderIdentity(PlatformFile(child.uri))) }) {
            return@withContext false
        }
        children.all(DocumentFile::delete) && directoryDoc.delete()
    } catch (error: Exception) {
        false
    }
}

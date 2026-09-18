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

internal actual suspend fun listDirectoryEntries(
    directory: PlatformFile,
): List<PlatformDirectoryEntry> = withContext(Dispatchers.IO) {
    when (val androidFile = directory.androidFile) {
        is AndroidFile.FileWrapper -> androidFile.file.listFiles().orEmpty().map { child ->
            PlatformDirectoryEntry(
                platformFile = PlatformFile(child),
                name = child.name,
                isDirectory = child.isDirectory,
            )
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
                check(idIndex >= 0 && nameIndex >= 0 && mimeIndex >= 0) {
                    "저장소가 파일 목록 메타데이터를 제공하지 않았습니다."
                }
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.isNull(idIndex) || cursor.isNull(nameIndex)) continue
                        val documentId = cursor.getString(idIndex)
                        val name = cursor.getString(nameIndex)
                        if (name.isNullOrBlank()) continue
                        val mimeType = if (cursor.isNull(mimeIndex)) null else cursor.getString(mimeIndex)
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
                            ),
                        )
                    }
                }
            } ?: error("저장소 파일 목록을 읽지 못했습니다.")
        }
    }
}

private val DIRECTORY_ENTRY_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
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
    try {
        val context = trashContext()
        val parentDoc = DocumentFile.fromTreeUri(
            context,
            parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"))
            ?:return@withContext null
        val existing = parentDoc.findFile(fileName)
        if (existing != null && existing.isFile) return@withContext PlatformFile(existing.uri)
        val newFile = parentDoc.createFile("application/json", fileName)?:return@withContext null
        PlatformFile(newFile.uri)
    } catch (e: Exception) {
        throw e
    }
}

internal actual suspend fun FileManager.validPermission(file: PlatformFile): Boolean {
    return try {
        val context = trashContext()
        context.contentResolver
            .persistedUriPermissions
            .any {
                it.isReadPermission &&
                        it.isWritePermission
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
    try {
        val context = trashContext()
        val parentDoc = DocumentFile.fromTreeUri(
            context,
            parentDirectory.toAndroidUri("com.ninetag.machum.fileprovider"),
        ) ?: return@withContext null
        val extension = file.name.substringAfterLast('.', missingDelimiterValue = "md")
        val targetName = "$name.$extension"
        if (parentDoc.findFile(targetName) != null) return@withContext null
        val doc = DocumentFile.fromTreeUri(
            context,
            file.toAndroidUri("com.ninetag.machum.fileprovider"),
        ) ?: return@withContext null
        if (!doc.renameTo(targetName)) return@withContext null
        PlatformFile(doc.uri)
    } catch (error: Exception) {
        null
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

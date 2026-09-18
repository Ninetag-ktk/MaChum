package com.ninetag.machum.external

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.serialization.Serializable

internal const val WORKSPACE_TRASH_NAME = ".machum-trash"
internal const val WORKSPACE_TRASH_RECEIPT = ".receipt.json"
internal const val WORKSPACE_TRASH_RECEIPT_RECOVERY = ".receipt-recovery.json"
internal const val WORKSPACE_TRASH_CONFIG_RECOVERY = ".project-config-recovery.json"
internal const val WORKSPACE_TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
internal val workspaceTrashIdPattern = Regex("[0-9a-f]{32}")

data class WorkspaceTrashResult(val directory: PlatformFile, val cleanupWarning: String? = null)
data class ProjectFileTrashResult(val file: PlatformFile, val cleanupWarning: String? = null)

@Serializable
internal enum class WorkspaceTrashKind { WORKSPACE, FILE, FOLDER }

/** Written before the move; an incomplete receipt is deliberately never eligible for expiry. */
@Serializable
internal data class WorkspaceTrashReceipt(
    val format: Int = 1,
    val entryId: String,
    val originalName: String,
    val originalLocation: String,
    val kind: WorkspaceTrashKind = WorkspaceTrashKind.WORKSPACE,
    val workspaceIdentity: String? = null,
    val workspaceLocation: String? = null,
    val originalRelativePath: String? = null,
    val removedFileIds: Map<String, String> = emptyMap(),
    val projectConfigRecoveryFile: String? = null,
    val suspendedProjects: Map<String, SuspendedProjectSettings> = emptyMap(),
    val movedAtEpochMillis: Long? = null,
    val movedLocation: String? = null,
    val cleanupComplete: Boolean = false,
)

/** Immutable and separate from the mutable receipt, so failed receipt writes cannot erase the only config copy. */
@Serializable
internal data class WorkspaceTrashConfigRecovery(
    val format: Int = 1,
    val entryId: String,
    val workspaceIdentity: String,
    val originalText: String,
    val updatedText: String,
)

/** Rejects links and non-direct children, including inside the tree to be moved/deleted. */
internal expect suspend fun validateWorkspaceTrashChild(parent: PlatformFile, child: PlatformFile)
internal expect suspend fun validateWorkspaceTrashFile(parent: PlatformFile, file: PlatformFile)
internal expect suspend fun moveWorkspaceItemNative(vault: PlatformFile, sourceParent: PlatformFile, source: PlatformFile, entry: PlatformFile): PlatformFile
/** Called only after validating the app receipt, age, exact entry contents and ancestry. */
internal expect suspend fun purgeWorkspaceTrashEntryNative(trash: PlatformFile, entry: PlatformFile)

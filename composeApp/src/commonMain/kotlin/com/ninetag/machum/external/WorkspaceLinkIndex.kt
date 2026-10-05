package com.ninetag.machum.external

/** Pure, cache-friendly identity for a file below a Vault workspace directory. */
data class WorkspaceLinkPath(
    val workspaceKind: WorkspaceKind,
    val workspaceName: String,
    val relativePath: String,
) {
    init {
        require(workspaceName.isNotBlank()) { "workspaceName must not be blank" }
        require('/' !in workspaceName && '\\' !in workspaceName) { "workspaceName must be one directory name" }
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
        require(relativePath == normalizeStoredPath(relativePath)) { "relativePath must be normalized" }
    }

    val fileName: String get() = relativePath.substringAfterLast('/')
    val fileNameWithoutExtension: String get() = fileName.substringBeforeLast('.', fileName)
    val parentPath: String get() = relativePath.substringBeforeLast('/', "")
    val vaultRelativePath: String get() = "$workspaceName/$relativePath"
}

enum class WorkspaceLinkResourceKind { MARKDOWN, ATTACHMENT }

data class WorkspaceLinkSourceRange(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0 && endExclusive >= start) { "invalid source range" }
    }
}

data class WorkspaceLinkHeading(
    val level: Int,
    val text: String,
    val sourceRange: WorkspaceLinkSourceRange,
    val path: String = text,
)

data class WorkspaceLinkBlock(
    val id: String,
    val sourceRange: WorkspaceLinkSourceRange,
    val preview: String = "",
)

enum class WorkspaceLinkSyntaxKind {
    WIKI_LINK,
    WIKI_EMBED,
    MARKDOWN_LINK,
    MARKDOWN_EMBED,
}

/** Parsed target keeps the user's spelling separately in [WorkspaceLinkReference.rawTarget]. */
data class WorkspaceLinkTarget(
    val file: String?,
    val heading: String? = null,
    val block: String? = null,
    val display: String? = null,
    val malformedPercentEncoding: Boolean = false,
)

data class WorkspaceLinkReference(
    val syntaxKind: WorkspaceLinkSyntaxKind,
    val sourceRange: WorkspaceLinkSourceRange,
    val targetRange: WorkspaceLinkSourceRange,
    val rawTarget: String,
    val target: WorkspaceLinkTarget,
)

data class WorkspaceLinkDocument(
    val path: WorkspaceLinkPath,
    val resourceKind: WorkspaceLinkResourceKind,
    val aliases: List<String> = emptyList(),
    val headings: List<WorkspaceLinkHeading> = emptyList(),
    val blocks: List<WorkspaceLinkBlock> = emptyList(),
    val outgoing: List<WorkspaceLinkReference> = emptyList(),
    /** Read/index failures are data: the resource remains a resolvable target and can be rebuilt later. */
    val issue: String? = null,
    val blockDrafts: List<WorkspaceLinkBlockDraft> = emptyList(),
) {
    companion object {
        fun markdown(path: WorkspaceLinkPath, rawMarkdown: String): WorkspaceLinkDocument =
            WorkspaceLinkScanner.scan(path, rawMarkdown)

        fun unreadableMarkdown(path: WorkspaceLinkPath, issue: String): WorkspaceLinkDocument =
            WorkspaceLinkDocument(path, WorkspaceLinkResourceKind.MARKDOWN, issue = issue)

        fun attachment(path: WorkspaceLinkPath, issue: String? = null): WorkspaceLinkDocument =
            WorkspaceLinkDocument(path, WorkspaceLinkResourceKind.ATTACHMENT, issue = issue)
    }
}

object WorkspaceLinkScanner {
    fun scan(path: WorkspaceLinkPath, rawMarkdown: String): WorkspaceLinkDocument {
        val note = NoteFile.parse(rawMarkdown)
        val body = note.body
        val bodyOffset = rawMarkdown.length - body.length
        val excluded = excludedCode(body)
        val headings = mutableListOf<WorkspaceLinkHeading>()
        val blocks = mutableListOf<WorkspaceLinkBlock>()

        forEachLine(body) { start, end ->
            if (lineIsExcluded(excluded, start, end)) return@forEachLine
            parseHeading(body, start, end, bodyOffset)?.let(headings::add)
            parseBlock(body, start, end, bodyOffset)?.let(blocks::add)
        }

        val previews = mutableMapOf<Int, String>()
        val drafts = scanMarkdownBlockDrafts(rawMarkdown, body, excludedCode(body, includeInlineCode = false)) { block, content ->
            previews[block.sourceRange.start] = content.replace(Regex("\\s+"), " ").trim().take(100)
        }
        return WorkspaceLinkDocument(
            path = path,
            resourceKind = WorkspaceLinkResourceKind.MARKDOWN,
            aliases = note.aliases.distinct(),
            headings = headings.withPaths(),
            blocks = blocks.map { block -> block.copy(preview = previews[block.sourceRange.start] ?: block.preview) },
            outgoing = scanReferences(body, bodyOffset, excluded),
            blockDrafts = drafts,
        )
    }

    private fun List<WorkspaceLinkHeading>.withPaths(): List<WorkspaceLinkHeading> {
        val parents = mutableListOf<WorkspaceLinkHeading>()
        return map { heading ->
            while (parents.lastOrNull()?.level?.let { it >= heading.level } == true) {
                parents.removeAt(parents.lastIndex)
            }
            val resolved = heading.copy(path = (parents.map { it.text } + heading.text).joinToString("#"))
            parents += resolved
            resolved
        }
    }

    internal fun excludedCode(text: String, includeInlineCode: Boolean = true): BooleanArray {
        val excluded = BooleanArray(text.length)
        var fence: Pair<Char, Int>? = null
        forEachLine(text) { start, end ->
            val marker = fenceMarker(text, start, end)
            val active = fence
            if (active != null) {
                excluded.fill(true, start, end)
                if (
                    marker?.first == active.first && marker.second >= active.second &&
                    fenceTailIsBlank(text, start, end, marker)
                ) fence = null
            } else if (marker != null) {
                excluded.fill(true, start, end)
                fence = marker
            }
        }

        if (!includeInlineCode) return excluded
        var i = 0
        while (i < text.length) {
            if (excluded[i] || text[i] != '`' || text.isEscaped(i)) {
                i++
                continue
            }
            val opening = countRun(text, i, '`')
            val close = findCodeClose(text, i + opening, opening, excluded)
            if (close < 0) {
                i += opening
                continue
            }
            excluded.fill(true, i, close + opening)
            i = close + opening
        }
        return excluded
    }

    private fun fenceMarker(text: String, start: Int, end: Int): Pair<Char, Int>? {
        var i = start
        var spaces = 0
        while (i < end && text[i] == ' ' && spaces < 4) {
            i++
            spaces++
        }
        if (spaces > 3 || i >= end || (text[i] != '`' && text[i] != '~')) return null
        val marker = text[i]
        val count = countRun(text, i, marker)
        return if (count >= 3) marker to count else null
    }

    private fun fenceTailIsBlank(
        text: String,
        start: Int,
        end: Int,
        marker: Pair<Char, Int>,
    ): Boolean {
        var cursor = start
        while (cursor < end && text[cursor] == ' ') cursor++
        cursor += marker.second
        return text.substring(cursor, end).isBlank()
    }

    private fun findCodeClose(text: String, from: Int, size: Int, excluded: BooleanArray): Int {
        var i = from
        while (i < text.length && text[i] != '\n' && text[i] != '\r') {
            if (!excluded[i] && text[i] == '`' && countRun(text, i, '`') == size) return i
            i++
        }
        return -1
    }

    private fun parseHeading(
        text: String,
        start: Int,
        end: Int,
        sourceOffset: Int,
    ): WorkspaceLinkHeading? {
        var cursor = start
        repeat(3) { if (cursor < end && text[cursor] == ' ') cursor++ }
        val hashes = countRun(text, cursor, '#')
        if (hashes !in 1..6) return null
        val after = cursor + hashes
        if (after < end && !text[after].isWhitespace()) return null
        val raw = text.substring(after, end).trim()
        val heading = raw.replace(Regex("[ \\t]+#+[ \\t]*$"), "").trim()
        if (heading.isEmpty()) return null
        val textStart = text.indexOf(heading, after)
        return WorkspaceLinkHeading(
            level = hashes,
            text = heading,
            sourceRange = WorkspaceLinkSourceRange(sourceOffset + textStart, sourceOffset + textStart + heading.length),
        )
    }

    internal fun parseBlock(
        text: String,
        start: Int,
        end: Int,
        sourceOffset: Int,
    ): WorkspaceLinkBlock? {
        var cursor = end
        while (cursor > start && text[cursor - 1].isWhitespace()) cursor--
        val idEnd = cursor
        while (cursor > start && isBlockIdCharacter(text[cursor - 1])) cursor--
        if (cursor == idEnd || cursor <= start || text[cursor - 1] != '^') return null
        val caret = cursor - 1
        if (caret > start && !text[caret - 1].isWhitespace()) return null
        return WorkspaceLinkBlock(
            id = text.substring(cursor, idEnd),
            sourceRange = WorkspaceLinkSourceRange(sourceOffset + caret, sourceOffset + idEnd),
            preview = text.substring(start, caret).trim().take(100),
        )
    }

    private fun scanReferences(
        text: String,
        sourceOffset: Int,
        excluded: BooleanArray,
    ): List<WorkspaceLinkReference> {
        val result = mutableListOf<WorkspaceLinkReference>()
        var i = 0
        while (i < text.length) {
            if (excluded[i] || text.isEscaped(i)) {
                i++
                continue
            }
            val wikiStart = when {
                text.startsWith("![[", i) -> i + 1
                text.startsWith("[[", i) -> i
                else -> -1
            }
            if (wikiStart >= 0) {
                val close = findWikiClose(text, wikiStart + 2)
                if (close >= 0 && (wikiStart until close + 2).none { excluded[it] }) {
                    parseWikiReference(text, i, wikiStart, close, sourceOffset)?.let(result::add)
                    i = close + 2
                    continue
                }
            }

            val labelStart = when {
                text.startsWith("![", i) -> i + 1
                text[i] == '[' -> i
                else -> -1
            }
            if (labelStart >= 0 && !text.startsWith("[[", labelStart)) {
                val lineEnd = text.indexOfAny(charArrayOf('\r', '\n'), labelStart + 1)
                    .let { if (it < 0) text.length else it }
                val closeLabel = findUnescapedChar(text, ']', labelStart + 1, lineEnd)
                if (closeLabel >= 0 && closeLabel + 1 < text.length && text[closeLabel + 1] == '(') {
                    val closeTarget = findMarkdownTargetClose(text, closeLabel + 2)
                    if (closeTarget >= 0 && (labelStart until closeTarget + 1).none { excluded[it] }) {
                        parseMarkdownReference(text, i, labelStart, closeLabel, closeTarget, sourceOffset)
                            ?.let(result::add)
                        i = closeTarget + 1
                        continue
                    }
                }
            }
            i++
        }
        return result
    }

    private fun parseWikiReference(
        text: String,
        syntaxStart: Int,
        bracketStart: Int,
        close: Int,
        sourceOffset: Int,
    ): WorkspaceLinkReference? {
        val innerStart = bracketStart + 2
        val separator = findUnescapedChar(text, '|', innerStart, close)
        val targetEnd = if (separator >= 0) separator else close
        val trimmed = trimRange(text, innerStart, targetEnd) ?: return null
        val rawTarget = text.substring(trimmed.first, trimmed.second)
        if (isExternalTarget(rawTarget)) return null
        val display = if (separator >= 0) text.substring(separator + 1, close).trim().ifEmpty { null } else null
        return WorkspaceLinkReference(
            syntaxKind = if (syntaxStart < bracketStart) WorkspaceLinkSyntaxKind.WIKI_EMBED
            else WorkspaceLinkSyntaxKind.WIKI_LINK,
            sourceRange = WorkspaceLinkSourceRange(sourceOffset + syntaxStart, sourceOffset + close + 2),
            targetRange = WorkspaceLinkSourceRange(sourceOffset + trimmed.first, sourceOffset + trimmed.second),
            rawTarget = rawTarget,
            target = parseTarget(rawTarget, display, decodePercent = false),
        )
    }

    private fun parseMarkdownReference(
        text: String,
        syntaxStart: Int,
        labelStart: Int,
        closeLabel: Int,
        closeTarget: Int,
        sourceOffset: Int,
    ): WorkspaceLinkReference? {
        var targetStart = closeLabel + 2
        var targetEnd = closeTarget
        while (targetStart < targetEnd && text[targetStart].isWhitespace()) targetStart++
        while (targetEnd > targetStart && text[targetEnd - 1].isWhitespace()) targetEnd--
        if (targetStart < targetEnd && text[targetStart] == '<' && text[targetEnd - 1] == '>') {
            targetStart++
            targetEnd--
        } else {
            var cursor = targetStart
            while (cursor < targetEnd && !text[cursor].isWhitespace()) cursor++
            targetEnd = cursor
        }
        if (targetStart >= targetEnd) return null
        val rawTarget = text.substring(targetStart, targetEnd)
        if (isExternalTarget(rawTarget)) return null
        return WorkspaceLinkReference(
            syntaxKind = if (syntaxStart < labelStart) WorkspaceLinkSyntaxKind.MARKDOWN_EMBED
            else WorkspaceLinkSyntaxKind.MARKDOWN_LINK,
            sourceRange = WorkspaceLinkSourceRange(sourceOffset + syntaxStart, sourceOffset + closeTarget + 1),
            targetRange = WorkspaceLinkSourceRange(sourceOffset + targetStart, sourceOffset + targetEnd),
            rawTarget = rawTarget,
            target = parseTarget(rawTarget, text.substring(labelStart + 1, closeLabel), decodePercent = true),
        )
    }
}

sealed interface WorkspaceLinkResolution {
    data class Resolved(
        val document: WorkspaceLinkDocument,
        val heading: WorkspaceLinkHeading? = null,
        val block: WorkspaceLinkBlock? = null,
    ) : WorkspaceLinkResolution

    data class Ambiguous(val candidates: List<WorkspaceLinkDocument>) : WorkspaceLinkResolution
    data class Unresolved(val target: WorkspaceLinkTarget) : WorkspaceLinkResolution
    data class Invalid(val rawTarget: String, val reason: WorkspaceLinkInvalidReason) : WorkspaceLinkResolution
}

enum class WorkspaceLinkInvalidReason { ABSOLUTE_PATH, OUTSIDE_WORKSPACE, MALFORMED_PERCENT_ENCODING }

data class WorkspaceLinkBackReference(
    val source: WorkspaceLinkPath,
    val reference: WorkspaceLinkReference,
)

data class WorkspaceLinkUnresolvedReference(
    val source: WorkspaceLinkPath,
    val reference: WorkspaceLinkReference,
    val resolution: WorkspaceLinkResolution,
)

enum class WorkspaceLinkCompletionKind { FILE, HEADING, BLOCK }

data class WorkspaceLinkCompletion(
    val kind: WorkspaceLinkCompletionKind,
    val title: String,
    val detail: String,
    val target: String,
    val path: WorkspaceLinkPath,
    val alias: String? = null,
    val blockDraft: WorkspaceLinkBlockDraft? = null,
)

/** One minimal target-only edit required after an app-owned file move or rename. */
data class WorkspaceLinkRewrite(
    val source: WorkspaceLinkPath,
    val targetRange: WorkspaceLinkSourceRange,
    val expectedTarget: String,
    val replacement: String,
)

/**
 * Small in-memory derived index. Mutation deliberately rebuilds references: correctness matters more than
 * optimizing a cache whose authoritative source is the Vault and whose update batches are normally tiny.
 */
class WorkspaceLinkIndex(
    private val unicodeNormalizer: (String) -> String = { it },
) {
    private val documentsByPath = linkedMapOf<WorkspaceLinkPath, WorkspaceLinkDocument>()
    private var incomingByPath = emptyMap<WorkspaceLinkPath, List<WorkspaceLinkBackReference>>()
    private var unresolved = emptyList<WorkspaceLinkUnresolvedReference>()

    val documents: List<WorkspaceLinkDocument> get() = documentsByPath.values.toList()
    val unresolvedReferences: List<WorkspaceLinkUnresolvedReference> get() = unresolved

    fun upsert(document: WorkspaceLinkDocument) {
        documentsByPath[document.path] = document
        rebuildReferences()
    }

    fun upsert(documents: Iterable<WorkspaceLinkDocument>) {
        documents.forEach { documentsByPath[it.path] = it }
        rebuildReferences()
    }

    fun remove(path: WorkspaceLinkPath): WorkspaceLinkDocument? =
        documentsByPath.remove(path).also { if (it != null) rebuildReferences() }

    fun applyPathChanges(changes: Map<WorkspaceLinkPath, WorkspaceLinkPath>) {
        require(changes.keys.all(documentsByPath::containsKey)) { "path change source is not indexed" }
        val retained = documentsByPath.keys - changes.keys
        require((retained + changes.values).size == retained.size + changes.size) { "path change collides" }
        val moved = changes.map { (old, new) -> documentsByPath.getValue(old).copy(path = new) }
        changes.keys.forEach(documentsByPath::remove)
        moved.forEach { documentsByPath[it.path] = it }
        rebuildReferences()
    }

    fun incoming(target: WorkspaceLinkPath): List<WorkspaceLinkBackReference> = incomingByPath[target].orEmpty()

    /**
     * Plans target-only edits against the pre-move index. Display text and link syntax sit outside
     * [WorkspaceLinkReference.targetRange], so applying these edits cannot rewrite either one.
     */
    fun planPathRewrites(
        changes: Map<WorkspaceLinkPath, WorkspaceLinkPath>,
        sourceOverrides: Map<WorkspaceLinkPath, String> = emptyMap(),
        skipUnrepresentableReferences: Boolean = false,
    ): List<WorkspaceLinkRewrite> {
        if (changes.isEmpty()) return emptyList()
        if (sourceOverrides.isNotEmpty()) {
            return WorkspaceLinkIndex(unicodeNormalizer).also { index ->
                index.upsert(documentsByPath.values.map { document ->
                    sourceOverrides[document.path]?.let { WorkspaceLinkDocument.markdown(document.path, it) }
                        ?: document
                })
            }.planPathRewrites(changes, skipUnrepresentableReferences = skipUnrepresentableReferences)
        }
        require(changes.keys.all(documentsByPath::containsKey)) { "path change source is not indexed" }

        val relocated = WorkspaceLinkIndex(unicodeNormalizer).also { next ->
            next.upsert(documentsByPath.values.map { document ->
                document.copy(path = changes[document.path] ?: document.path)
            })
        }
        return documentsByPath.values.flatMap { document ->
            val newSource = changes[document.path] ?: document.path
            document.outgoing.mapNotNull { reference ->
                val oldResolution = resolve(document.path, reference) as? WorkspaceLinkResolution.Resolved
                    ?: return@mapNotNull null
                val newTarget = changes[oldResolution.document.path] ?: oldResolution.document.path
                val oldFile = reference.target.file.orEmpty().replace('\\', '/')
                val unchangedResolution = relocated.resolve(newSource, reference) as? WorkspaceLinkResolution.Resolved
                if (unchangedResolution?.document?.path == newTarget &&
                    ('/' !in oldFile || oldResolution.document.path == newTarget)) return@mapNotNull null

                val targetDocument = relocated.documentsByPath[newTarget] ?: return@mapNotNull null
                val fileTarget = if (reference.target.file == null && newSource == newTarget) {
                    ""
                } else if ('/' in oldFile) {
                    // Explicit paths must not become bare names when a rename happens to be unique.
                    val oldTarget = oldResolution.document.path
                    val keepExtension = targetDocument.resourceKind != WorkspaceLinkResourceKind.MARKDOWN ||
                        oldFile.endsWith(".md", ignoreCase = true)
                    if (oldTarget.workspaceName == newTarget.workspaceName && oldTarget.parentPath == newTarget.parentPath) {
                        val name = if (keepExtension) newTarget.fileName else newTarget.fileNameWithoutExtension
                        oldFile.substringBeforeLast('/') + "/" + name
                    } else {
                        val path = if (newSource.workspaceName == newTarget.workspaceName && newTarget.parentPath.isNotEmpty() &&
                            !oldFile.startsWith(oldTarget.workspaceName + "/", ignoreCase = true)) {
                            newTarget.relativePath
                        } else newTarget.vaultRelativePath
                        if (!keepExtension && path.endsWith(".md", ignoreCase = true)) path.dropLast(3) else path
                    }
                } else {
                    relocated.shortestTarget(newSource, targetDocument)
                }
                val fragment = reference.rawTarget.substringAfter('#', "").let { rawFragment ->
                    when {
                        '#' in reference.rawTarget -> "#$rawFragment"
                        reference.rawTarget.startsWith('^') -> reference.rawTarget
                        else -> ""
                    }
                }
                fun preservesReference(replacement: String): Boolean {
                    val syntax = when (reference.syntaxKind) {
                        WorkspaceLinkSyntaxKind.WIKI_LINK -> "[[$replacement]]"
                        WorkspaceLinkSyntaxKind.WIKI_EMBED -> "![[$replacement]]"
                        WorkspaceLinkSyntaxKind.MARKDOWN_LINK -> "[label](<$replacement>)"
                        WorkspaceLinkSyntaxKind.MARKDOWN_EMBED -> "![label](<$replacement>)"
                    }
                    val rewrittenReference = WorkspaceLinkDocument.markdown(newSource, syntax).outgoing.singleOrNull()
                    val rewrittenResolution = rewrittenReference?.let { relocated.resolve(newSource, it) }
                        as? WorkspaceLinkResolution.Resolved
                    return !(rewrittenReference?.rawTarget != replacement || rewrittenResolution?.document?.path != newTarget || rewrittenResolution.heading != oldResolution.heading || rewrittenResolution.block != oldResolution.block)
                }
                val replacement = listOf(fileTarget, targetDocument.path.vaultRelativePath)
                    .map { encodeLinkTarget(it, reference.syntaxKind) + fragment }
                    .firstOrNull(::preservesReference)
                if (replacement == null && skipUnrepresentableReferences) return@mapNotNull null
                requireNotNull(replacement) {
                    "기존 링크 문법으로 변경된 파일 이름을 표현할 수 없습니다: ${newTarget.vaultRelativePath}"
                }
                if (replacement == reference.rawTarget) return@mapNotNull null
                WorkspaceLinkRewrite(document.path, reference.targetRange, reference.rawTarget, replacement)
            }
        }
    }

    /** Candidates visible while writing an internal link; retain an explicitly typed directory. */
    fun completeFiles(source: WorkspaceLinkPath, query: String): List<WorkspaceLinkCompletion> {
        val typed = query.trim().replace('\\', '/')
        val needle = typed.lowercase()
        val scope = documentsByPath.values.filter { it.isInCompletionScope(source) }
        return scope.mapNotNull { document ->
            val path = document.path
            val primary = if (document.resourceKind == WorkspaceLinkResourceKind.MARKDOWN) {
                path.fileNameWithoutExtension
            } else {
                path.fileName
            }
            val matchingAlias = document.aliases.firstOrNull { it.contains(needle, ignoreCase = true) }
            val searchable = listOf(primary, path.fileName, path.relativePath, path.vaultRelativePath) + document.aliases
            if (needle.isNotEmpty() && searchable.none { it.contains(needle, ignoreCase = true) }) return@mapNotNull null
            val rank = completionRank(source, document, needle, primary, matchingAlias)
            val target = if ('/' in typed) {
                val keepExtension = document.resourceKind != WorkspaceLinkResourceKind.MARKDOWN || typed.endsWith(".md", ignoreCase = true)
                val name = if (keepExtension) path.fileName else primary
                val explicit = typed.substringBeforeLast('/') + "/" + name
                val resolved = resolve(source, WorkspaceLinkTarget(explicit)) as? WorkspaceLinkResolution.Resolved
                val explicitPath = if (keepExtension) explicit else "$explicit.md"
                val directoryMatches = listOf(explicitPath, collapsePath(source.parentPath, explicitPath)).any { value ->
                    MatchMode.CASE_INSENSITIVE.matches(path.vaultRelativePath, value, unicodeNormalizer) ||
                        path.workspaceName == source.workspaceName &&
                        MatchMode.CASE_INSENSITIVE.matches(path.relativePath, value, unicodeNormalizer)
                }
                if (directoryMatches && resolved?.document?.path == path) explicit else {
                    val full = if (path.workspaceName == source.workspaceName) path.relativePath else path.vaultRelativePath
                    val completed = if (keepExtension) full else full.dropLast(3)
                    if ((resolve(source, WorkspaceLinkTarget(completed)) as? WorkspaceLinkResolution.Resolved)?.document?.path == path) {
                        completed
                    } else if (keepExtension) path.vaultRelativePath else path.vaultRelativePath.dropLast(3)
                }
            } else shortestCompletionTarget(source, document, scope)
            RankedCompletion(
                rank = rank,
                value = WorkspaceLinkCompletion(
                    kind = WorkspaceLinkCompletionKind.FILE,
                    title = matchingAlias ?: primary,
                    detail = path.vaultRelativePath,
                    target = target,
                    path = path,
                    alias = matchingAlias,
                ),
            )
        }
            .sortedWith(compareBy<RankedCompletion> { it.rank }.thenBy { it.value.detail.lowercase() })
            .map(RankedCompletion::value)
    }

    fun completeHeadings(
        source: WorkspaceLinkPath,
        file: String?,
        query: String,
    ): List<WorkspaceLinkCompletion> = completionDocument(source, file)?.headings
        .orEmpty()
        .filter { query.isBlank() || it.path.contains(query, ignoreCase = true) }
        .sortedWith(compareBy<WorkspaceLinkHeading> {
            when {
                it.path.equals(query, ignoreCase = true) -> 0
                it.path.startsWith(query, ignoreCase = true) -> 1
                else -> 2
            }
        }.thenBy { it.sourceRange.start })
        .map { heading ->
            val document = completionDocument(source, file)!!
            WorkspaceLinkCompletion(
                kind = WorkspaceLinkCompletionKind.HEADING,
                title = heading.text,
                detail = heading.path,
                target = "${file.orEmpty()}#${heading.path}",
                path = document.path,
            )
        }

    fun completeBlocks(
        source: WorkspaceLinkPath,
        file: String?,
        query: String,
        documentOverride: WorkspaceLinkDocument? = null,
    ): List<WorkspaceLinkCompletion> {
        val indexed = completionDocument(source, file) ?: return emptyList()
        val document = documentOverride?.takeIf { it.path == indexed.path } ?: indexed
        val existing = document.blocks
            .filter { query.isBlank() || it.id.contains(query, ignoreCase = true) || it.preview.contains(query, ignoreCase = true) }
            .sortedWith(compareBy<WorkspaceLinkBlock> {
                when {
                    it.id.equals(query, ignoreCase = true) -> 0
                    it.id.startsWith(query, ignoreCase = true) -> 1
                    else -> 2
                }
            }.thenBy { it.sourceRange.start })
            .map { block ->
                WorkspaceLinkCompletion(
                    kind = WorkspaceLinkCompletionKind.BLOCK,
                    title = block.preview.ifBlank { "^${block.id}" },
                    detail = document.path.vaultRelativePath,
                    target = if (file.isNullOrBlank()) "^${block.id}" else "$file#^${block.id}",
                    path = document.path,
                )
            }
        val drafts = document.blockDrafts
            .filter { query.isBlank() || it.expectedText.contains(query, ignoreCase = true) }
            .map { draft ->
                WorkspaceLinkCompletion(
                    kind = WorkspaceLinkCompletionKind.BLOCK,
                    title = draft.preview,
                    detail = document.path.vaultRelativePath,
                    target = if (file.isNullOrBlank()) "^" else "$file#^",
                    path = document.path,
                    blockDraft = draft,
                )
            }
        return existing + drafts
    }

    fun resolve(source: WorkspaceLinkPath, reference: WorkspaceLinkReference): WorkspaceLinkResolution =
        resolve(source, reference.target, reference.rawTarget)

    fun resolve(
        source: WorkspaceLinkPath,
        target: WorkspaceLinkTarget,
        rawTarget: String = target.file.orEmpty(),
    ): WorkspaceLinkResolution {
        if (target.malformedPercentEncoding) return WorkspaceLinkResolution.Invalid(
            rawTarget,
            WorkspaceLinkInvalidReason.MALFORMED_PERCENT_ENCODING,
        )
        val file = target.file?.replace('\\', '/')?.trim()
        if (file != null && (file.startsWith('/') || file.startsWith("~/") || WINDOWS_ABSOLUTE.matches(file))) {
            return WorkspaceLinkResolution.Invalid(rawTarget, WorkspaceLinkInvalidReason.ABSOLUTE_PATH)
        }
        if (file != null && collapsesOutsideWorkspace(source.parentPath, file)) {
            return WorkspaceLinkResolution.Invalid(rawTarget, WorkspaceLinkInvalidReason.OUTSIDE_WORKSPACE)
        }

        val candidates = if (file.isNullOrEmpty()) {
            listOfNotNull(documentsByPath[source])
        } else {
            resolveDocuments(source, file)
        }
        if (candidates.isEmpty()) return WorkspaceLinkResolution.Unresolved(target.copy(file = file))
        if (candidates.size > 1) return WorkspaceLinkResolution.Ambiguous(candidates)

        val document = candidates.single()
        val heading = target.heading?.let { fragment ->
            uniqueFragment(document.headings, fragment) { heading ->
                if ('#' in fragment) heading.path else heading.text
            }
                ?: return WorkspaceLinkResolution.Unresolved(target.copy(file = file))
        }
        val block = target.block?.let { fragment ->
            uniqueFragment(document.blocks, fragment) { it.id }
                ?: return WorkspaceLinkResolution.Unresolved(target.copy(file = file))
        }
        return WorkspaceLinkResolution.Resolved(document, heading, block)
    }

    private fun resolveDocuments(source: WorkspaceLinkPath, file: String): List<WorkspaceLinkDocument> {
        val path = collapsePath(source.parentPath, file)
        val fileName = file.substringAfterLast('/')
        val hasExplicitExtension = fileName.substringAfterLast('.', "").isNotEmpty()
        val allowMarkdownExtension = !fileName.endsWith(".md", ignoreCase = true)
        val bareFileName = fileName.substringBeforeLast('.', fileName)
        val implicitScope: (WorkspaceLinkDocument) -> Boolean = { document ->
            document.path.workspaceName == source.workspaceName ||
                document.path.workspaceKind == WorkspaceKind.GENERAL
        }
        val stages = buildList {
            add(MatchStage(path, allowMarkdownExtension = allowMarkdownExtension) {
                if (it.path.workspaceName == source.workspaceName) listOf(it.path.relativePath) else emptyList()
            })
            add(MatchStage(file, allowMarkdownExtension = allowMarkdownExtension) {
                if (it.path.workspaceName == source.workspaceName) listOf(it.path.relativePath) else emptyList()
            })
            add(MatchStage(file, allowMarkdownExtension = allowMarkdownExtension) { listOf(it.path.vaultRelativePath) })
            add(MatchStage(file, allowMarkdownExtension = allowMarkdownExtension) {
                if (implicitScope(it)) listOf(it.path.fileName) else emptyList()
            })
            if (!hasExplicitExtension) {
                add(MatchStage(bareFileName) {
                    if (implicitScope(it) && it.resourceKind == WorkspaceLinkResourceKind.MARKDOWN) {
                        listOf(it.path.fileNameWithoutExtension)
                    } else emptyList()
                })
                add(MatchStage(bareFileName) {
                    if (implicitScope(it) && it.resourceKind == WorkspaceLinkResourceKind.MARKDOWN) it.aliases
                    else emptyList()
                })
            }
        }
        for (mode in MatchMode.entries) {
            stages.forEach { stage ->
                val exactMatches = documentsByPath.values.filter { document ->
                    stage.selector(document).any { value ->
                        mode.matches(value, stage.needle, unicodeNormalizer)
                    }
                }
                if (exactMatches.isNotEmpty()) return exactMatches.distinctBy { it.path }
                if (stage.allowMarkdownExtension) {
                    val markdownMatches = documentsByPath.values.filter { document ->
                        document.resourceKind == WorkspaceLinkResourceKind.MARKDOWN &&
                            stage.selector(document).any { value ->
                                mode.matches(value, "${stage.needle}.md", unicodeNormalizer)
                            }
                    }
                    if (markdownMatches.isNotEmpty()) return markdownMatches.distinctBy { it.path }
                }
            }
        }
        return emptyList()
    }

    private fun completionDocument(source: WorkspaceLinkPath, file: String?): WorkspaceLinkDocument? {
        if (file.isNullOrBlank()) return documentsByPath[source]
        return (resolve(source, WorkspaceLinkTarget(file)) as? WorkspaceLinkResolution.Resolved)?.document
    }

    private fun WorkspaceLinkDocument.isInCompletionScope(source: WorkspaceLinkPath): Boolean =
        path.workspaceName == source.workspaceName || path.workspaceKind == WorkspaceKind.GENERAL

    private fun completionRank(
        source: WorkspaceLinkPath,
        document: WorkspaceLinkDocument,
        query: String,
        primary: String,
        matchingAlias: String?,
    ): Int = when {
        query.isNotEmpty() && primary.equals(query, ignoreCase = true) -> 0
        document.path.workspaceName == source.workspaceName && document.path.parentPath == source.parentPath -> 1
        query.isEmpty() || primary.startsWith(query, ignoreCase = true) -> 2
        matchingAlias != null -> 3
        else -> 4
    }

    private fun shortestCompletionTarget(
        source: WorkspaceLinkPath,
        document: WorkspaceLinkDocument,
        scope: List<WorkspaceLinkDocument>,
    ): String {
        val markdown = document.resourceKind == WorkspaceLinkResourceKind.MARKDOWN
        val name = if (markdown) document.path.fileNameWithoutExtension else document.path.fileName
        val sameName = scope.count { candidate ->
            val candidateName = if (candidate.resourceKind == WorkspaceLinkResourceKind.MARKDOWN) {
                candidate.path.fileNameWithoutExtension
            } else {
                candidate.path.fileName
            }
            candidateName.equals(name, ignoreCase = true)
        }
        if (sameName == 1) return name

        val path = if (document.path.workspaceName == source.workspaceName) {
            document.path.relativePath
        } else {
            document.path.vaultRelativePath
        }
        return if (markdown && path.endsWith(".md", ignoreCase = true)) path.dropLast(3) else path
    }

    private fun shortestTarget(source: WorkspaceLinkPath, document: WorkspaceLinkDocument): String =
        shortestCompletionTarget(
            source = source,
            document = document,
            scope = documentsByPath.values.filter { it.isInCompletionScope(source) },
        )

    private fun <T> uniqueFragment(items: List<T>, needle: String, value: (T) -> String): T? {
        for (mode in MatchMode.entries) {
            val matches = items.filter { mode.matches(value(it), needle, unicodeNormalizer) }
            if (matches.size == 1) return matches.single()
            if (matches.size > 1) return null
        }
        return null
    }

    private fun rebuildReferences() {
        val incoming = linkedMapOf<WorkspaceLinkPath, MutableList<WorkspaceLinkBackReference>>()
        val missing = mutableListOf<WorkspaceLinkUnresolvedReference>()
        documentsByPath.values.forEach { source ->
            source.outgoing.forEach { reference ->
                when (val resolution = resolve(source.path, reference)) {
                    is WorkspaceLinkResolution.Resolved -> incoming.getOrPut(resolution.document.path, ::mutableListOf)
                        .add(WorkspaceLinkBackReference(source.path, reference))
                    else -> missing += WorkspaceLinkUnresolvedReference(source.path, reference, resolution)
                }
            }
        }
        incomingByPath = incoming
        unresolved = missing
    }
}

private data class RankedCompletion(
    val rank: Int,
    val value: WorkspaceLinkCompletion,
)

private data class MatchStage(
    val needle: String,
    val allowMarkdownExtension: Boolean = false,
    val selector: (WorkspaceLinkDocument) -> List<String>,
)

private fun encodeLinkTarget(target: String, syntaxKind: WorkspaceLinkSyntaxKind): String =
    if (syntaxKind == WorkspaceLinkSyntaxKind.MARKDOWN_LINK || syntaxKind == WorkspaceLinkSyntaxKind.MARKDOWN_EMBED) {
        buildString {
            target.forEach { character ->
                if (character in "%#()[] ^") {
                    append('%')
                    append(character.code.toString(16).uppercase().padStart(2, '0'))
                } else append(character)
            }
        }
    } else {
        target
    }

private enum class MatchMode {
    EXACT,
    NFC,
    CASE_INSENSITIVE;

    fun matches(value: String, needle: String, normalizer: (String) -> String): Boolean = when (this) {
        EXACT -> value == needle
        NFC -> normalizer(value) == normalizer(needle)
        CASE_INSENSITIVE -> normalizer(value).equals(normalizer(needle), ignoreCase = true)
    }
}

private fun parseTarget(raw: String, display: String?, decodePercent: Boolean): WorkspaceLinkTarget {
    fun decode(value: String): String? = if (decodePercent) decodePercentOrNull(value) else value

    val hash = raw.indexOf('#')
    if (hash < 0) {
        val value = decode(raw)
        val malformed = value == null
        val decoded = value ?: raw
        return if (decoded.startsWith('^')) {
            WorkspaceLinkTarget(
                null,
                block = decoded.drop(1),
                display = display,
                malformedPercentEncoding = malformed,
            )
        } else {
            WorkspaceLinkTarget(
                decoded.ifEmpty { null },
                display = display,
                malformedPercentEncoding = malformed,
            )
        }
    }

    val decodedFile = decode(raw.substring(0, hash))
    val decodedFragment = decode(raw.substring(hash + 1))
    val malformed = decodedFile == null || decodedFragment == null
    val file = (decodedFile ?: raw.substring(0, hash)).ifEmpty { null }
    val fragment = decodedFragment ?: raw.substring(hash + 1)
    return if (fragment.startsWith('^')) {
        WorkspaceLinkTarget(
            file,
            block = fragment.drop(1),
            display = display,
            malformedPercentEncoding = malformed,
        )
    } else {
        WorkspaceLinkTarget(
            file,
            heading = fragment,
            display = display,
            malformedPercentEncoding = malformed,
        )
    }
}

private fun normalizeStoredPath(path: String): String = path.replace('\\', '/').split('/')
    .filter { it.isNotEmpty() && it != "." }
    .also { require(".." !in it) { "relativePath must stay inside the workspace" } }
    .joinToString("/")

private fun collapsePath(parent: String, child: String): String {
    val parts = parent.split('/').filter(String::isNotEmpty).toMutableList()
    child.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}

private fun collapsesOutsideWorkspace(parent: String, child: String): Boolean {
    var depth = parent.split('/').count(String::isNotEmpty)
    child.split('/').forEach {
        when (it) {
            "", "." -> Unit
            ".." -> if (depth == 0) return true else depth--
            else -> depth++
        }
    }
    return false
}

private fun decodePercentOrNull(value: String): String? {
    if ('%' !in value) return value
    val result = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        if (value[i] != '%') {
            result.append(value[i++])
            continue
        }
        val bytes = mutableListOf<Byte>()
        while (i < value.length && value[i] == '%') {
            if (i + 2 >= value.length) return null
            val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
            bytes += byte.toByte()
            i += 3
        }
        result.append(bytes.toByteArray().decodeToString(throwOnInvalidSequence = false))
    }
    return result.toString()
}

private fun isExternalTarget(target: String): Boolean {
    if (WINDOWS_ABSOLUTE.matches(target)) return false
    val colon = target.indexOf(':')
    if (colon <= 0) return false
    return target.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
}

internal fun forEachLine(text: String, action: (start: Int, endExclusive: Int) -> Unit) {
    var start = 0
    while (start <= text.length) {
        var end = start
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        action(start, end)
        if (end == text.length) return
        start = if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') end + 2 else end + 1
    }
}

internal fun lineIsExcluded(excluded: BooleanArray, start: Int, end: Int): Boolean =
    start < end && (start until end).all { excluded[it] }

private fun countRun(text: String, start: Int, char: Char): Int {
    var end = start
    while (end < text.length && text[end] == char) end++
    return end - start
}

private fun String.isEscaped(index: Int): Boolean {
    var slash = index - 1
    while (slash >= 0 && this[slash] == '\\') slash--
    return (index - slash - 1) % 2 == 1
}

internal fun findWikiClose(text: String, start: Int): Int {
    var index = start
    while (index + 1 < text.length && text[index] != '\n' && text[index] != '\r') {
        if (text[index] == ']' && text[index + 1] == ']' && !text.isEscaped(index)) return index
        index++
    }
    return -1
}

private fun findUnescapedChar(text: String, char: Char, start: Int, end: Int): Int {
    var i = start
    while (i < end) {
        if (text[i] == char && !text.isEscaped(i)) return i
        i++
    }
    return -1
}

private fun findMarkdownTargetClose(text: String, start: Int): Int {
    var depth = 0
    var i = start
    while (i < text.length && text[i] != '\n' && text[i] != '\r') {
        if (!text.isEscaped(i)) when (text[i]) {
            '(' -> depth++
            ')' -> if (depth == 0) return i else depth--
        }
        i++
    }
    return -1
}

private fun trimRange(text: String, start: Int, end: Int): Pair<Int, Int>? {
    var first = start
    var last = end
    while (first < last && text[first].isWhitespace()) first++
    while (last > first && text[last - 1].isWhitespace()) last--
    return if (first < last) first to last else null
}

private fun isBlockIdCharacter(char: Char): Boolean = char.isLetterOrDigit() || char == '-' || char == '_'

private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[/\\\\].*")

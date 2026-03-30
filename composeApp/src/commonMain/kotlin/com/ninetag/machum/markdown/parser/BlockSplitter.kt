package com.ninetag.machum.markdown.parser

class BlockSplitter {
    fun split(text: String): List<RawBlock> {
        val lines = text.lines()
        val blocks = mutableListOf<RawBlock>()
        var current = mutableListOf<String>()
        var startLine = 0
        var blockId: String? = null
        var inCodeBlock = false

        fun flush() {
            if (current.isNotEmpty()) {
                blocks.add(RawBlock(lines = current.toList(), startLine = startLine, blockId = blockId))
                current = mutableListOf()
                blockId = null
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            when {
                // 코드블록 진입/탈출
                trimmed.startsWith("```") -> {
                    if (!inCodeBlock) {
                        flush()
                        startLine = i
                        inCodeBlock = true
                        current.add(line)
                    } else {
                        current.add(line)
                        inCodeBlock = false
                        flush()
                    }
                }

                // 코드블록 내부 - 그대로 수집
                inCodeBlock -> current.add(line)
                // 빈 줄 - 블록 구분자
                trimmed.isEmpty() -> flush()

                // blockId 귀속 (`^` 로만 시작하는 줄)
                trimmed.matches(Regex("\\^\\S+")) -> {
                    blockId = trimmed.removePrefix("^")
                    flush()
                }

                // Heading - 단일 줄 블록
                trimmed.startsWith("#") -> {
                    flush()
                    startLine = i
                    current.add(line)
                    flush()
                }

                // Quote (Callout/Blockquote) - `>` 로 시작하는 줄 묶음
                trimmed.startsWith(">") -> {
                    if (current.isEmpty()) startLine = i
                    current.add(line)
                }

                // List - `-`, `*`, `숫자.` 로 시작하거나 들여쓰기된 줄
                isListLine(trimmed) || (current.isNotEmpty() && isListBlock(current) && line.startsWith("  ")) -> {
                    if (current.isEmpty()) startLine = i
                    current.add(line)
                }

                // Table - 두 번째 줄 구분자 lookahead
                trimmed.startsWith("|") -> {
                    if (current.isEmpty()) {
                        val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""
                        startLine = i
                        current.add(line)
                        if (!isTableSeparator(nextLine)) {
                            flush()
                        }
                    } else {
                        current.add(line)
                    }
                }

                // Embed - `![[` 로 시작하는 단독 줄
                trimmed.startsWith("![[") && trimmed.endsWith("]]") -> {
                    flush()
                    startLine = i
                    current.add(line)
                    flush()
                }

                // TextBlock
                else -> {
                    if (current.isEmpty()) startLine = i
                    current.add(line)
                }
            }
            i++
        }
        flush()
        return blocks
    }

    private fun isListLine(trimmed: String): Boolean {
        return trimmed.startsWith("- ") ||
                trimmed.startsWith("* ") ||
                trimmed.matches(Regex("\\d+\\.\\s.*"))
    }

    private fun isListBlock(lines: List<String>): Boolean {
        val first = lines.firstOrNull()?.trimStart() ?: return false
        return isListLine(first)
    }

    private fun isTableSeparator(line: String): Boolean {
        return line.startsWith("|") && line.contains("---")
    }
}
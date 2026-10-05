package com.ninetag.machum.markdown.ui.block

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.ninetag.machum.markdown.service.CalloutLayout
import com.ninetag.machum.markdown.service.MarkdownStyleConfig
import com.ninetag.machum.markdown.state.EditorBlock
import com.ninetag.machum.markdown.state.MarkdownBlockParser
import com.ninetag.machum.markdown.state.MarkdownInteraction
import com.ninetag.machum.markdown.state.MarkdownPreviewOffsetMapping
import com.ninetag.machum.markdown.state.MarkdownRenderContext
import com.ninetag.machum.markdown.state.VisualTaskCheckbox
import com.ninetag.machum.markdown.state.markdownInteractions
import com.ninetag.machum.markdown.state.markdownPresentation
import com.ninetag.machum.markdown.ui.drawBlockDecorations

/** Static content only: its parent document owns scrolling and Embed resolution. */
@Composable
internal fun MarkdownEmbedDocumentPreview(
    markdown: String,
    styleConfig: MarkdownStyleConfig,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { MarkdownBlockParser.parse(markdown) }
    SelectionContainer(modifier) {
        EmbedPreviewBlocks(blocks, styleConfig, textStyle, MarkdownRenderContext.RootText)
    }
}

@Composable
private fun EmbedPreviewBlocks(
    blocks: List<EditorBlock>,
    config: MarkdownStyleConfig,
    textStyle: TextStyle,
    context: MarkdownRenderContext,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is EditorBlock.Text -> EmbedPreviewText(block.textFieldState.text.toString(), config, textStyle, context)
                is EditorBlock.Code -> BasicText(
                    text = block.codeState.text.toString(),
                    style = textStyle.merge(config.codeBlock.text),
                    modifier = Modifier.codeBlockVisualLayout(config),
                )
                is EditorBlock.Callout -> EmbedPreviewCallout(block, config, textStyle)
                is EditorBlock.Table -> MarkdownEmbedTablePreview(block, config, textStyle)
                is EditorBlock.HorizontalRule -> EmbedPreviewText("---", config, textStyle, context)
                is EditorBlock.Embed -> BasicText(
                    text = block.target.substringAfter('|').trim().ifEmpty { block.target.substringBefore('|').trim() },
                    style = textStyle.merge(config.link),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmbedPreviewCallout(block: EditorBlock.Callout, config: MarkdownStyleConfig, textStyle: TextStyle) {
    val decoration = config.calloutDecorationStyle(block.calloutType)
    CalloutVisualLayout(
        type = block.calloutType,
        decoration = decoration,
        textStyle = textStyle,
        title = { titleStyle, titleModifier ->
            BasicText(
                text = calloutDisplayTitle(block.calloutType, block.titleState.text.toString()),
                style = titleStyle,
                modifier = titleModifier,
                maxLines = if (decoration.layout == CalloutLayout.Horizontal) 2 else 1,
                softWrap = decoration.layout == CalloutLayout.Horizontal,
                overflow = TextOverflow.Clip,
            )
        },
        body = { bodyStyle, bodyModifier ->
            EmbedPreviewBlocks(block.bodyBlocks, config, bodyStyle, MarkdownRenderContext.NestedText, bodyModifier)
        },
    )
}

@Composable
internal fun EmbedPreviewText(
    source: String,
    config: MarkdownStyleConfig,
    textStyle: TextStyle,
    context: MarkdownRenderContext,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(source, context, config) { markdownPresentation(source, context, config) }
    val annotated = remember(presentation) {
        buildAnnotatedString {
            append(presentation.visualText)
            presentation.styledRanges.forEach { addStyle(it.style, it.range.first, it.range.last + 1) }
            presentation.paragraphStyledRanges.forEach { addStyle(it.style, it.range.first, it.range.last + 1) }
        }
    }
    val checkboxes = remember(source, context, presentation) {
        if (context == MarkdownRenderContext.TableCell) emptyList() else {
            val mapping = MarkdownPreviewOffsetMapping(presentation.edits)
            markdownInteractions(source).filterIsInstance<MarkdownInteraction.TaskCheckbox>().mapNotNull { task ->
                mapping.mapRange(task.activationRange)?.let { VisualTaskCheckbox(it, task.checked) }
            }
        }
    }
    var layout by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(
        text = annotated,
        style = if (context == MarkdownRenderContext.TableCell) textStyle else normalizedMarkdownTextStyle(textStyle),
        modifier = modifier.fillMaxWidth().drawBehind {
            layout?.let {
                drawBlockDecorations(
                    layout = it,
                    blocks = presentation.blockRanges,
                    config = config,
                    inlineCodeRanges = presentation.inlineCodeRanges,
                    taskCheckboxes = checkboxes,
                )
            }
        },
        onTextLayout = { layout = it },
    )
}

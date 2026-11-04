package com.tridente.nootiz.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.CalendarContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tridente.nootiz.R
import com.tridente.nootiz.data.local.SessionPreferences
import com.tridente.nootiz.data.model.AttachmentMetadata
import com.tridente.nootiz.data.model.Folder
import com.tridente.nootiz.data.model.Note
import com.tridente.nootiz.data.model.NoteReminder
import com.tridente.nootiz.data.model.NoteShareInfo
import com.tridente.nootiz.data.model.User
import com.tridente.nootiz.data.remote.NotizApiClient
import com.tridente.nootiz.ui.theme.ErrorRedDark
import com.tridente.nootiz.ui.theme.IceBlueCard
import com.tridente.nootiz.ui.theme.IceBlueChip
import com.tridente.nootiz.ui.theme.IceBlueSurface
import com.tridente.nootiz.ui.theme.LimeAccent
import com.tridente.nootiz.ui.theme.NavyToast
import com.tridente.nootiz.ui.theme.OffWhiteBackground
import com.tridente.nootiz.ui.theme.PureWhite
import com.tridente.nootiz.ui.theme.RoyalBlue
import com.tridente.nootiz.ui.theme.TextPrimaryNavy
import com.tridente.nootiz.ui.theme.TextSecondarySlate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.util.Calendar
import java.util.UUID

private enum class TextBlockType {
    TITULO,
    SUBTITULO,
    TEXTO
}

private data class NoteTextBlock(
    val id: String = UUID.randomUUID().toString(),
    val type: TextBlockType = TextBlockType.TEXTO,
    val text: String = "",
    val hasCheckbox: Boolean = false,
    val isChecked: Boolean = false
)

private const val LITERAL_ESCAPE_MARKER = "\u200B"

private fun parseBlocksFromMarkdown(rawBody: String): List<NoteTextBlock> {
    if (rawBody.isEmpty()) {
        return listOf(NoteTextBlock(type = TextBlockType.TEXTO, text = ""))
    }
    val blocks = rawBody.lines().map { rawLine ->
        var working = rawLine
        var hasCheck = false
        var checked = false

        val trimmedStart = working.trimStart()
        when {
            trimmedStart.startsWith("- [ ] ") -> {
                hasCheck = true
                checked = false
                working = trimmedStart.removePrefix("- [ ] ")
            }
            trimmedStart.startsWith("- [x] ") || trimmedStart.startsWith("- [X] ") -> {
                hasCheck = true
                checked = true
                working = trimmedStart.substring(6)
            }
        }

        val blockType: TextBlockType
        val extractedText: String
        when {
            working.startsWith("### ") -> {
                blockType = TextBlockType.SUBTITULO
                extractedText = working.removePrefix("### ")
            }
            working.startsWith("## ") -> {
                blockType = TextBlockType.TITULO
                extractedText = working.removePrefix("## ")
            }
            working.startsWith("# ") -> {
                blockType = TextBlockType.TITULO
                extractedText = working.removePrefix("# ")
            }
            else -> {
                blockType = TextBlockType.TEXTO
                extractedText = working
            }
        }

        // Remove zero-width literal escape marker so user sees their exact characters (#, *, etc.)
        val cleanText = extractedText.removePrefix(LITERAL_ESCAPE_MARKER)

        NoteTextBlock(
            type = blockType,
            text = cleanText,
            hasCheckbox = hasCheck,
            isChecked = checked
        )
    }

    return if (blocks.isEmpty()) {
        listOf(NoteTextBlock(type = TextBlockType.TEXTO, text = ""))
    } else {
        blocks
    }
}

private fun needsLiteralEscape(text: String): Boolean {
    val t = text.trimStart()
    return t.startsWith("#") ||
        t.startsWith("- [ ] ") ||
        t.startsWith("- [x] ") ||
        t.startsWith("- [X] ") ||
        t.startsWith("![")
}

private fun serializeBlocksToMarkdown(blocks: List<NoteTextBlock>): String {
    val lines = blocks.flatMap { block ->
        val subLines = block.text.lines()
        subLines.map { subLine ->
            val headingPrefix = when (block.type) {
                TextBlockType.TITULO -> if (subLine.isBlank() && !block.hasCheckbox) "" else "## "
                TextBlockType.SUBTITULO -> if (subLine.isBlank() && !block.hasCheckbox) "" else "### "
                TextBlockType.TEXTO -> ""
            }
            val checkPrefix = if (block.hasCheckbox) {
                if (block.isChecked) "- [x] " else "- [ ] "
            } else {
                ""
            }
            val safeLine = if (needsLiteralEscape(subLine)) {
                "$LITERAL_ESCAPE_MARKER$subLine"
            } else {
                subLine
            }
            "$checkPrefix$headingPrefix$safeLine"
        }
    }
    return lines.joinToString("\n").trimEnd()
}

private suspend fun writeNoteToPdfUri(
    context: Context,
    targetUri: Uri,
    noteTitle: String,
    updatedAtFormatted: String,
    tags: List<String>,
    blocks: List<NoteTextBlock>,
    attachedBitmaps: List<ImageBitmap>
): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        val pdfDoc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val marginH = 44f
        val marginV = 48f
        val contentWidth = (pageWidth - marginH * 2).toInt()

        var pageNumber = 1
        var currentPage = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = currentPage.canvas
        var cursorY = marginV

        fun startNewPage() {
            pdfDoc.finishPage(currentPage)
            pageNumber++
            currentPage = pdfDoc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = currentPage.canvas
            cursorY = marginV
        }

        fun ensureSpace(heightNeeded: Float) {
            if (cursorY + heightNeeded > pageHeight - marginV) {
                startNewPage()
            }
        }

        fun drawWrappedText(text: String, paint: TextPaint, indentX: Float = 0f, spacingAfter: Float = 8f) {
            val width = (contentWidth - indentX.toInt()).coerceAtLeast(100)
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1.15f)
                .setIncludePad(false)
                .build()
            val h = layout.height.toFloat()
            ensureSpace(h + spacingAfter)
            canvas.save()
            canvas.translate(marginH + indentX, cursorY)
            layout.draw(canvas)
            canvas.restore()
            cursorY += h + spacingAfter
        }

        // 1. Note Title
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(18, 38, 65)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        drawWrappedText(noteTitle.ifBlank { "Sin t\u00edtulo" }, titlePaint, spacingAfter = 6f)

        // 2. Date & Tags metadata line
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(84, 102, 122)
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val tagsPart = if (tags.isNotEmpty()) "  \u2022  " + tags.joinToString(" ") { "#$it" } else ""
        drawWrappedText("Modificado: $updatedAtFormatted$tagsPart", metaPaint, spacingAfter = 14f)

        // Divider line
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(220, 230, 242)
            strokeWidth = 1.2f
        }
        ensureSpace(14f)
        canvas.drawLine(marginH, cursorY, pageWidth - marginH, cursorY, dividerPaint)
        cursorY += 16f

        // 3. Body Text Blocks (preserving Titulo, Subtitulo, Texto, and inline Checkboxes)
        blocks.forEach { block ->
            val blockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                when (block.type) {
                    TextBlockType.TITULO -> {
                        color = android.graphics.Color.rgb(18, 38, 65)
                        textSize = 16.5f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    TextBlockType.SUBTITULO -> {
                        color = android.graphics.Color.rgb(54, 81, 158)
                        textSize = 14f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    TextBlockType.TEXTO -> {
                        color = if (block.hasCheckbox && block.isChecked) {
                            android.graphics.Color.rgb(120, 134, 150)
                        } else {
                            android.graphics.Color.rgb(18, 38, 65)
                        }
                        textSize = 12.5f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    }
                }
                if (block.hasCheckbox && block.isChecked) {
                    flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
                }
            }

            val prefix = if (block.hasCheckbox) {
                if (block.isChecked) "[x] " else "[ ] "
            } else {
                ""
            }
            val lineText = prefix + block.text
            if (lineText.isBlank()) {
                cursorY += 10f
            } else {
                drawWrappedText(lineText, blockPaint, spacingAfter = 7f)
            }
        }

        // 4. Attached Images (if any)
        if (attachedBitmaps.isNotEmpty()) {
            cursorY += 12f
            attachedBitmaps.forEach { imgBmp ->
                val androidBmp = imgBmp.asAndroidBitmap()
                if (androidBmp.width > 0 && androidBmp.height > 0) {
                    val maxImgWidth = contentWidth.toFloat()
                    val maxImgHeight = 280f
                    val scale = minOf(maxImgWidth / androidBmp.width, maxImgHeight / androidBmp.height, 1f)
                    val drawW = (androidBmp.width * scale).toInt().coerceAtLeast(1)
                    val drawH = (androidBmp.height * scale).toInt().coerceAtLeast(1)
                    ensureSpace(drawH + 14f)
                    val destRect = android.graphics.Rect(
                        marginH.toInt(),
                        cursorY.toInt(),
                        marginH.toInt() + drawW,
                        cursorY.toInt() + drawH
                    )
                    canvas.drawBitmap(androidBmp, null, destRect, null)
                    cursorY += drawH + 14f
                }
            }
        }

        pdfDoc.finishPage(currentPage)
        context.contentResolver.openOutputStream(targetUri)?.use { out ->
            pdfDoc.writeTo(out)
            out.flush()
        } ?: error("No se pudo abrir el archivo de destino para guardar el PDF.")
        pdfDoc.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    initialNote: Note?,
    initialFolderId: String?,
    currentUser: User?,
    sessionPrefs: SessionPreferences,
    apiClient: NotizApiClient,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentNote by remember { mutableStateOf(initialNote) }
    var title by remember { mutableStateOf(initialNote?.title ?: "") }
    var tags by remember { mutableStateOf(initialNote?.tags ?: emptyList()) }
    var folderId by remember { mutableStateOf(initialNote?.folderId ?: initialFolderId) }

    val textBlocks = remember {
        mutableStateListOf<NoteTextBlock>().apply {
            addAll(parseBlocksFromMarkdown(initialNote?.body ?: ""))
        }
    }
    var focusedBlockIndex by remember { mutableIntStateOf(0) }
    var pendingFocusBlockId by remember { mutableStateOf<String?>(null) }
    var pendingCursorOffset by remember { mutableStateOf<Int?>(null) }

    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var showFolderMenu by remember { mutableStateOf(false) }

    var attachments by remember { mutableStateOf<List<AttachmentMetadata>>(emptyList()) }
    val attachmentBitmaps = remember { mutableStateMapOf<String, ImageBitmap>() }
    var isUploadingImage by remember { mutableStateOf(false) }

    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var toastBannerText by remember { mutableStateOf<String?>(null) }

    // Tag dropdown & dialog state
    var showTagDropdown by remember { mutableStateOf(false) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var newTagInput by remember { mutableStateOf("") }
    var allKnownTags by remember { mutableStateOf<List<String>>(initialNote?.tags ?: emptyList()) }

    // Share Sheet State
    var showShareSheet by remember { mutableStateOf(false) }
    var sharesList by remember { mutableStateOf<List<NoteShareInfo>>(emptyList()) }

    // Calendar Reminder Sheet State
    var showReminderSheet by remember { mutableStateOf(false) }
    var activeReminder by remember {
        mutableStateOf(initialNote?.id?.let { sessionPrefs.getReminderForNote(it) })
    }

    val canEdit = currentNote?.canEdit ?: true
    val isOwner = currentNote?.isOwner ?: true

    fun currentMarkdownBody(): String {
        return serializeBlocksToMarkdown(textBlocks.toList())
    }

    fun showToastBanner(message: String) {
        toastBannerText = message
        scope.launch {
            delay(3200)
            if (toastBannerText == message) {
                toastBannerText = null
            }
        }
    }

    fun loadAttachments(noteId: String) {
        scope.launch {
            apiClient.getAttachments(noteId).onSuccess { list ->
                attachments = list
                list.forEach { meta ->
                    if (!attachmentBitmaps.containsKey(meta.id)) {
                        apiClient.downloadAttachmentBytes(noteId, meta.id).onSuccess { bytes ->
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                attachmentBitmaps[meta.id] = bmp.asImageBitmap()
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        apiClient.getFolders().onSuccess { folders = it }
        apiClient.getNotes().onSuccess { allNotes ->
            allKnownTags = (allNotes.flatMap { it.tags } + tags).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
        }
        currentNote?.id?.let { id ->
            apiClient.getNote(id).onSuccess { fresh ->
                currentNote = fresh
                title = fresh.title
                tags = fresh.tags
                folderId = fresh.folderId
                textBlocks.clear()
                textBlocks.addAll(parseBlocksFromMarkdown(fresh.body))
                if (focusedBlockIndex >= textBlocks.size) {
                    focusedBlockIndex = (textBlocks.size - 1).coerceAtLeast(0)
                }
            }
            loadAttachments(id)
        }
    }

    suspend fun saveNoteInternal(silent: Boolean = false): Note? {
        if (!canEdit) return currentNote
        val serializedBody = currentMarkdownBody()
        val effectiveTitle = title.trim()
        isSaving = true
        errorMessage = null
        val existing = currentNote
        val result = if (existing == null) {
            apiClient.createNote(
                title = effectiveTitle,
                body = serializedBody,
                tags = tags,
                folderId = folderId
            )
        } else {
            apiClient.updateNote(
                noteId = existing.id,
                title = effectiveTitle,
                body = serializedBody,
                tags = tags,
                updateFolder = isOwner,
                folderId = folderId
            )
        }
        isSaving = false
        return result.fold(
            onSuccess = { saved ->
                currentNote = saved
                title = saved.title
                tags = saved.tags
                folderId = saved.folderId
                if (!silent) showToastBanner("Nota guardada")
                saved
            },
            onFailure = { err ->
                errorMessage = err.message ?: "Error al guardar la nota"
                null
            }
        )
    }

    fun exitAndSave() {
        scope.launch {
            val serializedBody = currentMarkdownBody()
            if (canEdit && (title.isNotBlank() || serializedBody.isNotBlank() || currentNote != null)) {
                saveNoteInternal(silent = true)
            }
            onBack()
        }
    }

    // Intercept system back button so it also auto-saves before returning to Dashboard
    BackHandler {
        exitAndSave()
    }

    // Auto-save changes in the background when editing an existing note or after typing content
    val bodySnapshot = currentMarkdownBody()
    LaunchedEffect(title, bodySnapshot, tags, folderId) {
        if (!canEdit) return@LaunchedEffect
        if (currentNote == null && title.isBlank() && bodySnapshot.isBlank()) return@LaunchedEffect
        delay(1200)
        saveNoteInternal(silent = true)
    }

    // Image Picker Launcher for BLOB Upload (POST /api/notes/{id}/attachments)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ensuredNote = currentNote ?: saveNoteInternal(silent = true) ?: return@launch
            isUploadingImage = true
            errorMessage = null
            try {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "image/png"
                val fileName = queryFileName(context, uri) ?: "imagen_${System.currentTimeMillis()}.png"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val uploadRes = apiClient.uploadAttachment(
                        noteId = ensuredNote.id,
                        filename = fileName,
                        contentType = mimeType,
                        bytes = bytes
                    )
                    uploadRes.onSuccess { meta ->
                        attachments = attachments + meta
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            attachmentBitmaps[meta.id] = bmp.asImageBitmap()
                        }
                        showToastBanner("Imagen adjuntada")
                    }.onFailure { err ->
                        errorMessage = err.message ?: "Error al subir imagen"
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "No se pudo leer la imagen"
            } finally {
                isUploadingImage = false
            }
        }
    }

    fun applyBlockType(targetType: TextBlockType) {
        if (!canEdit) return
        if (textBlocks.isEmpty()) {
            val created = NoteTextBlock(type = targetType, text = "")
            textBlocks.add(created)
            focusedBlockIndex = 0
            pendingFocusBlockId = created.id
            return
        }
        val safeIdx = focusedBlockIndex.coerceIn(0, textBlocks.lastIndex)
        val currentBlock = textBlocks[safeIdx]
        if (currentBlock.type != targetType) {
            textBlocks[safeIdx] = currentBlock.copy(type = targetType)
        } else if (currentBlock.text.isNotBlank()) {
            val insertIndex = safeIdx + 1
            val created = NoteTextBlock(type = targetType, text = "")
            textBlocks.add(insertIndex, created)
            focusedBlockIndex = insertIndex
            pendingFocusBlockId = created.id
        }
    }

    fun toggleBlockCheckbox() {
        if (!canEdit) return
        if (textBlocks.isEmpty()) {
            val created = NoteTextBlock(type = TextBlockType.TEXTO, text = "", hasCheckbox = true, isChecked = false)
            textBlocks.add(created)
            focusedBlockIndex = 0
            pendingFocusBlockId = created.id
            return
        }
        val safeIdx = focusedBlockIndex.coerceIn(0, textBlocks.lastIndex)
        val currentBlock = textBlocks[safeIdx]
        textBlocks[safeIdx] = currentBlock.copy(
            hasCheckbox = !currentBlock.hasCheckbox,
            isChecked = if (!currentBlock.hasCheckbox) false else currentBlock.isChecked
        )
    }

    val activeBlock = textBlocks.getOrNull(focusedBlockIndex.coerceAtLeast(0))
    val activeBlockType = activeBlock?.type ?: TextBlockType.TEXTO
    val activeHasCheckbox = activeBlock?.hasCheckbox == true
    val isWholeBodyEmpty = textBlocks.size == 1 && textBlocks[0].text.isEmpty() && !textBlocks[0].hasCheckbox

    var isExportingPdf by remember { mutableStateOf(false) }
    val pdfExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { targetUri: Uri? ->
        if (targetUri == null) return@rememberLauncherForActivityResult
        isExportingPdf = true
        scope.launch {
            val exportTitle = title.trim().ifBlank { "Nota de Nootiz" }
            val exportTime = formatNoteTime(currentNote?.updatedAt?.ifBlank { currentNote?.createdAt ?: "" } ?: "")
            val exportBlocks = textBlocks.toList()
            val exportImages = attachments.mapNotNull { attachmentBitmaps[it.id] }
            val res = writeNoteToPdfUri(
                context = context,
                targetUri = targetUri,
                noteTitle = exportTitle,
                updatedAtFormatted = exportTime,
                tags = tags,
                blocks = exportBlocks,
                attachedBitmaps = exportImages
            )
            isExportingPdf = false
            res.onSuccess {
                showShareSheet = false
                showToastBanner("Nota exportada a PDF")
            }.onFailure { err ->
                errorMessage = err.message ?: "No se pudo exportar la nota a PDF"
            }
        }
    }

    Scaffold(
        containerColor = OffWhiteBackground,
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Dark Navy Confirmation Toast ("Nota compartida" matching Image 2 & Image 4)
                AnimatedVisibility(
                    visible = toastBannerText != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = NavyToast,
                        shape = RoundedCornerShape(16.dp),
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = LimeAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = toastBannerText ?: "",
                                color = PureWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Bottom Formatting Bar: T\u00edtulo, Subt\u00edtulo, Texto, Task Check Icon, IMG
                Surface(
                    color = PureWhite,
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, IceBlueCard, RoundedCornerShape(18.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // T\u00edtulo button
                        EditorToolPill(
                            text = "T\u00edtulo",
                            active = activeBlockType == TextBlockType.TITULO,
                            enabled = canEdit,
                            onClick = { applyBlockType(TextBlockType.TITULO) },
                            modifier = Modifier.weight(1f)
                        )

                        // Subt\u00edtulo button
                        EditorToolPill(
                            text = "Subt\u00edtulo",
                            active = activeBlockType == TextBlockType.SUBTITULO,
                            enabled = canEdit,
                            onClick = { applyBlockType(TextBlockType.SUBTITULO) },
                            modifier = Modifier.weight(1.3f)
                        )

                        // Texto button (normal text - default)
                        EditorToolPill(
                            text = "Texto",
                            active = activeBlockType == TextBlockType.TEXTO,
                            enabled = canEdit,
                            onClick = { applyBlockType(TextBlockType.TEXTO) },
                            modifier = Modifier.weight(1f)
                        )

                        // Inline check toggle button (using custom ic_task_check from reference image)
                        IconButton(
                            onClick = { toggleBlockCheckbox() },
                            enabled = canEdit,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (activeHasCheckbox) RoyalBlue else IceBlueSurface)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_task_check),
                                contentDescription = "Alternar casilla de verificaci\u00f3n en el texto",
                                tint = if (activeHasCheckbox) PureWhite else RoyalBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Image Attachment button (kept as IMG)
                        EditorToolPill(
                            text = "IMG",
                            active = false,
                            enabled = canEdit && !isUploadingImage,
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.weight(0.9f)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Top Navigation & Actions Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { exitAndSave() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(IceBlueSurface)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = TextPrimaryNavy
                    )
                }

                // Folder selector pill (only Owner can change folder_id per 4.2 Permission Matrix)
                Box {
                    val currentFolderName = folders.find { it.id == folderId }?.name ?: "Sin carpeta"
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(IceBlueSurface)
                            .clickable(enabled = isOwner) { showFolderMenu = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = currentFolderName,
                            color = TextPrimaryNavy,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    DropdownMenu(
                        expanded = showFolderMenu,
                        onDismissRequest = { showFolderMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sin carpeta") },
                            onClick = {
                                folderId = null
                                showFolderMenu = false
                            }
                        )
                        folders.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text(folder.name) },
                                onClick = {
                                    folderId = folder.id
                                    showFolderMenu = false
                                }
                            )
                        }
                    }
                }

                // Right Action Icons: Reminder, Share, Delete
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Calendar Reminder Button
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (currentNote == null) {
                                    saveNoteInternal(silent = true)
                                }
                                showReminderSheet = true
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (activeReminder != null) LimeAccent else IceBlueSurface)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = "Recordatorio",
                            tint = TextPrimaryNavy,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Share & Export PDF Button
                    IconButton(
                        onClick = {
                            scope.launch {
                                val ensured = if (canEdit) {
                                    currentNote ?: saveNoteInternal(silent = true)
                                } else {
                                    currentNote
                                }
                                if (ensured != null && isOwner) {
                                    apiClient.getNoteShares(ensured.id).onSuccess {
                                        sharesList = it
                                    }
                                }
                                showShareSheet = true
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(IceBlueSurface)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Compartir o exportar nota",
                            tint = TextPrimaryNavy,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Delete (owner) or Leave shared note (shared user)
                    if (currentNote != null) {
                        IconButton(
                            onClick = {
                                val noteId = currentNote?.id ?: return@IconButton
                                scope.launch {
                                    if (isOwner) {
                                        apiClient.deleteNote(noteId).onSuccess { onBack() }
                                            .onFailure { errorMessage = it.message }
                                    } else {
                                        val myId = currentUser?.id ?: return@launch
                                        apiClient.revokeNoteShare(noteId, myId).onSuccess { onBack() }
                                            .onFailure { errorMessage = it.message }
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(IceBlueSurface)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = if (isOwner) "Eliminar nota" else "Salir de nota compartida",
                                tint = ErrorRedDark,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (errorMessage != null) {
                Surface(
                    color = ErrorRedDark.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = ErrorRedDark,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Title & Time Row (matching Image 1 & Image 3: "Ideas para el lunes" + "10:24")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (title.isEmpty()) {
                        Text(
                            text = "T\u00edtulo",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextSecondarySlate.copy(alpha = 0.45f)
                        )
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { if (canEdit) title = it },
                        readOnly = !canEdit,
                        textStyle = MaterialTheme.typography.headlineMedium.copy(
                            color = TextPrimaryNavy
                        ),
                        cursorBrush = SolidColor(RoyalBlue),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = formatNoteTime(currentNote?.updatedAt?.ifBlank { currentNote?.createdAt ?: "" } ?: ""),
                    color = TextSecondarySlate,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tags Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tags.forEach { tag ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(IceBlueChip)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "#$tag",
                            color = RoyalBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (canEdit) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Quitar etiqueta",
                                tint = RoyalBlue,
                                modifier = Modifier
                                    .size(13.dp)
                                    .clickable { tags = tags - tag }
                            )
                        }
                    }
                }

                if (canEdit) {
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(IceBlueSurface)
                                .clickable {
                                    showTagDropdown = true
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = TextSecondarySlate,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Etiqueta",
                                color = TextSecondarySlate,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Opciones de etiqueta",
                                tint = TextSecondarySlate,
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        val combinedTags = remember(allKnownTags, tags) {
                            (allKnownTags + tags).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
                        }

                        DropdownMenu(
                            expanded = showTagDropdown,
                            onDismissRequest = { showTagDropdown = false },
                            modifier = Modifier.background(PureWhite)
                        ) {
                            if (combinedTags.isNotEmpty()) {
                                combinedTags.forEach { existingTag ->
                                    val isSelected = tags.contains(existingTag)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "#$existingTag",
                                                color = TextPrimaryNavy,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = RoyalBlue,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        onClick = {
                                            tags = if (isSelected) {
                                                tags - existingTag
                                            } else {
                                                tags + existingTag
                                            }
                                            showTagDropdown = false
                                        }
                                    )
                                }
                                HorizontalDivider(color = IceBlueCard)
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Nueva etiqueta...",
                                        color = RoyalBlue,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = RoyalBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showTagDropdown = false
                                    newTagInput = ""
                                    showAddTagDialog = true
                                }
                            )
                        }
                    }
                }
            }

            // Active Reminder Badge if set
            activeReminder?.let { rem ->
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(IceBlueChip)
                        .clickable { showReminderSheet = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = RoyalBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recordatorio: ${rem.dayOfMonth}/${rem.month}/${rem.year} a las %02d:%02d".format(rem.hour, rem.minute),
                        color = TextPrimaryNavy,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Rich WYSIWYG Block Editor (Supports Enter to jump to next line and seamless Backspace to merge into line above)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                textBlocks.forEachIndexed { index, block ->
                    val focusRequester = remember(block.id) { FocusRequester() }
                    val sentinel = "\u2060"
                    val needsSentinel = index > 0
                    val displayPrefix = if (needsSentinel) sentinel else ""
                    val displayText = displayPrefix + block.text

                    var textFieldValue by remember(block.id) {
                        mutableStateOf(
                            TextFieldValue(
                                text = displayText,
                                selection = TextRange(displayText.length)
                            )
                        )
                    }

                    // Keep TextFieldValue synced only when block.text changes externally (e.g. merging or loading), preserving IME composition
                    val currentCleanText = textFieldValue.text.removePrefix(sentinel).replace(sentinel, "")
                    if (currentCleanText != block.text) {
                        val prefixLen = displayPrefix.length
                        val clampedStart = textFieldValue.selection.start.coerceIn(prefixLen, displayText.length)
                        val clampedEnd = textFieldValue.selection.end.coerceIn(prefixLen, displayText.length)
                        textFieldValue = TextFieldValue(
                            text = displayText,
                            selection = TextRange(clampedStart, clampedEnd)
                        )
                    }
                    LaunchedEffect(pendingFocusBlockId, block.id) {
                        if (pendingFocusBlockId == block.id) {
                            val prefixLen = if (index > 0) 1 else 0
                            val targetCursor = ((pendingCursorOffset ?: block.text.length) + prefixLen)
                                .coerceIn(prefixLen, displayText.length)
                            textFieldValue = TextFieldValue(
                                text = displayText,
                                selection = TextRange(targetCursor)
                            )
                            try {
                                focusRequester.requestFocus()
                            } catch (_: Exception) {
                            }
                            pendingFocusBlockId = null
                            pendingCursorOffset = null
                        }
                    }

                    fun mergeIntoPreviousBlock(remainingText: String) {
                        if (index <= 0) return
                        val prevIndex = index - 1
                        val prevBlock = textBlocks[prevIndex]
                        val prevLength = prevBlock.text.length
                        val mergedText = prevBlock.text + remainingText
                        textBlocks[prevIndex] = prevBlock.copy(text = mergedText)
                        textBlocks.removeAt(index)
                        focusedBlockIndex = prevIndex
                        pendingCursorOffset = prevLength
                        pendingFocusBlockId = prevBlock.id
                    }

                    val baseStyle = when (block.type) {
                        TextBlockType.TITULO -> MaterialTheme.typography.titleLarge.copy(
                            color = TextPrimaryNavy,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 26.sp
                        )
                        TextBlockType.SUBTITULO -> MaterialTheme.typography.titleMedium.copy(
                            color = RoyalBlue,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 22.sp
                        )
                        TextBlockType.TEXTO -> MaterialTheme.typography.bodyLarge.copy(
                            color = TextPrimaryNavy,
                            fontSize = 15.sp,
                            lineHeight = 24.sp
                        )
                    }

                    val blockTextStyle = if (block.hasCheckbox && block.isChecked) {
                        baseStyle.copy(
                            color = TextSecondarySlate,
                            textDecoration = TextDecoration.LineThrough
                        )
                    } else {
                        baseStyle
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (block.hasCheckbox) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = canEdit) {
                                        textBlocks[index] = block.copy(isChecked = !block.isChecked)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (block.isChecked) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_task_check),
                                        contentDescription = "Completado",
                                        tint = RoyalBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .border(2.dp, TextSecondarySlate.copy(alpha = 0.65f), CircleShape)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            if (index == 0 && isWholeBodyEmpty) {
                                Text(
                                    text = "Una semana con menos ruido y m\u00e1s foco.\nEmpezar por lo importante, dejar espacio para lo inesperado.",
                                    style = baseStyle.copy(
                                        color = TextSecondarySlate.copy(alpha = 0.45f)
                                    )
                                )
                            }
                            BasicTextField(
                                value = textFieldValue,
                                onValueChange = { newValue ->
                                    if (!canEdit) return@BasicTextField

                                    // 1. Detect Backspace at start of line (sentinel \u2060 deleted on line > 0)
                                    if (needsSentinel && !newValue.text.startsWith(sentinel)) {
                                        val remaining = newValue.text.replace(sentinel, "")
                                        mergeIntoPreviousBlock(remaining)
                                        return@BasicTextField
                                    }

                                    val cleanNewText = newValue.text.removePrefix(sentinel).replace(sentinel, "")

                                    // 2. Detect Enter (\n) to split into a new clean normal Texto line below
                                    if (cleanNewText.contains("\n")) {
                                        val parts = cleanNewText.split("\n", limit = 2)
                                        val firstPart = parts[0]
                                        val secondPart = parts.getOrElse(1) { "" }
                                        textBlocks[index] = block.copy(text = firstPart)
                                        val newDisplay = displayPrefix + firstPart
                                        textFieldValue = TextFieldValue(
                                            text = newDisplay,
                                            selection = TextRange(newDisplay.length)
                                        )
                                        val nextIndex = index + 1
                                        val newBlock = NoteTextBlock(
                                            type = TextBlockType.TEXTO,
                                            text = secondPart,
                                            hasCheckbox = false,
                                            isChecked = false
                                        )
                                        textBlocks.add(nextIndex, newBlock)
                                        focusedBlockIndex = nextIndex
                                        pendingCursorOffset = 0
                                        pendingFocusBlockId = newBlock.id
                                    } else {
                                        textBlocks[index] = block.copy(text = cleanNewText)
                                        val prefixLen = displayPrefix.length
                                        val rebuilt = displayPrefix + cleanNewText
                                        val selStart = newValue.selection.start.coerceIn(prefixLen, rebuilt.length)
                                        val selEnd = newValue.selection.end.coerceIn(prefixLen, rebuilt.length)
                                        textFieldValue = if (newValue.text == rebuilt) {
                                            newValue.copy(selection = TextRange(selStart, selEnd))
                                        } else {
                                            newValue.copy(text = rebuilt, selection = TextRange(selStart, selEnd))
                                        }
                                    }
                                },
                                readOnly = !canEdit,
                                textStyle = blockTextStyle,
                                cursorBrush = SolidColor(RoyalBlue),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { state ->
                                        if (state.isFocused) {
                                            focusedBlockIndex = index
                                        }
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (canEdit &&
                                            index > 0 &&
                                            event.type == KeyEventType.KeyDown &&
                                            event.key == Key.Backspace
                                        ) {
                                            val minPos = if (needsSentinel) 1 else 0
                                            if (textFieldValue.selection.collapsed && textFieldValue.selection.start <= minPos) {
                                                mergeIntoPreviousBlock(block.text)
                                                true
                                            } else {
                                                false
                                            }
                                        } else {
                                            false
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            // Attached BLOB Images Section (GET /api/notes/{id}/attachments)
            if (attachments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Im\u00e1genes adjuntas (${attachments.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimaryNavy
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    attachments.forEach { att ->
                        Surface(
                            color = PureWhite,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, IceBlueCard, RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                val bmp = attachmentBitmaps[att.id]
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = att.filename,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 140.dp, max = 240.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = att.filename,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimaryNavy,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${att.contentType} \u2022 ${att.sizeBytes / 1024} KB",
                                            fontSize = 11.sp,
                                            color = TextSecondarySlate
                                        )
                                    }
                                    if (canEdit) {
                                        IconButton(
                                            onClick = {
                                                val noteId = currentNote?.id ?: return@IconButton
                                                scope.launch {
                                                    apiClient.deleteAttachment(noteId, att.id).onSuccess {
                                                        attachments = attachments.filterNot { it.id == att.id }
                                                        attachmentBitmaps.remove(att.id)
                                                        showToastBanner("Imagen eliminada")
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Eliminar adjunto",
                                                tint = ErrorRedDark
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // Add Tag Dialog
    if (showAddTagDialog) {
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text("Nueva etiqueta", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newTagInput,
                    onValueChange = { newTagInput = it },
                    placeholder = { Text("Ej. rust, dise\u00f1o, ideas") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val normalized = newTagInput.trim().lowercase().removePrefix("#")
                        if (normalized.isNotEmpty()) {
                            if (!tags.contains(normalized)) {
                                tags = tags + normalized
                            }
                            if (!allKnownTags.contains(normalized)) {
                                allKnownTags = (allKnownTags + normalized).sorted()
                            }
                        }
                        showAddTagDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RoyalBlue)
                ) {
                    Text("A\u00f1adir", color = PureWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTagDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // SHARE & EXPORT PDF BOTTOM SHEET
    if (showShareSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var recipientInput by remember { mutableStateOf("") }
        var selectedPermission by remember { mutableStateOf("read") }
        var inviteRoleDropdownExpanded by remember { mutableStateOf(false) }
        var shareError by remember { mutableStateOf<String?>(null) }
        var isSharing by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { showShareSheet = false },
            sheetState = sheetState,
            containerColor = OffWhiteBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text(
                    text = "Compartir nota",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimaryNavy
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Una buena idea merece compartirse",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondarySlate
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Note Preview Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(IceBlueSurface)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = RoyalBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title.ifBlank { "Sin t\u00edtulo" },
                        color = TextPrimaryNavy,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isOwner && currentNote != null) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Email o nombre de usuario",
                        color = TextPrimaryNavy,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = recipientInput,
                        onValueChange = {
                            recipientInput = it
                            shareError = null
                        },
                        placeholder = { Text("lucia@ejemplo.com") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        trailingIcon = {
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(IceBlueSurface)
                                        .clickable { inviteRoleDropdownExpanded = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (selectedPermission == "edit") "Editor" else "Lector",
                                        color = TextPrimaryNavy,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Elegir rol",
                                        tint = TextPrimaryNavy,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = inviteRoleDropdownExpanded,
                                    onDismissRequest = { inviteRoleDropdownExpanded = false },
                                    modifier = Modifier.background(PureWhite)
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Lector",
                                                color = TextPrimaryNavy,
                                                fontWeight = if (selectedPermission == "read") FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            if (selectedPermission == "read") {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = RoyalBlue,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        onClick = {
                                            selectedPermission = "read"
                                            inviteRoleDropdownExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "Editor",
                                                color = TextPrimaryNavy,
                                                fontWeight = if (selectedPermission == "edit") FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        leadingIcon = {
                                            if (selectedPermission == "edit") {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = RoyalBlue,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        onClick = {
                                            selectedPermission = "edit"
                                            inviteRoleDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = PureWhite,
                            unfocusedContainerColor = PureWhite,
                            focusedBorderColor = RoyalBlue,
                            unfocusedBorderColor = IceBlueCard
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (shareError != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = shareError ?: "",
                            color = ErrorRedDark,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Lime Green "Compartir" CTA Button
                    Button(
                        onClick = {
                            val noteId = currentNote?.id ?: return@Button
                            if (recipientInput.isBlank()) {
                                shareError = "Ingresa el correo o username del destinatario."
                                return@Button
                            }
                            isSharing = true
                            shareError = null
                            scope.launch {
                                val res = apiClient.shareNote(
                                    noteId = noteId,
                                    recipient = recipientInput.trim(),
                                    permission = selectedPermission
                                )
                                isSharing = false
                                res.onSuccess { info ->
                                    sharesList = sharesList.filterNot {
                                        it.sharedWithUserId == info.sharedWithUserId
                                    } + info
                                    recipientInput = ""
                                    showToastBanner("Nota compartida")
                                }.onFailure { err ->
                                    shareError = err.message ?: "No se pudo compartir la nota"
                                }
                            }
                        },
                        enabled = !isSharing,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LimeAccent,
                            contentColor = TextPrimaryNavy
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = if (isSharing) "Compartiendo..." else "Compartir",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (sharesList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(color = IceBlueCard)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Personas con acceso",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryNavy
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        sharesList.forEach { share ->
                            var roleMenuExpanded by remember(share.sharedWithUserId) { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = share.username,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimaryNavy
                                    )
                                    Text(
                                        text = share.email,
                                        fontSize = 11.sp,
                                        color = TextSecondarySlate
                                    )
                                }
                                Box {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(IceBlueSurface)
                                            .clickable { roleMenuExpanded = true }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = if (share.permission == "edit") "Editor" else "Lector",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimaryNavy
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Cambiar rol",
                                            tint = TextPrimaryNavy,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = roleMenuExpanded,
                                        onDismissRequest = { roleMenuExpanded = false },
                                        modifier = Modifier.background(PureWhite)
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Lector",
                                                    color = TextPrimaryNavy,
                                                    fontWeight = if (share.permission == "read") FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            leadingIcon = {
                                                if (share.permission == "read") {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = RoyalBlue,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                } else {
                                                    Spacer(modifier = Modifier.size(18.dp))
                                                }
                                            },
                                            onClick = {
                                                roleMenuExpanded = false
                                                if (share.permission != "read") {
                                                    val noteId = currentNote?.id ?: return@DropdownMenuItem
                                                    scope.launch {
                                                        apiClient.shareNote(noteId, share.email, "read").onSuccess { updated ->
                                                            sharesList = sharesList.map {
                                                                if (it.sharedWithUserId == share.sharedWithUserId) updated else it
                                                            }
                                                            showToastBanner("${share.username} ahora es Lector")
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Editor",
                                                    color = TextPrimaryNavy,
                                                    fontWeight = if (share.permission == "edit") FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            leadingIcon = {
                                                if (share.permission == "edit") {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = RoyalBlue,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                } else {
                                                    Spacer(modifier = Modifier.size(18.dp))
                                                }
                                            },
                                            onClick = {
                                                roleMenuExpanded = false
                                                if (share.permission != "edit") {
                                                    val noteId = currentNote?.id ?: return@DropdownMenuItem
                                                    scope.launch {
                                                        apiClient.shareNote(noteId, share.email, "edit").onSuccess { updated ->
                                                            sharesList = sharesList.map {
                                                                if (it.sharedWithUserId == share.sharedWithUserId) updated else it
                                                            }
                                                            showToastBanner("${share.username} ahora es Editor")
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                        HorizontalDivider(color = IceBlueCard)
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "Quitar acceso",
                                                    color = ErrorRedDark,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = ErrorRedDark,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            },
                                            onClick = {
                                                roleMenuExpanded = false
                                                val noteId = currentNote?.id ?: return@DropdownMenuItem
                                                scope.launch {
                                                    apiClient.revokeNoteShare(noteId, share.sharedWithUserId).onSuccess {
                                                        sharesList = sharesList.filterNot {
                                                            it.sharedWithUserId == share.sharedWithUserId
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = IceBlueCard)
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Exportar documento",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimaryNavy
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Export to PDF Button inside the Share Sheet
                Button(
                    onClick = {
                        val safeChars = title.trim().filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }
                        val cleanName = safeChars.trim().replace(' ', '_').ifBlank { "nota_nootiz" }
                        pdfExportLauncher.launch("$cleanName.pdf")
                    },
                    enabled = !isExportingPdf,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RoyalBlue,
                        contentColor = PureWhite
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_pdf_export),
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isExportingPdf) "Generando PDF..." else "Exportar a PDF",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // CALENDAR REMINDER BOTTOM SHEET (matching Image 4: October 2026 Calendar + Alerta local + Google Calendar)
    if (showReminderSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val initialYm = remember {
            activeReminder?.let { YearMonth.of(it.year, it.month) } ?: YearMonth.now()
        }
        var displayedYearMonth by remember { mutableStateOf(initialYm) }
        var selectedDay by remember {
            mutableIntStateOf(activeReminder?.dayOfMonth ?: LocalDate.now().dayOfMonth)
        }
        var selectedHour by remember { mutableIntStateOf(activeReminder?.hour ?: 9) }
        var selectedMinute by remember { mutableIntStateOf(activeReminder?.minute ?: 0) }
        var localAlertChecked by remember { mutableStateOf(activeReminder?.localAlert ?: true) }
        var googleCalendarChecked by remember { mutableStateOf(activeReminder?.googleCalendar ?: false) }

        ModalBottomSheet(
            onDismissRequest = { showReminderSheet = false },
            sheetState = sheetState,
            containerColor = OffWhiteBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp)
            ) {
                // Calendar Card Container
                Surface(
                    color = IceBlueSurface,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Month Header (e.g., "Octubre 2026")
                        val monthNames = listOf(
                            "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
                            "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
                        )
                        val monthLabel = "${monthNames[displayedYearMonth.monthValue - 1]} ${displayedYearMonth.year}"

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    displayedYearMonth = displayedYearMonth.minusMonths(1)
                                    selectedDay = selectedDay.coerceAtMost(displayedYearMonth.lengthOfMonth())
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "Mes anterior",
                                    tint = TextPrimaryNavy
                                )
                            }

                            Text(
                                text = monthLabel,
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimaryNavy
                            )

                            IconButton(
                                onClick = {
                                    displayedYearMonth = displayedYearMonth.plusMonths(1)
                                    selectedDay = selectedDay.coerceAtMost(displayedYearMonth.lengthOfMonth())
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Mes siguiente",
                                    tint = TextPrimaryNavy
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Days of week header (L M X J V S D)
                        val weekDays = listOf("L", "M", "X", "J", "V", "S", "D")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            weekDays.forEach { d ->
                                Text(
                                    text = d,
                                    color = TextSecondarySlate,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Calendar Days Grid
                        val firstDayOfWeek = displayedYearMonth.atDay(1).dayOfWeek.value // 1 (Mon) .. 7 (Sun)
                        val leadingEmpty = firstDayOfWeek - 1
                        val daysInMonth = displayedYearMonth.lengthOfMonth()
                        val totalCells = ((leadingEmpty + daysInMonth + 6) / 7) * 7

                        for (week in 0 until (totalCells / 7)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (dow in 0 until 7) {
                                    val cellIndex = week * 7 + dow
                                    val dayNumber = cellIndex - leadingEmpty + 1
                                    if (dayNumber in 1..daysInMonth) {
                                        val isSelected = dayNumber == selectedDay
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(34.dp)
                                                .padding(horizontal = 2.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) LimeAccent else PureWhite.copy(alpha = 0f))
                                                .clickable { selectedDay = dayNumber },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = dayNumber.toString(),
                                                color = TextPrimaryNavy,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f).height(34.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Time selector row ("09:00", "12:00", "16:00", "19:00")
                        Text(
                            text = "Hora de la alerta: %02d:%02d".format(
                                selectedHour,
                                selectedMinute
                            ),
                            color = TextPrimaryNavy,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            listOf(9 to 0, 10 to 0, 12 to 30, 16 to 0, 19 to 0).forEach { (h, m) ->
                                val timeSelected = selectedHour == h && selectedMinute == m
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (timeSelected) RoyalBlue else PureWhite)
                                        .clickable {
                                            selectedHour = h
                                            selectedMinute = m
                                        }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "%02d:%02d".format(h, m),
                                        color = if (timeSelected) PureWhite else TextPrimaryNavy,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option 1: Alerta local (matching Image 4)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PureWhite)
                        .clickable { localAlertChecked = !localAlertChecked }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Checkbox(
                        checked = localAlertChecked,
                        onCheckedChange = { localAlertChecked = it },
                        colors = CheckboxDefaults.colors(checkedColor = RoyalBlue)
                    )
                    Text(
                        text = "Alerta local",
                        color = TextPrimaryNavy,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Option 2: Google Calendar (matching Image 4)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(PureWhite)
                        .clickable { googleCalendarChecked = !googleCalendarChecked }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Checkbox(
                        checked = googleCalendarChecked,
                        onCheckedChange = { googleCalendarChecked = it },
                        colors = CheckboxDefaults.colors(checkedColor = RoyalBlue)
                    )
                    Text(
                        text = "Google Calendar",
                        color = TextPrimaryNavy,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Lime Green CTA "Guardar recordatorio" (matching Image 4)
                Button(
                    onClick = {
                        val noteId = currentNote?.id ?: "temp"
                        val rem = NoteReminder(
                            noteId = noteId,
                            noteTitle = title.ifBlank { "Ideas para el lunes" },
                            year = displayedYearMonth.year,
                            month = displayedYearMonth.monthValue,
                            dayOfMonth = selectedDay,
                            hour = selectedHour,
                            minute = selectedMinute,
                            localAlert = localAlertChecked,
                            googleCalendar = googleCalendarChecked
                        )
                        sessionPrefs.saveReminder(rem)
                        activeReminder = rem
                        showReminderSheet = false
                        showToastBanner("Recordatorio guardado")

                        if (googleCalendarChecked) {
                            openGoogleCalendarIntent(context, rem, currentMarkdownBody())
                        }
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LimeAccent,
                        contentColor = TextPrimaryNavy
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = "Guardar recordatorio",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorToolPill(
    text: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) RoyalBlue else IceBlueSurface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) PureWhite else TextPrimaryNavy,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

private fun queryFileName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    return cursor.use {
        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (it.moveToFirst() && nameIndex >= 0) {
            it.getString(nameIndex)
        } else {
            null
        }
    }
}

private fun openGoogleCalendarIntent(context: Context, reminder: NoteReminder, noteBody: String) {
    try {
        val beginTime = Calendar.getInstance().apply {
            set(reminder.year, reminder.month - 1, reminder.dayOfMonth, reminder.hour, reminder.minute)
        }
        val endTime = Calendar.getInstance().apply {
            timeInMillis = beginTime.timeInMillis + 60 * 60 * 1000L
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime.timeInMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime.timeInMillis)
            putExtra(CalendarContract.Events.TITLE, reminder.noteTitle)
            putExtra(CalendarContract.Events.DESCRIPTION, noteBody)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

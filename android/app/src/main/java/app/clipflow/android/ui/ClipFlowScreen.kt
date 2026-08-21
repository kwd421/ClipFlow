package app.clipflow.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clipflow.android.model.ClipFlowUiState
import app.clipflow.android.model.ClipRange
import app.clipflow.android.model.DownloadPreferences
import app.clipflow.android.model.DownloadTaskState
import app.clipflow.android.model.MediaCandidate
import app.clipflow.android.model.TaskStatus
import app.clipflow.android.model.formatBytes
import app.clipflow.android.model.formatDuration
import app.clipflow.android.model.parseTimecode
import app.clipflow.android.model.RowKind
import app.clipflow.android.model.SortKey
import app.clipflow.android.ui.theme.clipPalette
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.foundation.layout.offset
import android.content.Intent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipFlowScreen(
    state: ClipFlowUiState,
    onUrlChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
    onToggleSelected: (String) -> Unit,
    onSelectOnly: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onDownloadSelected: () -> Unit,
    onDownloadSegment: (MediaCandidate, ClipRange) -> Unit,
    onExtractAudio: (MediaCandidate, String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (MediaCandidate) -> Unit,
    onRemove: (String) -> Unit,
    onPlay: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onPreferencesChanged: (DownloadPreferences) -> Unit,
    onClipRangeChanged: (ClipRange) -> Unit,
    onOutputTreeChanged: (Uri?) -> Unit,
    onUseDefaultOutput: () -> Unit,
    onCookieFileChanged: (Uri?) -> Unit,
    onClearCookieFile: () -> Unit,
    onErrorDismissed: () -> Unit,
    onToggleSort: () -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onTogglePlaylist: (String) -> Unit,
    onDownloadPlaylist: (String) -> Unit,
    onDismissUpdate: () -> Unit,
) {
    var showOptions by remember { mutableStateOf(false) }
    var showClipRange by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirmation by remember { mutableStateOf(false) }
    var deleteCandidateId by remember { mutableStateOf<String?>(null) }
    var segmentExtractCandidate by remember { mutableStateOf<MediaCandidate?>(null) }
    var actionCandidate by remember { mutableStateOf<MediaCandidate?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val colors = clipPalette()
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        onOutputTreeChanged(it)
    }
    val cookiePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        onCookieFileChanged(it)
    }

    BackHandler(enabled = searchExpanded) {
        searchExpanded = false
        searchQuery = ""
    }
    BackHandler(enabled = selectionMode && !searchExpanded) {
        selectionMode = false
    }

    if (state.error.isNotBlank()) {
        AlertDialog(
            onDismissRequest = onErrorDismissed,
            confirmButton = { TextButton(onClick = onErrorDismissed) { Text("확인") } },
            title = { Text("확인 필요") },
            text = { Text(state.error) },
        )
    }
    val selectedOutputIds = state.selectedIds.filter { candidateId ->
        state.tasks[candidateId]?.let { task ->
            task.status == TaskStatus.Completed && task.outputUri.isNotBlank()
        } == true
    }
    if (showDeleteSelectedConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirmation = false },
            title = { Text("선택한 파일 삭제", color = colors.ink, fontWeight = FontWeight.Bold) },
            text = { Text("다운로드된 파일 ${selectedOutputIds.size}개를 삭제하시겠습니까?") },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteSelectedConfirmation = false },
                    border = BorderStroke(1.5.dp, colors.strongBorder),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("취소", color = colors.ink) }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedOutputIds.forEach(onDeleteFile)
                        showDeleteSelectedConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("삭제") }
            },
            containerColor = colors.raised,
        )
    }
    deleteCandidateId?.let { candidateId ->
        AlertDialog(
            onDismissRequest = { deleteCandidateId = null },
            title = { Text("파일 삭제", color = colors.ink, fontWeight = FontWeight.Bold) },
            text = { Text("다운로드된 파일을 삭제하시겠습니까?") },
            dismissButton = {
                OutlinedButton(
                    onClick = { deleteCandidateId = null },
                    border = BorderStroke(1.5.dp, colors.strongBorder),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("취소", color = colors.ink) }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFile(candidateId)
                        deleteCandidateId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("삭제") }
            },
            containerColor = colors.raised,
        )
    }
    if (showOptions) {
        PreferencesSheet(
            value = state.preferences,
            onDismiss = { showOptions = false },
            onApply = {
                onPreferencesChanged(it)
                showOptions = false
            },
        )
    }
    if (showClipRange) {
        ClipRangeDialog(
            title = "구간선택",
            value = state.clipRange,
            onDismiss = { showClipRange = false },
            onApply = {
                onClipRangeChanged(it)
                showClipRange = false
            },
        )
    }
    segmentExtractCandidate?.let { candidate ->
        ClipRangeDialog(
            title = "구간 추출",
            value = ClipRange(),
            onDismiss = { segmentExtractCandidate = null },
            onApply = {
                onDownloadSegment(candidate, it)
                segmentExtractCandidate = null
            },
        )
    }
    actionCandidate?.let { candidate ->
        val task = state.tasks[candidate.id]
        CandidateActionsDialog(
            candidate = candidate,
            selected = selectionMode && candidate.id in state.selectedIds,
            status = task?.status ?: TaskStatus.Ready,
            hasOutput = task?.outputUri?.isNotBlank() == true,
            onDismiss = { actionCandidate = null },
            onSelect = {
                selectionMode = true
                onSelectOnly(candidate.id)
                actionCandidate = null
            },
            onUnselect = {
                onToggleSelected(candidate.id)
                actionCandidate = null
            },
            onRemove = {
                onRemove(candidate.id)
                actionCandidate = null
            },
            onPlay = {
                onPlay(candidate.id)
                actionCandidate = null
            },
            onOpenFolder = {
                onOpenFolder(candidate.id)
                actionCandidate = null
            },
            onDeleteFile = {
                deleteCandidateId = candidate.id
                actionCandidate = null
            },
            onSegmentExtract = {
                segmentExtractCandidate = candidate
                actionCandidate = null
            },
            onExtractAudio = { format ->
                onExtractAudio(candidate, format)
                actionCandidate = null
            },
            onOpenSource = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(candidate.sourceUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                actionCandidate = null
            },
        )
    }

    Scaffold(
        containerColor = colors.canvas,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
        ) {
            InputPanel(
                state = state,
                onUrlChanged = onUrlChanged,
                onAnalyze = onAnalyze,
                onDownloadSelected = onDownloadSelected,
                onPaste = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(onUrlChanged)
                },
                onClear = { onUrlChanged("") },
                onClipRange = { showClipRange = true },
                onFolder = { folderPicker.launch(null) },
                onDefaultFolder = onUseDefaultOutput,
                onCookieFile = {
                    cookiePicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
                },
                onClearCookieFile = onClearCookieFile,
            )
            if (state.updateMessage.isNotBlank()) {
                AlertDialog(
                    onDismissRequest = onDismissUpdate,
                    title = { Text("업데이트", color = colors.ink, fontWeight = FontWeight.Bold) },
                    text = { Text(state.updateMessage, color = colors.ink) },
                    confirmButton = {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(state.updateUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            onDismissUpdate()
                        }) { Text("열기", color = colors.accent) }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissUpdate) { Text("나중에", color = colors.ink) }
                    },
                    containerColor = colors.raised,
                )
            }
            ListToolbar(
                state = state,
                selectionMode = selectionMode,
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                onToggleSelectAll = onToggleSelectAll,
                onExitSelectionMode = { selectionMode = false },
                onRemoveSelected = {
                    state.selectedIds.toList().forEach(onRemove)
                },
                canDeleteSelectedFiles = selectedOutputIds.isNotEmpty(),
                onDeleteSelectedFiles = { showDeleteSelectedConfirmation = true },
                onSearchExpanded = { searchExpanded = it },
                onSearchQuery = { searchQuery = it },
                onSort = onToggleSort,
                onToggleDarkTheme = { onToggleDarkTheme(!state.darkTheme) },
                onOptions = { showOptions = true },
            )

            val rows = state.candidates
                .filter { searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = MediaCandidate::id) { candidate ->
                        CandidateCard(
                            candidate = candidate,
                            selectionMode = selectionMode,
                            selected = candidate.id in state.selectedIds,
                            task = state.tasks[candidate.id],
                            onToggleSelected = { onToggleSelected(candidate.id) },
                            onPause = { onPause(candidate.id) },
                            onResume = { onResume(candidate) },
                            onRemove = { onRemove(candidate.id) },
                            onDeleteFile = { deleteCandidateId = candidate.id },
                            onLongPress = { actionCandidate = candidate },
                            onTogglePlaylist = { onTogglePlaylist(candidate.id) },
                            onDownloadPlaylist = { onDownloadPlaylist(candidate.id) },
                        )
                    }
                }
                if (state.analyzing) {
                    // Desktop-style analyzing card: track + rotating dash ring (not a dead border).
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(72.dp),
                    ) {
                        Surface(
                            color = colors.raised,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.4.dp, colors.strongBorder.copy(alpha = 0.28f)),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    state.analysisMessage.ifBlank { "영상 정보를 확인하는 중" },
                                    color = colors.muted,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        CardProgressRing(
                            progress = 0,
                            trackColor = colors.accentTint,
                            progressColor = colors.accent,
                            indeterminate = true,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Icon-only affordance sized to the 48dp touch target / 22-24dp glyph range, with a
 * tap-and-hold tooltip (Material3 [TooltipBox] shows on long-press for touch input).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    val colors = clipPalette()
    val resolvedTint = tint ?: colors.ink
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
            Icon(icon, contentDescription = label, tint = if (enabled) resolvedTint else colors.muted, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun InputPanel(
    state: ClipFlowUiState,
    onUrlChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
    onDownloadSelected: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onClipRange: () -> Unit,
    onFolder: () -> Unit,
    onDefaultFolder: () -> Unit,
    onCookieFile: () -> Unit,
    onClearCookieFile: () -> Unit,
) {
    val colors = clipPalette()
    val keyboard = LocalSoftwareKeyboardController.current
    val hasCandidates = state.candidates.isNotEmpty()
    val hasSelection = state.selectedIds.isNotEmpty()
    val currentUrl = state.url.trim()
    val analyzedUrls = state.candidates.map { it.sourceUrl }.toSet()
    val inputUrls = currentUrl.lineSequence().map { it.trim() }.filter { it.startsWith("http") }.toList()
    val showingCurrentAnalysis = hasCandidates && inputUrls.isNotEmpty() && inputUrls.all { it in analyzedUrls }
    val primaryEnabled = !state.analyzing && if (showingCurrentAnalysis) hasSelection else currentUrl.isNotBlank()
    val primaryAction: () -> Unit = {
        if (showingCurrentAnalysis) {
            if (hasSelection) onDownloadSelected()
        } else {
            onAnalyze()
        }
    }
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChanged,
                placeholder = { Text("URL · 여러 개는 줄바꿈") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                trailingIcon = {
                    if (state.url.isBlank()) {
                        TooltipIconButton(Icons.Default.ContentPaste, "붙여넣기", onClick = onPaste)
                    } else {
                        TooltipIconButton(Icons.Default.Clear, "URL 지우기", onClick = onClear)
                    }
                },
                maxLines = 4,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    primaryAction()
                    keyboard?.hide()
                }),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    primaryAction()
                    keyboard?.hide()
                },
                enabled = primaryEnabled,
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.ink),
                modifier = Modifier.size(56.dp),
            ) {
                if (state.analyzing) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = if (showingCurrentAnalysis) "다운로드" else "분석",
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onClipRange,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.5.dp, colors.strongBorder),
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.clipRange.isSet) "구간 적용됨" else "구간선택", color = colors.ink)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.5.dp, colors.strongBorder),
                color = colors.raised,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(start = 12.dp)
                        .clickable(onClick = onFolder),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.outputLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.outputTreeUri.isNotBlank()) {
                        TooltipIconButton(Icons.Default.Clear, "기본 저장 위치", iconSize = 18.dp, onClick = onDefaultFolder)
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.5.dp, if (state.cookieEnabled) colors.accent else colors.strongBorder),
            color = colors.raised,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = 12.dp)
                    .clickable(onClick = onCookieFile),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Cookie,
                    contentDescription = null,
                    tint = if (state.cookieEnabled) colors.accent else colors.ink,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    state.cookieLabel,
                    color = if (state.cookieEnabled) colors.ink else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.cookieEnabled) {
                    TooltipIconButton(
                        Icons.Default.Clear,
                        "쿠키 사용 해제",
                        iconSize = 18.dp,
                        onClick = onClearCookieFile,
                    )
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ListToolbar(
    state: ClipFlowUiState,
    selectionMode: Boolean,
    searchExpanded: Boolean,
    searchQuery: String,
    onToggleSelectAll: () -> Unit,
    onExitSelectionMode: () -> Unit,
    onRemoveSelected: () -> Unit,
    canDeleteSelectedFiles: Boolean,
    onDeleteSelectedFiles: () -> Unit,
    onSearchExpanded: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onSort: () -> Unit,
    onToggleDarkTheme: () -> Unit,
    onOptions: () -> Unit,
) {
    val colors = clipPalette()
    val keyboard = LocalSoftwareKeyboardController.current
    val sortLabel = when {
        state.sort.key == SortKey.Latest && state.sort.descending -> "최신순"
        state.sort.key == SortKey.Latest && !state.sort.descending -> "오래된순"
        state.sort.key == SortKey.Name && !state.sort.descending -> "이름순"
        else -> "이름역순"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            TooltipIconButton(
                icon = Icons.Default.Clear,
                label = "선택 모드 종료",
                onClick = onExitSelectionMode,
            )
            TooltipIconButton(
                icon = if (state.candidates.isNotEmpty() && state.selectedIds.size == state.candidates.size) {
                    Icons.Default.CheckBox
                } else Icons.Default.CheckBoxOutlineBlank,
                label = "전체 선택",
                tint = if (state.selectedIds.isNotEmpty()) colors.accent else colors.ink,
                onClick = onToggleSelectAll,
            )
            TooltipIconButton(
                icon = Icons.Default.Clear,
                label = "선택 항목 목록에서 제거",
                enabled = state.selectedIds.isNotEmpty(),
                onClick = onRemoveSelected,
            )
            TooltipIconButton(
                icon = Icons.Default.Delete,
                label = "선택 항목 파일 삭제",
                tint = colors.danger,
                enabled = canDeleteSelectedFiles,
                onClick = onDeleteSelectedFiles,
            )
        }
        Spacer(Modifier.weight(1f))
        if (searchExpanded) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQuery,
                singleLine = true,
                placeholder = { Text("검색") },
                trailingIcon = {
                    TooltipIconButton(
                        Icons.Default.Clear,
                        "검색 닫기",
                        iconSize = 18.dp,
                        onClick = { onSearchQuery(""); onSearchExpanded(false) },
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .width(180.dp)
                    .height(50.dp),
            )
        } else {
            TooltipIconButton(Icons.Default.Search, "검색", onClick = { onSearchExpanded(true) })
            TextButton(onClick = onSort) {
                Icon(Icons.Default.SwapVert, contentDescription = null)
                Text(sortLabel, color = colors.ink)
            }
            TooltipIconButton(
                Icons.Default.DarkMode,
                if (state.darkTheme) "라이트 테마" else "다크 테마",
                onClick = onToggleDarkTheme,
            )
            TooltipIconButton(Icons.Default.Settings, "옵션", onClick = onOptions)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateCard(
    candidate: MediaCandidate,
    selectionMode: Boolean,
    selected: Boolean,
    task: DownloadTaskState?,
    onToggleSelected: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onDeleteFile: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePlaylist: () -> Unit,
    onDownloadPlaylist: () -> Unit,
) {
    val colors = clipPalette()
    val status = task?.status ?: TaskStatus.Ready
    val active = status in setOf(TaskStatus.Queued, TaskStatus.Downloading, TaskStatus.Finishing)
    val progress = (task?.progress ?: 0).coerceIn(0, 100)
    val detail = task?.detail.orEmpty()
    // Desktop clipflow_rows: fixed ~1.4dp stroke; progress is a partial perimeter path.
    val trackColor = when (status) {
        TaskStatus.Failed -> colors.danger
        TaskStatus.Completed -> colors.strongBorder
        TaskStatus.Paused -> colors.muted
        TaskStatus.Downloading, TaskStatus.Queued, TaskStatus.Finishing -> colors.accentTint
        else -> colors.strongBorder
    }
    val progressColor = when (status) {
        TaskStatus.Finishing -> colors.accent
        TaskStatus.Downloading, TaskStatus.Queued -> colors.accent
        TaskStatus.Failed -> colors.danger
        TaskStatus.Completed -> colors.strongBorder
        else -> colors.accent
    }
    val ringProgress = when (status) {
        TaskStatus.Completed, TaskStatus.Failed -> 100
        TaskStatus.Downloading, TaskStatus.Queued, TaskStatus.Finishing, TaskStatus.Paused -> progress
        else -> 0
    }
    // Desktop: indeterminate dash ring while analyzing/starting/finishing;
    // determinate perimeter fill while downloading; full graphite when completed.
    val showRing = status != TaskStatus.Ready || active
    val indeterminateRing = status == TaskStatus.Queued || status == TaskStatus.Finishing

    val statusLine = when (status) {
        TaskStatus.Failed -> detail.takeIf { it.isNotBlank() && it != "다운로드 실패" } ?: "실패"
        TaskStatus.Paused -> "일시정지"
        TaskStatus.Finishing -> detail.ifBlank { "마무리 중" }
        TaskStatus.Downloading -> when {
            detail.isNotBlank() && detail != "대기 중" -> detail
            progress > 0 -> "$progress%"
            else -> "다운로드 중"
        }
        TaskStatus.Queued -> detail.ifBlank { "대기 중" }
        else -> ""
    }
    val showStatusLine = statusLine.isNotBlank()
    val context = LocalContext.current
    val thumbRequest = remember(candidate.thumbnailUrl, candidate.sourceUrl) {
        val thumb = candidate.thumbnailUrl.trim()
        if (thumb.isBlank()) {
            null
        } else {
            val referer = runCatching {
                val uri = Uri.parse(candidate.sourceUrl)
                "${uri.scheme}://${uri.host}/"
            }.getOrDefault("https://anilife.app/")
            ImageRequest.Builder(context)
                .data(thumb)
                .crossfade(true)
                .httpHeaders(
                    NetworkHeaders.Builder()
                        .set(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36",
                        )
                        .set("Referer", referer)
                        .build(),
                )
                .build()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelected() },
                onLongClick = onLongPress,
            ),
    ) {
        Surface(
            color = colors.raised,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(
                1.4.dp,
                if (showRing) colors.strongBorder.copy(alpha = 0.28f) else colors.strongBorder,
            ),
            shadowElevation = if (active) 2.dp else 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelected() },
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                // Desktop ThumbnailPlaceholder: fixed 96×54 — never stretch with status line.
                AsyncImage(
                    model = thumbRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 96.dp, height = 54.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.thumbPlaceholder),
                )
                Spacer(Modifier.width(10.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = candidate.title,
                        color = colors.ink,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                velocity = 28.dp,
                            ),
                    )
                    // Desktop meta: clock + duration, download icon + size.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (candidate.kind == RowKind.Playlist) {
                            Text(
                                "재생목록 · ${candidate.itemCount}개",
                                color = colors.muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        } else if (candidate.kind == RowKind.PlaylistChild) {
                            Text(
                                "항목 ${candidate.playlistIndex + 1}",
                                color = colors.muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        MetaIconText(
                            icon = Icons.Default.AccessTime,
                            text = formatDuration(candidate.durationSeconds),
                            color = colors.muted,
                        )
                        MetaIconText(
                            icon = Icons.Default.Download,
                            text = formatBytes(candidate.sizeBytes),
                            color = colors.muted,
                        )
                    }
                    if (showStatusLine) {
                        Text(
                            statusLine,
                            color = if (status == TaskStatus.Failed) colors.danger else colors.accent,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (candidate.kind == RowKind.Playlist) {
                    TooltipIconButton(
                        if (candidate.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (candidate.expanded) "접기" else "펼치기",
                        iconSize = 20.dp,
                        onClick = onTogglePlaylist,
                    )
                    TooltipIconButton(
                        Icons.Default.PlaylistPlay,
                        "재생목록 일괄 다운로드",
                        iconSize = 20.dp,
                        onClick = onDownloadPlaylist,
                    )
                }
                // Desktop clipflow_rows._refresh_actions:
                // downloading → pause only; paused/failed → resume + remove/delete.
                when (status) {
                    TaskStatus.Queued, TaskStatus.Downloading, TaskStatus.Finishing -> {
                        TooltipIconButton(Icons.Default.Pause, "일시정지", iconSize = 20.dp, onClick = onPause)
                    }
                    TaskStatus.Paused, TaskStatus.Failed -> {
                        TooltipIconButton(Icons.Default.PlayArrow, "재시작", iconSize = 20.dp, onClick = onResume)
                        TooltipIconButton(
                            Icons.Default.Delete,
                            if (task?.outputUri?.isNotBlank() == true) "파일 삭제" else "다운로드 삭제",
                            tint = colors.danger,
                            iconSize = 20.dp,
                            onClick = if (task?.outputUri?.isNotBlank() == true) onDeleteFile else onRemove,
                        )
                    }
                    else -> Unit
                }
            }
        }
        if (showRing) {
            CardProgressRing(
                progress = ringProgress,
                trackColor = trackColor,
                progressColor = progressColor,
                indeterminate = indeterminateRing,
                rainbow = status == TaskStatus.Finishing,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * Desktop clipflow_rows ring (paintEvent + _ring_segment_path):
 * - phase 0 = top edge left→right, 1 = right, 2 = bottom, 3 = left
 * - download fill: start 3.82, length 4*p/100 (near top-left, clockwise)
 * - analysis/starting: rotating dash length 0.46
 * Stroke fixed 1.4dp — progress never changes thickness.
 */
@Composable
private fun CardProgressRing(
    progress: Int,
    trackColor: Color,
    progressColor: Color,
    indeterminate: Boolean = false,
    rainbow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val indicatorPath = remember { Path() }
    val rainbowBrush = remember { Brush.sweepGradient(finishingRingColors) }
    val spinPhase = if (indeterminate) {
        val infinite = rememberInfiniteTransition(label = "ring-spin")
        val animatedPhase by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ring-phase",
        )
        animatedPhase
    } else {
        0f
    }
    Canvas(modifier = modifier) {
        val stroke = 1.4.dp.toPx()
        val inset = stroke / 2f
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        val radius = minOf(8.dp.toPx(), (right - left) / 2f, (bottom - top) / 2f)
        val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(radius, radius),
            style = style,
        )
        if (indeterminate) {
            if (rainbow) {
                finishingRingColors.forEachIndexed { index, color ->
                    drawCircle(
                        color = color,
                        radius = stroke * (1.9f - index * 0.14f),
                        center = desktopRingPoint(
                            left,
                            top,
                            right,
                            bottom,
                            radius,
                            spinPhase - index * 0.075f,
                        ),
                    )
                }
            } else {
                drawCircle(
                    color = progressColor,
                    radius = stroke * 1.8f,
                    center = desktopRingPoint(left, top, right, bottom, radius, spinPhase),
                )
            }
            return@Canvas
        }
        if (rainbow) {
            drawRoundRect(
                brush = rainbowBrush,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(radius, radius),
                style = style,
            )
            return@Canvas
        }
        val bounded = progress.coerceIn(0, 100)
        if (bounded <= 0) return@Canvas
        if (bounded >= 100) {
            drawRoundRect(
                color = progressColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(radius, radius),
                style = style,
            )
            return@Canvas
        }
        // Desktop: partial = _ring_segment_path(rect, 3.82, 4.0 * progress / 100.0)
        val partial = desktopRingSegmentPath(
            indicatorPath,
            left, top, right, bottom, radius,
            startPhase = 3.82f,
            lengthPhase = 4f * bounded / 100f,
        )
        drawPath(partial, color = progressColor, style = style)
    }
}

private val finishingRingColors = listOf(
    Color(0xFFFF4D6D),
    Color(0xFFFFA62B),
    Color(0xFFFFE66D),
    Color(0xFF43D17B),
    Color(0xFF29B6F6),
    Color(0xFF7C6CF2),
)

/** Port of clipflow_rows._ring_segment_path / _analysis_ring_point (phase 0..4). */
private fun desktopRingSegmentPath(
    path: Path,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
    startPhase: Float,
    lengthPhase: Float,
): Path {
    path.reset()
    val length = lengthPhase.coerceIn(0f, 4f)
    if (length <= 0f) return path
    val start = ((startPhase % 4f) + 4f) % 4f
    val first = desktopRingPoint(left, top, right, bottom, radius, start)
    path.moveTo(first.x, first.y)
    val steps = maxOf(2, kotlin.math.ceil(length * 32f).toInt())
    for (i in 1..steps) {
        val phase = start + length * (i / steps.toFloat())
        val point = desktopRingPoint(left, top, right, bottom, radius, phase)
        path.lineTo(point.x, point.y)
    }
    return path
}

private fun desktopRingPoint(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
    phaseIn: Float,
): Offset {
    val straightFraction = 0.82f
    val cornerSpan = 1f - straightFraction
    val phase = ((phaseIn % 4f) + 4f) % 4f
    val side = phase.toInt()
    val t = phase - side
    val cornerT = if (t < straightFraction) 0f else (t - straightFraction) / cornerSpan
    return when (side) {
        0 -> if (t < straightFraction) {
            Offset((left + radius) + (right - 2 * radius - left) * (t / straightFraction), top)
        } else {
            val angle = Math.toRadians(-90.0 + 90.0 * cornerT)
            Offset(
                (right - radius + radius * kotlin.math.cos(angle)).toFloat(),
                (top + radius + radius * kotlin.math.sin(angle)).toFloat(),
            )
        }
        1 -> if (t < straightFraction) {
            Offset(right, (top + radius) + (bottom - 2 * radius - top) * (t / straightFraction))
        } else {
            val angle = Math.toRadians(90.0 * cornerT)
            Offset(
                (right - radius + radius * kotlin.math.cos(angle)).toFloat(),
                (bottom - radius + radius * kotlin.math.sin(angle)).toFloat(),
            )
        }
        2 -> if (t < straightFraction) {
            Offset((right - radius) - (right - 2 * radius - left) * (t / straightFraction), bottom)
        } else {
            val angle = Math.toRadians(90.0 + 90.0 * cornerT)
            Offset(
                (left + radius + radius * kotlin.math.cos(angle)).toFloat(),
                (bottom - radius + radius * kotlin.math.sin(angle)).toFloat(),
            )
        }
        else -> if (t < straightFraction) {
            Offset(left, (bottom - radius) - (bottom - 2 * radius - top) * (t / straightFraction))
        } else {
            val angle = Math.toRadians(180.0 + 90.0 * cornerT)
            Offset(
                (left + radius + radius * kotlin.math.cos(angle)).toFloat(),
                (top + radius + radius * kotlin.math.sin(angle)).toFloat(),
            )
        }
    }
}

@Composable
private fun MetaIconText(
    icon: ImageVector,
    text: String,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text,
            color = color,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CandidateActionsDialog(
    candidate: MediaCandidate,
    selected: Boolean,
    status: TaskStatus,
    hasOutput: Boolean,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onUnselect: () -> Unit,
    onRemove: () -> Unit,
    onPlay: () -> Unit,
    onOpenFolder: () -> Unit,
    onDeleteFile: () -> Unit,
    onSegmentExtract: () -> Unit,
    onExtractAudio: (String) -> Unit,
    onOpenSource: () -> Unit,
) {
    val colors = clipPalette()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                candidate.title,
                color = colors.ink,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CandidateActionButton(
                    icon = if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    label = if (selected) "선택 해제" else "선택",
                    onClick = if (selected) onUnselect else onSelect,
                )
                CandidateActionButton(
                    icon = Icons.Default.OpenInBrowser,
                    label = "사이트 열기",
                    onClick = onOpenSource,
                )
                when (status) {
                    TaskStatus.Ready -> {
                        CandidateActionButton(Icons.Default.Clear, "목록에서 삭제", onClick = onRemove)
                    }
                    TaskStatus.Completed -> {
                        CandidateActionButton(Icons.Default.PlayArrow, "재생", onClick = onPlay)
                        CandidateActionButton(Icons.Default.Folder, "폴더 열기", onClick = onOpenFolder)
                        CandidateActionButton(Icons.Default.Clear, "목록에서 삭제", onClick = onRemove)
                        if (hasOutput) {
                            CandidateActionButton(
                                Icons.Default.Delete,
                                "파일 삭제",
                                tint = colors.danger,
                                onClick = onDeleteFile,
                            )
                        }
                        CandidateActionButton(Icons.Default.Timer, "구간 추출", onClick = onSegmentExtract)
                        CandidateActionButton(Icons.Default.MusicNote, "음원 추출 (WAV)") {
                            onExtractAudio("WAV")
                        }
                        CandidateActionButton(Icons.Default.MusicNote, "음원 추출 (MP3)") {
                            onExtractAudio("MP3")
                        }
                    }
                    else -> {
                        CandidateActionButton(Icons.Default.Clear, "목록에서 삭제", onClick = onRemove)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = colors.ink) }
        },
        containerColor = colors.raised,
    )
}

@Composable
private fun CandidateActionButton(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val colors = clipPalette()
    val resolvedTint = tint ?: colors.ink
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.5.dp, colors.strongBorder),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = resolvedTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = resolvedTint, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PreferencesSheet(
    value: DownloadPreferences,
    onDismiss: () -> Unit,
    onApply: (DownloadPreferences) -> Unit,
) {
    val colors = clipPalette()
    var draft by remember(value) { mutableStateOf(value) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.raised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("다운로드 옵션", color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            OptionMenu(
                "화질",
                draft.quality,
                listOf("자동", "4320p", "2160p", "1440p", "1080p", "720p", "480p", "360p"),
            ) { draft = draft.copy(quality = it) }
            OptionMenu("포맷", "MP4", listOf("MP4")) {}
            OptionMenu("코덱", draft.codec, listOf("자동", "H264", "H265", "AV1", "VP9")) {
                draft = draft.copy(codec = it)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HDR", color = colors.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = draft.hdrEnabled, onCheckedChange = { draft = draft.copy(hdrEnabled = it) })
            }
            OptionMenu(
                "병렬",
                draft.concurrency.toString(),
                listOf("4", "8", "12", "16"),
            ) {
                draft = draft.copy(concurrency = it.toInt())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { draft = DownloadPreferences() },
                    border = BorderStroke(1.5.dp, colors.strongBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("초기화", color = colors.ink) }
                Button(
                    onClick = { onApply(draft) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.ink),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("적용") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OptionMenu(label: String, value: String, options: List<String>, onSelected: (String) -> Unit) {
    val colors = clipPalette()
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.ink, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp))
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                border = BorderStroke(1.5.dp, colors.strongBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(value, color = colors.ink, fontWeight = FontWeight.Bold) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(0.65f)) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold) },
                        onClick = { onSelected(option); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipRangeDialog(title: String, value: ClipRange, onDismiss: () -> Unit, onApply: (ClipRange) -> Unit) {
    val colors = clipPalette()
    var start by remember { mutableStateOf(value.startSeconds?.toString().orEmpty()) }
    var end by remember { mutableStateOf(value.endSeconds?.toString().orEmpty()) }
    var exact by remember { mutableStateOf(value.exact) }
    var validation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeInput("시작시간", start) { start = it }
                TimeInput("종료시간", end) { end = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("컷 방식", color = colors.ink, fontWeight = FontWeight.Bold, modifier = Modifier.width(82.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (exact) {
                            OutlinedButton(
                                onClick = { exact = false },
                                border = BorderStroke(1.5.dp, colors.strongBorder),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("빠른 컷", color = colors.ink) }
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = colors.ink),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("정확 컷") }
                        } else {
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = colors.ink),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("빠른 컷") }
                            OutlinedButton(
                                onClick = { exact = true },
                                border = BorderStroke(1.5.dp, colors.strongBorder),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("정확 컷", color = colors.ink) }
                        }
                    }
                }
                if (validation.isNotBlank()) Text(validation, color = colors.danger, fontSize = 12.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { onApply(ClipRange()) },
                border = BorderStroke(1.5.dp, colors.strongBorder),
                shape = RoundedCornerShape(8.dp),
            ) { Text("초기화", color = colors.ink) }
        },
        confirmButton = {
            Button(
                onClick = {
                    val startSeconds = parseTimecode(start)
                    val endSeconds = parseTimecode(end)
                    if (start.isNotBlank() && startSeconds == null || end.isNotBlank() && endSeconds == null) {
                        validation = "구간 시간은 숫자로 입력하세요."
                    } else if (startSeconds != null && endSeconds != null && startSeconds >= endSeconds) {
                        validation = "종료구간은 시작구간보다 뒤여야 합니다."
                    } else onApply(ClipRange(startSeconds, endSeconds, exact))
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.ink),
                shape = RoundedCornerShape(8.dp),
            ) { Text("적용") }
        },
    )
}

@Composable
private fun TimeInput(label: String, value: String, onValueChanged: (String) -> Unit) {
    val colors = clipPalette()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.width(82.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChanged,
            placeholder = { Text("--:--") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f),
        )
    }
}

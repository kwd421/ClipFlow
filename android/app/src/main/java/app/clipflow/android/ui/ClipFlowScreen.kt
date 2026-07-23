package app.clipflow.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import app.clipflow.android.ui.theme.Accent
import app.clipflow.android.ui.theme.Canvas
import app.clipflow.android.ui.theme.Danger
import app.clipflow.android.ui.theme.Ink
import app.clipflow.android.ui.theme.Muted
import app.clipflow.android.ui.theme.Raised
import app.clipflow.android.ui.theme.StrongBorder
import app.clipflow.android.ui.theme.Success
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipFlowScreen(
    state: ClipFlowUiState,
    onUrlChanged: (String) -> Unit,
    onAnalyze: () -> Unit,
    onToggleSelected: (String) -> Unit,
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
    var newestFirst by remember { mutableStateOf(true) }
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
            title = { Text("선택한 파일 삭제", color = Ink, fontWeight = FontWeight.Bold) },
            text = { Text("다운로드된 파일 ${selectedOutputIds.size}개를 삭제하시겠습니까?") },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteSelectedConfirmation = false },
                    border = BorderStroke(1.5.dp, StrongBorder),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("취소", color = Ink) }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedOutputIds.forEach(onDeleteFile)
                        showDeleteSelectedConfirmation = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("삭제") }
            },
            containerColor = Raised,
        )
    }
    deleteCandidateId?.let { candidateId ->
        AlertDialog(
            onDismissRequest = { deleteCandidateId = null },
            title = { Text("파일 삭제", color = Ink, fontWeight = FontWeight.Bold) },
            text = { Text("다운로드된 파일을 삭제하시겠습니까?") },
            dismissButton = {
                OutlinedButton(
                    onClick = { deleteCandidateId = null },
                    border = BorderStroke(1.5.dp, StrongBorder),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("취소", color = Ink) }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteFile(candidateId)
                        deleteCandidateId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("삭제") }
            },
            containerColor = Raised,
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
                if (candidate.id !in state.selectedIds) onToggleSelected(candidate.id)
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
        )
    }

    Scaffold(
        containerColor = Canvas,
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
            ListToolbar(
                state = state,
                selectionMode = selectionMode,
                searchExpanded = searchExpanded,
                searchQuery = searchQuery,
                newestFirst = newestFirst,
                onToggleSelectAll = onToggleSelectAll,
                onExitSelectionMode = { selectionMode = false },
                onRemoveSelected = {
                    state.selectedIds.toList().forEach(onRemove)
                },
                canDeleteSelectedFiles = selectedOutputIds.isNotEmpty(),
                onDeleteSelectedFiles = { showDeleteSelectedConfirmation = true },
                onSearchExpanded = { searchExpanded = it },
                onSearchQuery = { searchQuery = it },
                onSort = { newestFirst = !newestFirst },
                onOptions = { showOptions = true },
            )

            val rows = state.candidates
                .filter { searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) }
                .let { if (newestFirst) it else it.reversed() }
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
                        )
                    }
                }
                if (state.analyzing) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = Ink, strokeWidth = 3.dp)
                        Text(state.analysisMessage, color = Muted, fontWeight = FontWeight.SemiBold)
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
    tint: Color = Ink,
    enabled: Boolean = true,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else Muted, modifier = Modifier.size(iconSize))
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
    val keyboard = LocalSoftwareKeyboardController.current
    val hasCandidates = state.candidates.isNotEmpty()
    val hasSelection = state.selectedIds.isNotEmpty()
    val currentUrl = state.url.trim()
    val showingCurrentUrl = hasCandidates && state.candidates.all { it.sourceUrl == currentUrl }
    val primaryEnabled = !state.analyzing && if (showingCurrentUrl) hasSelection else currentUrl.isNotBlank()
    val primaryAction: () -> Unit = {
        if (showingCurrentUrl) {
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
                placeholder = { Text("URL을 입력하세요") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                trailingIcon = {
                    if (state.url.isBlank()) {
                        TooltipIconButton(Icons.Default.ContentPaste, "붙여넣기", onClick = onPaste)
                    } else {
                        TooltipIconButton(Icons.Default.Clear, "URL 지우기", onClick = onClear)
                    }
                },
                singleLine = true,
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
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
                modifier = Modifier.size(56.dp),
            ) {
                if (state.analyzing) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = if (showingCurrentUrl) "다운로드" else "분석",
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onClipRange,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.5.dp, StrongBorder),
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.clipRange.isSet) "구간 적용됨" else "구간선택", color = Ink)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.5.dp, StrongBorder),
                color = Raised,
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
            border = BorderStroke(1.5.dp, if (state.cookieEnabled) Accent else StrongBorder),
            color = Raised,
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
                    tint = if (state.cookieEnabled) Accent else Ink,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    state.cookieLabel,
                    color = if (state.cookieEnabled) Ink else Muted,
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
    newestFirst: Boolean,
    onToggleSelectAll: () -> Unit,
    onExitSelectionMode: () -> Unit,
    onRemoveSelected: () -> Unit,
    canDeleteSelectedFiles: Boolean,
    onDeleteSelectedFiles: () -> Unit,
    onSearchExpanded: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onSort: () -> Unit,
    onOptions: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
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
                tint = if (state.selectedIds.isNotEmpty()) Accent else Ink,
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
                tint = Danger,
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
                Text(if (newestFirst) "다운로드순" else "이름순", color = Ink)
            }
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
) {
    val status = task?.status ?: TaskStatus.Ready
    val active = status in setOf(TaskStatus.Queued, TaskStatus.Downloading, TaskStatus.Finishing)
    val infinite = rememberInfiniteTransition(label = "finishing-border")
    val hue by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "hue",
    )
    val borderColor = when (status) {
        TaskStatus.Finishing -> Color.hsv(hue, 0.72f, 0.92f)
        TaskStatus.Downloading, TaskStatus.Queued -> Accent
        TaskStatus.Completed -> Success
        TaskStatus.Failed -> Danger
        else -> StrongBorder
    }
    Surface(
        color = Raised,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(if (active || status == TaskStatus.Completed) 2.dp else 1.5.dp, borderColor),
        shadowElevation = if (active) 3.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelected() },
                onLongClick = onLongPress,
            ),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
                }
                AsyncImage(
                    model = candidate.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 112.dp, height = 68.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE5E5E5)),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        candidate.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp,
                    )
                }
            }
            FlowRow(
                modifier = Modifier.padding(
                    start = if (selectionMode) 58.dp else 12.dp,
                    end = 8.dp,
                    bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        listOf(formatDuration(candidate.durationSeconds), formatBytes(candidate.sizeBytes))
                            .joinToString("  ·  "),
                        color = Muted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (status) {
                    TaskStatus.Queued, TaskStatus.Downloading, TaskStatus.Finishing -> {
                        TooltipIconButton(Icons.Default.Pause, "일시정지", onClick = onPause)
                        TooltipIconButton(Icons.Default.Delete, "다운로드 삭제", tint = Danger, onClick = onRemove)
                    }
                    TaskStatus.Paused, TaskStatus.Failed -> {
                        TooltipIconButton(Icons.Default.PlayArrow, "다시 시작", onClick = onResume)
                        TooltipIconButton(
                            Icons.Default.Delete,
                            if (task?.outputUri?.isNotBlank() == true) "파일 삭제" else "다운로드 삭제",
                            tint = Danger,
                            onClick = if (task?.outputUri?.isNotBlank() == true) onDeleteFile else onRemove,
                        )
                    }
                    else -> Unit
                }
            }
            if (task != null && status != TaskStatus.Ready) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            task.detail,
                            color = if (status == TaskStatus.Failed) Danger else Muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (status == TaskStatus.Downloading) Text("${task.progress}%", fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = { task.progress / 100f },
                        color = borderColor,
                        trackColor = Color(0xFFE5E5E5),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                candidate.title,
                color = Ink,
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
                                tint = Danger,
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
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기", color = Ink) }
        },
        containerColor = Raised,
    )
}

@Composable
private fun CandidateActionButton(
    icon: ImageVector,
    label: String,
    tint: Color = Ink,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.5.dp, StrongBorder),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = tint, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PreferencesSheet(
    value: DownloadPreferences,
    onDismiss: () -> Unit,
    onApply: (DownloadPreferences) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Raised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("다운로드 옵션", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                Text("HDR", color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(checked = draft.hdrEnabled, onCheckedChange = { draft = draft.copy(hdrEnabled = it) })
            }
            OptionMenu("병렬", draft.concurrency.toString(), (1..3).map(Int::toString)) {
                draft = draft.copy(concurrency = it.toInt())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { draft = DownloadPreferences() },
                    border = BorderStroke(1.5.dp, StrongBorder),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) { Text("초기화", color = Ink) }
                Button(
                    onClick = { onApply(draft) },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink),
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
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp))
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                border = BorderStroke(1.5.dp, StrongBorder),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(value, color = Ink, fontWeight = FontWeight.Bold) }
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
                    Text("컷 방식", color = Ink, fontWeight = FontWeight.Bold, modifier = Modifier.width(82.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (exact) {
                            OutlinedButton(
                                onClick = { exact = false },
                                border = BorderStroke(1.5.dp, StrongBorder),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("빠른 컷", color = Ink) }
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Ink),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("정확 컷") }
                        } else {
                            Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = Ink),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("빠른 컷") }
                            OutlinedButton(
                                onClick = { exact = true },
                                border = BorderStroke(1.5.dp, StrongBorder),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("정확 컷", color = Ink) }
                        }
                    }
                }
                if (validation.isNotBlank()) Text(validation, color = Danger, fontSize = 12.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { onApply(ClipRange()) },
                border = BorderStroke(1.5.dp, StrongBorder),
                shape = RoundedCornerShape(8.dp),
            ) { Text("초기화", color = Ink) }
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
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
                shape = RoundedCornerShape(8.dp),
            ) { Text("적용") }
        },
    )
}

@Composable
private fun TimeInput(label: String, value: String, onValueChanged: (String) -> Unit) {
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

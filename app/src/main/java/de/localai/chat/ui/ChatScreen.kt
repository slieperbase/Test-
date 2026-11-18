package de.localai.chat.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.localai.chat.ChatMessage
import de.localai.chat.ChatViewModel
import de.localai.chat.ModelStatus
import de.localai.chat.Role
import de.localai.chat.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importModel)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(vm::addAttachment)
    }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbarHost.showSnackbar(it)
            vm.snackbarShown()
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }

    // Bildschirm während eines Downloads anlassen
    val view = LocalView.current
    val downloading = state.downloads.isNotEmpty()
    DisposableEffect(downloading) {
        view.keepScreenOn = downloading
        onDispose { view.keepScreenOn = false }
    }

    val needsModel = state.modelStatus == ModelStatus.NONE || state.modelStatus == ModelStatus.ERROR
    if (showModels || needsModel) {
        BackHandler(enabled = showModels && !needsModel) { showModels = false }
        ModelsScreen(
            state = state,
            vm = vm,
            snackbarHost = snackbarHost,
            onBack = if (needsModel) null else ({ showModels = false }),
            onImport = { pickModel.launch(arrayOf("*/*")) },
            onUse = { file -> vm.useModel(file); showModels = false },
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Lokale KI", fontWeight = FontWeight.SemiBold)
                        Text(
                            statusText(state),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::newChat, enabled = !state.isGenerating) {
                        Icon(Icons.Default.AddComment, contentDescription = "Neuer Chat")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menü")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Modelle verwalten …") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = {
                                menuOpen = false
                                showModels = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("GPU verwenden") },
                            leadingIcon = { Checkbox(checked = state.useGpu, onCheckedChange = null) },
                            onClick = {
                                menuOpen = false
                                vm.setUseGpu(!state.useGpu)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            when (state.modelStatus) {
                ModelStatus.READY -> {
                    MessageList(state.messages, Modifier.weight(1f))
                    InputBar(
                        state = state,
                        onSend = vm::send,
                        onAttach = { pickFiles.launch(arrayOf("*/*")) },
                        onRemoveAttachment = vm::removeAttachment,
                    )
                }
                ModelStatus.IMPORTING, ModelStatus.LOADING -> LoadingView(state, Modifier.weight(1f))
                ModelStatus.NONE, ModelStatus.ERROR -> Unit
            }
        }
    }
}

private fun statusText(s: UiState): String = when (s.modelStatus) {
    ModelStatus.NONE -> "Kein Modell geladen"
    ModelStatus.IMPORTING -> "Modell wird importiert …"
    ModelStatus.LOADING -> "Modell wird geladen …"
    ModelStatus.ERROR -> "Fehler beim Laden"
    ModelStatus.READY -> "${s.modelName ?: "Modell"} · ${if (s.useGpu) "GPU" else "CPU"} · offline"
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier) {
    val listState = rememberLazyListState()
    val lastText = messages.lastOrNull()?.text
    LaunchedEffect(messages.size, lastText) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Schreib etwas, um zu beginnen.\nAlles bleibt auf deinem Gerät.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { MessageBubble(it) }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER
    val bg = when {
        msg.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        msg.isError -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bg,
            contentColor = fg,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                msg.attachments.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (msg.isStreaming && msg.text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
                        Spacer(Modifier.size(8.dp))
                        Text("denkt nach …", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (msg.text.isNotEmpty()) {
                    SelectionContainer {
                        Text(msg.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    state: UiState,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (de.localai.chat.Attachment) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Column {
            if (state.pendingAttachments.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.pendingAttachments) { att ->
                        InputChip(
                            selected = false,
                            onClick = { onRemoveAttachment(att) },
                            label = { Text(att.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, "Entfernen", Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAttach, enabled = !state.isGenerating) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Datei anhängen")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Nachricht …") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                )
                val canSend = !state.isGenerating &&
                    (text.isNotBlank() || state.pendingAttachments.isNotEmpty())
                IconButton(
                    onClick = { onSend(text); text = "" },
                    enabled = canSend,
                ) {
                    if (state.isGenerating) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Senden")
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingView(state: UiState, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.modelStatus == ModelStatus.IMPORTING) {
            Text("Modell wird in die App kopiert …")
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { state.importProgress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("${(state.importProgress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Modell wird geladen … das kann einige Sekunden dauern.")
        }
    }
}

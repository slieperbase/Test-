package de.localai.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.localai.chat.CatalogModel
import de.localai.chat.ChatViewModel
import de.localai.chat.DownloadProgress
import de.localai.chat.ModelCatalog
import de.localai.chat.ModelStatus
import de.localai.chat.UiState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    state: UiState,
    vm: ChatViewModel,
    snackbarHost: SnackbarHostState,
    onBack: (() -> Unit)?,
    onImport: () -> Unit,
    onUse: (String) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val installedNames = state.installed.map { it.fileName }.toSet()
    val custom = state.installed.filter { ModelCatalog.byFileName(it.fileName) == null }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("KI-Modelle", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Wähle ein Modell. Es wird einmalig heruntergeladen und läuft danach komplett offline auf deinem Handy.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Dein Gerät: ca. ${state.deviceRamGb} GB Arbeitsspeicher · ${formatMb(state.freeStorageMb)} frei",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.modelStatus == ModelStatus.ERROR) {
                item {
                    Text(
                        "Das zuletzt gewählte Modell konnte nicht geladen werden. Wähle ein anderes Modell " +
                            "oder schalte im Chat-Menü die GPU-Option aus.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(ModelCatalog.models, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    installed = model.fileName in installedNames,
                    active = state.activeFile == model.fileName && state.modelStatus != ModelStatus.ERROR,
                    progress = state.downloads[model.id],
                    partial = vm.hasPartialDownload(model),
                    deviceRamGb = state.deviceRamGb,
                    onDownload = { vm.downloadModel(model) },
                    onCancel = { vm.cancelDownload(model) },
                    onUse = { onUse(model.fileName) },
                    onDelete = { confirmDelete = model.fileName },
                )
            }
            if (custom.isNotEmpty()) {
                item {
                    Text("Eigene Modelldateien", style = MaterialTheme.typography.titleMedium)
                }
                items(custom, key = { it.fileName }) { m ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(m.fileName, fontWeight = FontWeight.SemiBold)
                            Text(formatMb(m.sizeMb.toLong()), style = MaterialTheme.typography.bodySmall)
                            InstalledButtons(
                                active = state.activeFile == m.fileName && state.modelStatus != ModelStatus.ERROR,
                                onUse = { onUse(m.fileName) },
                                onDelete = { confirmDelete = m.fileName },
                            )
                        }
                    }
                }
            }
            item { TokenSection(state.hfToken, vm::setHfToken) }
            item {
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Eigene .task-Datei importieren")
                }
            }
        }
    }

    confirmDelete?.let { name ->
        val title = ModelCatalog.byFileName(name)?.name ?: name
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Modell löschen?") },
            text = { Text("„$title“ wird vom Gerät entfernt. Du kannst es später erneut herunterladen.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteModel(name); confirmDelete = null }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    model: CatalogModel,
    installed: Boolean,
    active: Boolean,
    progress: DownloadProgress?,
    partial: Boolean,
    deviceRamGb: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val ramTooLow = deviceRamGb in 1 until model.minRamGb
    Card(
        Modifier.fillMaxWidth(),
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (active) Badge("Aktiv", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                if (model.recommended) Badge("Empfohlen", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                if (installed && !active) Badge("Heruntergeladen", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                if (model.needsToken) Badge("Token nötig", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(model.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatMb(model.sizeMb.toLong())} · empfohlen ab ${model.minRamGb} GB RAM",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ramTooLow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Dein Handy hat evtl. zu wenig Arbeitsspeicher für dieses Modell.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            when {
                progress != null -> {
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatMb(progress.downloaded / 1_000_000)} von ${formatMb(progress.total / 1_000_000)} " +
                                "(${(progress.fraction * 100).toInt()} %)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("Abbrechen") }
                    }
                    Text(
                        "Bitte die App geöffnet lassen. Bei Abbruch geht es später an derselben Stelle weiter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                installed -> InstalledButtons(active, onUse, onDelete)
                else -> Button(onClick = onDownload) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (partial) "Download fortsetzen" else "Herunterladen")
                }
            }
        }
    }
}

@Composable
private fun InstalledButtons(active: Boolean, onUse: () -> Unit, onDelete: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text("Wird verwendet", modifier = Modifier.weight(1f))
        } else {
            Button(onClick = onUse, modifier = Modifier.weight(1f)) { Text("Verwenden") }
        }
        OutlinedButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Löschen")
        }
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(8.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun TokenSection(token: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(token.isNotBlank()) }
    var visible by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hugging-Face-Token (nur für Gemma)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ausblenden" else "Einrichten") }
            }
            if (expanded) {
                Text(
                    "1. Kostenloses Konto auf huggingface.co anlegen.\n" +
                        "2. Die Seite „litert-community/Gemma3-1B-IT“ öffnen und die Lizenz akzeptieren.\n" +
                        "3. Unter Settings → Access Tokens einen Token (Typ „Read“) erstellen und hier einfügen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = onChange,
                    label = { Text("Token (hf_…)") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (visible) "Verbergen" else "Anzeigen",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Der Token wird nur auf deinem Gerät gespeichert und nur an huggingface.co gesendet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatMb(mb: Long): String =
    if (mb >= 1000) String.format(Locale.GERMANY, "%.1f GB", mb / 1000.0) else "$mb MB"

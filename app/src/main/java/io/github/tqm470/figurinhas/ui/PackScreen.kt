package io.github.tqm470.figurinhas.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import io.github.tqm470.figurinhas.R
import io.github.tqm470.figurinhas.data.Pack
import io.github.tqm470.figurinhas.data.Sticker
import io.github.tqm470.figurinhas.data.StickerStore
import io.github.tqm470.figurinhas.ui.theme.WhatsAppGreen
import io.github.tqm470.figurinhas.whatsapp.WhatsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackScreen(packId: String, onBack: () -> Unit, onEditMedia: (Uri) -> Unit) {
    val context = LocalContext.current
    val store = remember { StickerStore.get(context) }
    val packs by store.packs.collectAsStateWithLifecycle()
    val pack = packs.firstOrNull { it.id == packId }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var editInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Sticker?>(null) }
    var pickMenu by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(false) }
    val picker = rememberMediaPicker(onEditMedia)

    // Re-check "already added" whenever we come back to this screen (e.g. from WhatsApp).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(packId, pack?.version) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            added = withContext(Dispatchers.IO) { WhatsApp.isPackAdded(context, packId) }
        }
    }

    val msgAdded = stringResource(R.string.added_ok)
    val msgNoWa = stringResource(R.string.whatsapp_missing)
    val msgError = stringResource(R.string.whatsapp_error)
    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        scope.launch {
            if (res.resultCode == Activity.RESULT_OK) {
                snackbar.showSnackbar(msgAdded)
            } else {
                val err = res.data?.getStringExtra("validation_error")
                if (err != null) snackbar.showSnackbar(msgError.format(err))
            }
        }
    }

    if (pack == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(pack.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit_pack)) },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { menu = false; editInfo = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_pack)) },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                WhatsAppCard(pack, added) {
                    val apps = WhatsApp.installed(context)
                    if (apps.isEmpty()) {
                        scope.launch { snackbar.showSnackbar(msgNoWa) }
                    } else {
                        try {
                            addLauncher.launch(WhatsApp.addPackIntent(pack, apps.singleOrNull()))
                        } catch (_: ActivityNotFoundException) {
                            scope.launch { snackbar.showSnackbar(msgNoWa) }
                        }
                    }
                }
            }
            items(pack.stickers, key = { it.file }) { s ->
                StickerCell(File(store.packDir(pack.id), s.file), isTray = s.file == (pack.trayFrom ?: pack.stickers.first().file)) {
                    selected = s
                }
            }
            if (!pack.isFull) {
                item {
                    Box {
                        AddCell { pickMenu = true }
                        PickSourceMenu(pickMenu, { pickMenu = false }, picker)
                    }
                }
            }
        }
    }

    if (editInfo) {
        PackInfoDialog(
            initial = pack,
            onDismiss = { editInfo = false },
            onConfirm = { name, author, _ ->
                editInfo = false
                store.updateInfo(pack.id, name, author)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_pack_q)) },
            text = { Text(stringResource(R.string.delete_pack_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    store.deletePack(pack.id)
                    onBack()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    selected?.let { s ->
        StickerDialog(
            pack = pack,
            sticker = s,
            file = File(store.packDir(pack.id), s.file),
            onDismiss = { selected = null },
            onDelete = { store.removeSticker(pack.id, s.file); selected = null },
            onSetTray = { store.setTray(pack.id, s.file); selected = null },
            onMove = { d -> store.move(pack.id, s.file, d) },
            onEmojis = { e -> store.setEmojis(pack.id, s.file, e) },
        )
    }
}

@Composable
private fun WhatsAppCard(pack: Pack, added: Boolean, onAdd: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PackTray(pack)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(pack.publisher.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(if (pack.animated) R.string.type_animated else R.string.type_static) +
                            " · ${pack.stickers.size}/${Pack.MAX_STICKERS}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { pack.stickers.size / Pack.MAX_STICKERS.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (!pack.canAddToWhatsApp) {
                Text(
                    stringResource(R.string.need_more, Pack.MIN_STICKERS - pack.stickers.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (added) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = WhatsAppGreen)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.already_added), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (pack.canAddToWhatsApp) {
                Spacer(Modifier.height(8.dp))
                if (added) {
                    OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.add_again))
                    }
                } else {
                    Button(
                        onClick = onAdd,
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text(stringResource(R.string.add_to_whatsapp), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

@Composable
private fun StickerCell(file: File, isTray: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (isTray) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun AddCell(onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Add, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.add_sticker), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StickerDialog(
    pack: Pack,
    sticker: Sticker,
    file: File,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSetTray: () -> Unit,
    onMove: (Int) -> Unit,
    onEmojis: (List<String>) -> Unit,
) {
    var emojis by remember(sticker.file) { mutableStateOf(sticker.emojis.joinToString("")) }
    val index = pack.stickers.indexOfFirst { it.file == sticker.file }
    AlertDialog(
        onDismissRequest = { onEmojis(parseEmojis(emojis)); onDismiss() },
        confirmButton = {
            TextButton(onClick = { onEmojis(parseEmojis(emojis)); onDismiss() }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.delete))
            }
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(model = file, contentDescription = null, modifier = Modifier.size(200.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    "%.0f KB".format(file.length() / 1024f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = emojis,
                    onValueChange = { emojis = it },
                    label = { Text(stringResource(R.string.emojis)) },
                    supportingText = { Text(stringResource(R.string.emojis_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = index > 0, onClick = { onMove(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.move_left))
                    }
                    TextButton(onClick = onSetTray) {
                        Icon(Icons.Default.Image, null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.use_as_icon))
                    }
                    IconButton(enabled = index < pack.stickers.lastIndex, onClick = { onMove(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.move_right))
                    }
                }
            }
        },
    )
}

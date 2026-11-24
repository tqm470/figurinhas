package io.github.tqm470.figurinhas.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.tqm470.figurinhas.R
import io.github.tqm470.figurinhas.data.StickerStore
import io.github.tqm470.figurinhas.engine.FitMode
import io.github.tqm470.figurinhas.engine.StickerRenderer
import io.github.tqm470.figurinhas.engine.TextPosition
import kotlinx.coroutines.delay
import java.util.Locale

private val TEXT_COLORS = listOf(
    Color.White, Color(0xFFFFE14D), Color(0xFFFF5A5A), Color(0xFF6BE675),
    Color(0xFF5AC8FF), Color(0xFFFF7AD9), Color.Black,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(uri: Uri, initialPackId: String?, onClose: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: EditorViewModel = viewModel(key = uri.toString()) { EditorViewModel(app, uri, initialPackId) }
    val store = remember { StickerStore.get(context) }
    val packs by store.packs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var choosePack by remember { mutableStateOf(false) }
    var createPack by remember { mutableStateOf(false) }

    val msg = vm.message
    val msgText = msg?.let { stringResource(it) }
    LaunchedEffect(msg) {
        if (msgText != null) {
            snackbar.showSnackbar(msgText)
            vm.message = null
        }
    }
    val savedTo = vm.savedTo
    LaunchedEffect(savedTo) { if (savedTo != null) onSaved(savedTo) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_sticker)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                },
                actions = {
                    vm.pack?.let {
                        Text(
                            it.name, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp).widthIn(max = 140.dp), maxLines = 1,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    if (vm.saving) {
                        Text(stringResource(R.string.encoding), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { vm.saveProgress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Button(
                            enabled = !vm.loading && vm.frames.isNotEmpty(),
                            onClick = {
                                val id = vm.packId
                                if (id != null && store.pack(id)?.isFull == false) vm.save(id) else choosePack = true
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.save_sticker), style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        },
    ) { padding ->
        when {
            vm.loadError != null -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(vm.loadError!!), style = MaterialTheme.typography.bodyLarge)
            }
            vm.source == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> Column(
                Modifier
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Preview(vm)
                Text(
                    stringResource(R.string.gesture_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Controls(vm)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (choosePack) {
        val ok = packs.filter { !it.isFull }
        AlertDialog(
            onDismissRequest = { choosePack = false },
            title = { Text(stringResource(R.string.choose_pack)) },
            text = {
                LazyColumn {
                    items(ok, key = { it.id }) { p ->
                        ListItem(
                            leadingContent = { PackTray(p) },
                            headlineContent = { Text(p.name, maxLines = 1) },
                            supportingContent = {
                                Text(
                                    pluralStringResource(R.plurals.sticker_count, p.stickers.size, p.stickers.size) + " · " +
                                        stringResource(if (p.animated) R.string.type_animated else R.string.type_static),
                                )
                            },
                            modifier = Modifier.clickable {
                                choosePack = false
                                vm.packId = p.id
                                vm.save(p.id)
                            },
                        )
                    }
                    item {
                        ListItem(
                            leadingContent = { Icon(Icons.Default.Add, null, Modifier.size(56.dp).padding(14.dp)) },
                            headlineContent = { Text(stringResource(R.string.new_pack)) },
                            modifier = Modifier.clickable { choosePack = false; createPack = true },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosePack = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (createPack) {
        PackInfoDialog(
            initial = null,
            defaultAnimated = vm.sourceIsAnimated,
            onDismiss = { createPack = false },
            onConfirm = { name, author, animated ->
                createPack = false
                val p = store.createPack(name, author, animated)
                vm.packId = p.id
                vm.save(p.id)
            },
        )
    }
}

@Composable
private fun Preview(vm: EditorViewModel) {
    val seq = vm.sequence
    val dur = vm.frameDurationMs
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(seq, dur) {
        index = 0
        if (seq.size > 1) while (true) {
            delay(dur.toLong())
            index = (index + 1) % seq.size
        }
    }
    val opts by rememberUpdatedState(vm.options)
    val currentFrame = seq.getOrNull(index.coerceAtMost(seq.lastIndex))

    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val o = vm.options
                    val f = vm.frames.firstOrNull() ?: return@detectTransformGestures
                    val newZoom = (o.zoom * zoom).coerceIn(1f, 4f)
                    val (ox, oy) = StickerRenderer.overflow(f.width, f.height, o.copy(zoom = newZoom))
                    val k = StickerRenderer.SIZE / size.width.toFloat()
                    val px = if (ox > 0) (o.panX - pan.x * k * 2 / ox).coerceIn(-1f, 1f) else 0f
                    val py = if (oy > 0) (o.panY - pan.y * k * 2 / oy).coerceIn(-1f, 1f) else 0f
                    vm.options = o.copy(zoom = newZoom, panX = px, panY = py)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Checkerboard()
        if (currentFrame != null) {
            Canvas(Modifier.fillMaxSize()) {
                val s = size.width / StickerRenderer.SIZE
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.scale(s, s)
                    StickerRenderer.draw(nc, currentFrame, opts)
                    nc.restore()
                }
            }
        }
        if (vm.loading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { vm.loadProgress }, color = Color.White)
            }
        }
    }
}

@Composable
private fun Checkerboard() {
    val a = MaterialTheme.colorScheme.surfaceContainerHighest
    val b = MaterialTheme.colorScheme.surfaceContainerLow
    Canvas(Modifier.fillMaxSize()) {
        drawRect(b)
        val cell = size.width / 16
        for (y in 0 until 16) for (x in 0 until 16) {
            if ((x + y) % 2 == 0) drawRect(a, Offset(x * cell, y * cell), Size(cell, cell))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Controls(vm: EditorViewModel) {
    val src = vm.source ?: return
    val o = vm.options

    // ---- Timing ----
    if (vm.sourceIsAnimated && src.durationMs > 0) {
        if (vm.animatedOutput) {
            Section(stringResource(R.string.trim))
            val outSeconds = vm.sequence.size * vm.frameDurationMs / 1000f
            Text(
                "${secs(vm.trimStart)} – ${secs(vm.trimEnd)}  ·  " +
                    stringResource(R.string.sticker_length, String.format(Locale.US, "%.1f", outSeconds)),
                style = MaterialTheme.typography.bodyMedium,
            )
            RangeSlider(
                value = vm.trimStart.toFloat()..vm.trimEnd.toFloat(),
                onValueChange = { r -> vm.setTrim(r.start.toLong(), r.endInclusive.toLong()) },
                onValueChangeFinished = { vm.reload() },
                valueRange = 0f..src.durationMs.toFloat(),
            )
            if (src.durationMs > vm.maxWindowMs) {
                Hint(stringResource(R.string.trim_hint))
            }

            Section(stringResource(R.string.speed))
            ChipRow(EditorViewModel.SPEED_OPTIONS, vm.speed, { "${fmt(it)}×" }) { vm.updateSpeed(it) }

            Section(stringResource(R.string.fps))
            ChipRow(EditorViewModel.FPS_OPTIONS, vm.fps, { "$it" }) { vm.updateFps(it) }
            Hint(stringResource(R.string.fps_hint))

            Section(stringResource(R.string.loop))
            ChipRow(
                Playback.entries.toList(), vm.playback,
                {
                    when (it) {
                        Playback.NORMAL -> "▶ " + stringResource(R.string.loop_normal)
                        Playback.REVERSE -> "◀ " + stringResource(R.string.loop_reverse)
                        Playback.BOOMERANG -> "↔ " + stringResource(R.string.loop_boomerang)
                    }
                },
            ) { vm.updatePlayback(it) }
        } else {
            Section(stringResource(R.string.pick_frame))
            Hint(stringResource(R.string.static_pack_hint))
            Slider(
                value = vm.trimStart.toFloat(),
                onValueChange = { vm.setFrameTime(it.toLong()) },
                onValueChangeFinished = { vm.reload() },
                valueRange = 0f..src.durationMs.toFloat(),
            )
        }
    }

    // ---- Framing ----
    Section(stringResource(R.string.framing))
    ChipRow(
        listOf(FitMode.FIT, FitMode.FILL), o.fit,
        { stringResource(if (it == FitMode.FIT) R.string.fit else R.string.fill) },
    ) { vm.options = o.copy(fit = it, panX = 0f, panY = 0f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.zoom), Modifier.padding(end = 12.dp))
        Slider(
            value = o.zoom, onValueChange = { vm.options = o.copy(zoom = it) },
            valueRange = 1f..4f, modifier = Modifier.weight(1f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.rounded), Modifier.weight(1f))
        Switch(checked = o.rounded, onCheckedChange = { vm.options = o.copy(rounded = it) })
    }

    // ---- Text ----
    Section(stringResource(R.string.caption))
    OutlinedTextField(
        value = o.text,
        onValueChange = { vm.options = o.copy(text = it.take(60)) },
        placeholder = { Text(stringResource(R.string.caption_hint)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )
    if (o.text.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TEXT_COLORS.forEach { c ->
                val selected = o.textColor == c.toArgb()
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        )
                        .clickable { vm.options = o.copy(textColor = c.toArgb()) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ChipRow(
            listOf(TextPosition.TOP, TextPosition.BOTTOM), o.textPosition,
            { stringResource(if (it == TextPosition.TOP) R.string.top else R.string.bottom) },
        ) { vm.options = o.copy(textPosition = it) }
    }

    // ---- Emojis ----
    Section(stringResource(R.string.emojis))
    OutlinedTextField(
        value = vm.emojis,
        onValueChange = { vm.emojis = it },
        supportingText = { Text(stringResource(R.string.emojis_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(values: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label(v)) })
        }
    }
}

private fun secs(ms: Long) = String.format(Locale.US, "%.1fs", ms / 1000f)
private fun fmt(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

package io.github.tqm470.figurinhas.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tqm470.figurinhas.R
import io.github.tqm470.figurinhas.data.Pack
import io.github.tqm470.figurinhas.data.StickerStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenPack: (String) -> Unit, onEditMedia: (Uri) -> Unit) {
    val context = LocalContext.current
    val store = remember { StickerStore.get(context) }
    val packs by store.packs.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }
    var showPickMenu by remember { mutableStateOf(false) }
    val picker = rememberMediaPicker(onEditMedia)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    Box {
                        OutlinedButton(onClick = { showPickMenu = true }, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.quick_sticker))
                        }
                        PickSourceMenu(showPickMenu, { showPickMenu = false }, picker)
                    }
                },
            )
        },
        floatingActionButton = {
            if (packs.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showNew = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.new_pack)) },
                )
            }
        },
    ) { padding ->
        if (packs.isEmpty()) {
            EmptyState(Modifier.padding(padding)) { showNew = true }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.home_tip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(packs, key = { it.id }) { pack -> PackRow(pack) { onOpenPack(pack.id) } }
            }
        }
    }

    if (showNew) {
        PackInfoDialog(
            initial = null,
            onDismiss = { showNew = false },
            onConfirm = { name, author, animated ->
                showNew = false
                onOpenPack(store.createPack(name, author, animated).id)
            },
        )
    }
}

@Composable
private fun PackRow(pack: Pack, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PackTray(pack)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val type = stringResource(if (pack.animated) R.string.type_animated else R.string.type_static)
                Text(
                    pluralStringResource(R.plurals.sticker_count, pack.stickers.size, pack.stickers.size) + " · " + type,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onCreate: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🎞️✨", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.create_first_pack))
        }
    }
}

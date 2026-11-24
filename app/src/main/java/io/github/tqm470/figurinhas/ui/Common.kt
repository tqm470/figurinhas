package io.github.tqm470.figurinhas.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.tqm470.figurinhas.R
import io.github.tqm470.figurinhas.data.Pack
import io.github.tqm470.figurinhas.data.StickerStore
import java.io.File

private const val PREFS = "prefs"
private const val KEY_AUTHOR = "author"

fun lastAuthor(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUTHOR, "").orEmpty()

fun saveAuthor(context: Context, author: String) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_AUTHOR, author).apply()

/** Splits user input into at most 3 emoji graphemes. */
fun parseEmojis(input: String): List<String> {
    val it = android.icu.text.BreakIterator.getCharacterInstance()
    it.setText(input)
    val out = ArrayList<String>()
    var start = it.first()
    var end = it.next()
    while (end != android.icu.text.BreakIterator.DONE) {
        val g = input.substring(start, end)
        if (g.isNotBlank() && g.any { c -> c.code > 0x7F }) out += g
        start = end
        end = it.next()
    }
    return out.distinct().take(3)
}

/** Dialog to create a pack, or edit its name/author when [initial] is given. */
@Composable
fun PackInfoDialog(
    initial: Pack?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, author: String, animated: Boolean) -> Unit,
    defaultAnimated: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var author by remember { mutableStateOf(initial?.publisher ?: lastAuthor(context)) }
    var animated by remember { mutableStateOf(initial?.animated ?: defaultAnimated) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.new_pack else R.string.edit_pack)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(128) },
                    label = { Text(stringResource(R.string.pack_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author, onValueChange = { author = it.take(128) },
                    label = { Text(stringResource(R.string.pack_author)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (initial == null) {
                    Text(stringResource(R.string.pack_type), style = MaterialTheme.typography.labelLarge)
                    TypeOption(true, animated, R.string.type_animated, R.string.type_animated_desc) { animated = true }
                    TypeOption(false, animated, R.string.type_static, R.string.type_static_desc) { animated = false }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    saveAuthor(context, author.trim())
                    onConfirm(name.trim(), author.trim(), animated)
                },
            ) { Text(stringResource(if (initial == null) R.string.create else R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TypeOption(value: Boolean, current: Boolean, title: Int, desc: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(selected = value == current, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == current, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Launchers for the system photo picker and the file browser. */
class MediaPicker(val gallery: () -> Unit, val files: () -> Unit)

@Composable
fun rememberMediaPicker(onPicked: (Uri) -> Unit): MediaPicker {
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPicked(uri)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    return remember {
        MediaPicker(
            gallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            files = { files.launch(arrayOf("image/*", "video/*")) },
        )
    }
}

/** Small menu: "From gallery" / "From files". */
@Composable
fun PickSourceMenu(expanded: Boolean, onDismiss: () -> Unit, picker: MediaPicker) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.from_gallery)) },
            leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
            onClick = { onDismiss(); picker.gallery() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.from_files)) },
            leadingIcon = { Icon(Icons.Default.Folder, null) },
            onClick = { onDismiss(); picker.files() },
        )
    }
}

@Composable
fun PackTray(pack: Pack, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tray = File(StickerStore.get(context).packDir(pack.id), Pack.TRAY_FILE)
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (pack.stickers.isNotEmpty()) {
            AsyncImage(
                model = tray, contentDescription = null,
                contentScale = ContentScale.Fit, modifier = Modifier.size(48.dp),
            )
        } else {
            Text("🖼️", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

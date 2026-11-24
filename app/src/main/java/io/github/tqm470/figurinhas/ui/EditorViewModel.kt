package io.github.tqm470.figurinhas.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.tqm470.figurinhas.R
import io.github.tqm470.figurinhas.data.StickerStore
import io.github.tqm470.figurinhas.engine.MediaKind
import io.github.tqm470.figurinhas.engine.MediaSource
import io.github.tqm470.figurinhas.engine.RenderOptions
import io.github.tqm470.figurinhas.engine.StickerMaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Playback { NORMAL, REVERSE, BOOMERANG }

class EditorViewModel(app: Application, private val uri: Uri, initialPackId: String?) : AndroidViewModel(app) {

    private val store = StickerStore.get(app)

    var packId by mutableStateOf(initialPackId)
    var source by mutableStateOf<MediaSource?>(null)
        private set
    /** String resource of a fatal load error. */
    var loadError by mutableStateOf<Int?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadProgress by mutableFloatStateOf(0f)
        private set

    /** Frames sampled from the trim window at [fps] (before playback mode). */
    var frames by mutableStateOf<List<Bitmap>>(emptyList())
        private set

    var trimStart by mutableLongStateOf(0L)
        private set
    var trimEnd by mutableLongStateOf(0L)
        private set
    var fps by mutableIntStateOf(10)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var playback by mutableStateOf(Playback.NORMAL)
        private set

    var options by mutableStateOf(RenderOptions())
    var emojis by mutableStateOf(StickerStore.DEFAULT_EMOJI)

    var saving by mutableStateOf(false)
        private set
    var saveProgress by mutableFloatStateOf(0f)
        private set
    /** One-shot message (string resource) for a snackbar. */
    var message by mutableStateOf<Int?>(null)
    /** Set once the sticker is saved; the screen navigates away. */
    var savedTo by mutableStateOf<String?>(null)
        private set

    val pack get() = packId?.let { store.pack(it) }

    /** Whether we produce an animated sticker (pack type wins; otherwise follow the source). */
    val animatedOutput: Boolean
        get() = pack?.animated ?: (source?.kind != MediaKind.IMAGE)

    val sourceIsAnimated: Boolean get() = source?.kind == MediaKind.GIF || source?.kind == MediaKind.VIDEO

    val frameDurationMs: Int get() = 1000 / fps

    /** What actually plays / gets encoded. */
    val sequence: List<Bitmap> by derivedStateOf {
        val f = frames
        if (!animatedOutput) return@derivedStateOf f.take(1)
        when (playback) {
            Playback.NORMAL -> f
            Playback.REVERSE -> f.reversed()
            Playback.BOOMERANG -> if (f.size > 2) f + f.reversed().drop(1).dropLast(1) else f
        }
    }

    /** Longest trim window (in source time) that still yields <= 10 s of sticker. */
    val maxWindowMs: Long
        get() = (StickerMaker.MAX_DURATION_MS * speed / if (playback == Playback.BOOMERANG) 2 else 1).toLong()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val src = MediaSource.open(getApplication(), uri)
                source = src
                fps = if (src.kind == MediaKind.GIF) FPS_OPTIONS.minBy { abs(it - min(src.nativeFps, 15f)) } else 10
                trimStart = 0
                trimEnd = min(src.durationMs, maxWindowMs)
                reload()
            } catch (e: Exception) {
                loadError = R.string.error_open
                loading = false
            }
        }
    }

    fun reload() {
        val src = source ?: return
        loadJob?.cancel()
        loading = true
        loadProgress = 0f
        loadJob = viewModelScope.launch {
            try {
                val stepMs = 1000.0 / fps * speed
                val end = if (animatedOutput) max(trimEnd, trimStart + 1) else trimStart + 1
                frames = src.frames(trimStart, end, stepMs) { p -> loadProgress = p }
                loading = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // OutOfMemoryError included: tell the user to trim or lower FPS.
                frames = emptyList()
                loading = false
                message = if (e is OutOfMemoryError) R.string.error_memory else R.string.error_decode
            }
        }
    }

    /** Called while dragging the trim slider; keeps the window <= maxWindowMs. */
    fun setTrim(start: Long, end: Long) {
        val movedStart = start != trimStart
        var s = start
        var e = end
        if (e - s > maxWindowMs) {
            if (movedStart) e = s + maxWindowMs else s = e - maxWindowMs
        }
        if (e - s < MIN_WINDOW_MS && (source?.durationMs ?: 0) > MIN_WINDOW_MS) {
            if (movedStart) s = max(0, e - MIN_WINDOW_MS) else e = s + MIN_WINDOW_MS
        }
        trimStart = s
        trimEnd = e
    }

    /** For static output: picks the single frame time. */
    fun setFrameTime(t: Long) {
        trimStart = t
    }

    fun updateFps(v: Int) { fps = v; reload() }

    fun updateSpeed(v: Float) {
        speed = v
        clampWindow()
        reload()
    }

    fun updatePlayback(p: Playback) {
        val needsReload = (p == Playback.BOOMERANG) != (playback == Playback.BOOMERANG) &&
            trimEnd - trimStart > (StickerMaker.MAX_DURATION_MS * speed / if (p == Playback.BOOMERANG) 2 else 1).toLong()
        playback = p
        if (needsReload) { clampWindow(); reload() }
    }

    private fun clampWindow() {
        val dur = source?.durationMs ?: return
        if (trimEnd - trimStart > maxWindowMs) trimEnd = trimStart + maxWindowMs
        if (trimEnd > dur) trimEnd = dur
    }

    fun save(targetPackId: String) {
        if (saving) return
        val target = store.pack(targetPackId) ?: return
        val seq = if (target.animated) {
            if (!animatedOutput) frames else sequence
        } else sequence.take(1)
        if (seq.isEmpty()) return
        saving = true
        saveProgress = 0f
        viewModelScope.launch {
            try {
                val result = if (target.animated) {
                    StickerMaker.makeAnimated(seq, frameDurationMs, options) { saveProgress = it }
                } else {
                    StickerMaker.makeStatic(seq.first(), options)
                }
                withContext(Dispatchers.IO) {
                    store.addSticker(target.id, result.webp, result.firstFrame, parseEmojis(emojis))
                }
                savedTo = target.id
            } catch (e: StickerMaker.TooBigException) {
                message = R.string.error_too_big
            } catch (e: OutOfMemoryError) {
                message = R.string.error_memory
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = R.string.error_save
            } finally {
                saving = false
            }
        }
    }

    override fun onCleared() {
        source?.release()
    }

    companion object {
        val FPS_OPTIONS = listOf(5, 8, 10, 12, 15)
        val SPEED_OPTIONS = listOf(0.5f, 1f, 1.5f, 2f, 3f)
        private const val MIN_WINDOW_MS = 200L
    }
}

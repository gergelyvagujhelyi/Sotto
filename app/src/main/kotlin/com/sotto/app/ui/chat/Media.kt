package com.sotto.app.ui.chat

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import com.sotto.app.domain.model.MediaKind
import com.sotto.app.domain.model.Message
import com.sotto.app.media.VoicePlayer
import com.sotto.app.ui.components.LocalBubbleGestures
import com.sotto.app.ui.theme.Aurora
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What the chat's bubbles need for media: decrypting a message's media, and the one voice player. */
@Stable
class ChatMedia(
    val load: suspend (messageId: String) -> ByteArray?,
    val player: VoicePlayer,
    val openPhoto: (messageId: String) -> Unit,
    /** A file's bubble was tapped: download it, or open it once it's here. */
    val onFile: (Message) -> Unit = {},
    /** File transfers under way, by message id (0..1). */
    val fileProgress: State<Map<String, Float>> = androidx.compose.runtime.mutableStateOf(emptyMap()),
    /** An HD photo's full-size copy, once it's on this phone. */
    val loadHd: suspend (messageId: String) -> ByteArray? = { null },
)

val LocalChatMedia = staticCompositionLocalOf<ChatMedia?> { null }

@Composable
fun ProvideChatMedia(media: ChatMedia, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalChatMedia provides media, content = content)
}

/** The media part of [message]'s bubble, above its caption; nothing for a text message. */
@Composable
internal fun BubbleMedia(message: Message, contentColor: Color) {
    val media = message.media ?: return
    when (media.kind) {
        MediaKind.VOICE -> VoiceMessage(message, contentColor)
        MediaKind.PHOTO -> PhotoThumb(message)
        MediaKind.FILE -> FileCard(message, contentColor)
    }
}

/**
 * A tap on media in a bubble does [onClick]; holding it reacts and (with [doubleTap])
 * double-tapping hearts it, as on the rest of the bubble.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.mediaClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    doubleTap: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val gestures = LocalBubbleGestures.current
    return combinedClickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onLongClickLabel = if (gestures.onLongClick != null) "React" else null,
        onLongClick = gestures.onLongClick,
        onDoubleClick = if (doubleTap) gestures.onDoubleClick else null,
        onClick = onClick,
    )
}

/** "1.4 MB" */
internal fun byteSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

@Composable
private fun FileCard(message: Message, contentColor: Color) {
    val host = LocalChatMedia.current ?: return
    val media = message.media ?: return
    val progress = host.fileProgress.value[message.id]
    val state = media.fileState
    val icon = when {
        media.mime.startsWith("video/") -> Icons.Rounded.Movie
        media.mime.startsWith("audio/") -> Icons.Rounded.AudioFile
        media.mime == "application/pdf" -> Icons.Rounded.PictureAsPdf
        media.mime.startsWith("image/") -> Icons.Rounded.Image
        else -> Icons.Rounded.Description
    }
    val status = when {
        progress != null && message.isOutgoing -> "Sending ${(progress * 100).toInt()}%"
        progress != null -> "Downloading ${(progress * 100).toInt()}%"
        state == com.sotto.app.domain.model.FileState.UPLOADING -> if (message.state == com.sotto.app.domain.model.MessageState.FAILED) "Not uploaded" else "Waiting to upload…"
        state == com.sotto.app.domain.model.FileState.DOWNLOADING -> "Waiting to download…"
        state == com.sotto.app.domain.model.FileState.DIRECT -> if (message.isOutgoing) "Sending directly…" else "Coming directly…"
        state == com.sotto.app.domain.model.FileState.REMOTE -> "Tap to download"
        state == com.sotto.app.domain.model.FileState.GONE -> "No longer available"
        state == com.sotto.app.domain.model.FileState.UNSENT -> "Not sent · Tap to retry"
        else -> "Tap to open"
    }
    // Our own file is on this phone from the start: it opens while it's still being sent.
    val tappable = message.isOutgoing || (
        progress == null && state != com.sotto.app.domain.model.FileState.GONE &&
            state != com.sotto.app.domain.model.FileState.DOWNLOADING &&
            state != com.sotto.app.domain.model.FileState.DIRECT
        )
    val thumb = remember(message.id, media.thumbnail?.size) {
        media.thumbnail?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    if (thumb != null) {
        // A video or picture: its preview, with the file's line under it.
        Box(
            Modifier
                .width(FileWidth)
                .aspectRatio((thumb.width.toFloat() / thumb.height).coerceIn(0.6f, 1.8f))
                .clip(RoundedCornerShape(14.dp))
                .mediaClickable(enabled = tappable, onClickLabel = status) { host.onFile(message) },
            contentAlignment = Alignment.Center,
        ) {
            Image(thumb, contentDescription = media.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                if (progress != null) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp), strokeWidth = 3.dp, color = Color.White)
                } else {
                    Icon(
                        when {
                            state == com.sotto.app.domain.model.FileState.REMOTE -> Icons.Rounded.Download
                            media.mime.startsWith("video/") -> Icons.Rounded.PlayArrow
                            else -> Icons.Rounded.Image
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            if (media.durationMs > 0) {
                Text(
                    clock(media.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            "${media.name.ifBlank { "File" }} · ${byteSize(media.size)} · $status",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
                .width(FileWidth)
                .padding(top = 4.dp, bottom = 2.dp),
        )
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(FileWidth)
            .clip(RoundedCornerShape(14.dp))
            .background(contentColor.copy(alpha = 0.08f))
            .mediaClickable(enabled = tappable, onClickLabel = status) { host.onFile(message) }
            .padding(10.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (progress != null) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = contentColor)
            } else {
                Icon(
                    if (state == com.sotto.app.domain.model.FileState.REMOTE) Icons.Rounded.Download else icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                media.name.ifBlank { "File" },
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "${byteSize(media.size)} · $status",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.75f),
            )
        }
    }
}

private val FileWidth = 252.dp

/** mm:ss */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun VoiceMessage(message: Message, contentColor: Color) {
    val host = LocalChatMedia.current ?: return
    val player = host.player
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val current = player.currentId == message.id
    val playing = current && player.isPlaying
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val total = message.media?.durationMs?.takeIf { it > 0 } ?: 0L

    LaunchedEffect(playing) {
        while (playing) {
            player.tick()
            delay(80)
        }
    }
    val progress = if (current && total > 0) (player.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(progress, label = "voiceProgress")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(VoiceWidth)
            .padding(bottom = 2.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.14f))
                // No double tap here: play starts at once.
                .mediaClickable(role = Role.Button, onClickLabel = if (playing) "Pause" else "Play", doubleTap = false) {
                    if (loading) return@mediaClickable
                    scope.launch {
                        loading = true
                        try {
                            val audio = host.load(message.id)
                            if (audio == null) {
                                failed = true
                            } else {
                                failed = false
                                player.toggle(message.id, audio)
                            }
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            loading = false
                        }
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = contentColor)
                else -> Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Pause voice message" else "Play voice message",
                    tint = contentColor,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(contentColor.copy(alpha = 0.22f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(shown)
                        .background(contentColor.copy(alpha = 0.85f)),
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = when {
                    failed -> "Can't play this voice message"
                    current -> clock(player.positionMs) + (if (total > 0) " / " + clock(total) else "")
                    else -> if (total > 0) clock(total) else "Voice message"
                },
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.75f),
            )
        }
    }
}

private val VoiceWidth = 232.dp

/** Decoded photos, by message id, so scrolling back doesn't decrypt and decode again. */
private object PhotoCache {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, value: ImageBitmap) {
        cache.put(key, value)
    }
}

/** Decodes [bytes] at most about [maxSide] px on the long side. */
private fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    return bitmap?.asImageBitmap()
}

@Composable
private fun rememberPhoto(messageId: String, maxSide: Int, hd: Boolean = false): State<ImageBitmap?> {
    val host = LocalChatMedia.current
    val key = "$messageId@$maxSide${if (hd) "-hd" else ""}"
    return produceState(PhotoCache.get(key), key) {
        if (value != null || host == null) return@produceState
        value = withContext(Dispatchers.Default) {
            (if (hd) host.loadHd(messageId) else host.load(messageId))?.let { decode(it, maxSide) }
        }?.also { PhotoCache.put(key, it) }
        if (value == null && !hd) failedPhotos.add(messageId)
    }
}

private val failedPhotos = java.util.Collections.synchronizedSet(HashSet<String>())

@Composable
private fun PhotoThumb(message: Message) {
    val host = LocalChatMedia.current ?: return
    val media = message.media ?: return
    val ratio = if (media.width > 0 && media.height > 0) {
        (media.width.toFloat() / media.height).coerceIn(0.5f, 2f)
    } else {
        4f / 3f
    }
    val photo by rememberPhoto(message.id, maxSide = 720)
    Box(
        Modifier
            .widthIn(min = 180.dp, max = 280.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .mediaClickable(onClickLabel = "Open photo") { host.openPhoto(message.id) }
            .semantics { contentDescription = "Photo" },
        contentAlignment = Alignment.Center,
    ) {
        val p = photo
        if (p != null) {
            Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                if (message.id in failedPhotos) Icons.Rounded.BrokenImage else Icons.Rounded.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
        if (media.hd) {
            Text(
                "HD",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .semantics { contentDescription = "HD photo" },
            )
        }
    }
}

/** A photo on its own, full screen: pinch to zoom, drag to move, Back or ✕ to close. */
@Composable
fun PhotoViewer(messageId: String, message: Message?, onDismiss: () -> Unit) {
    val host = LocalChatMedia.current
    val media = message?.media
    // An HD photo's full-size copy is shown once it's on this phone; until then the normal one.
    val hdHere = media?.hd == true && (message.isOutgoing || media.fileState == com.sotto.app.domain.model.FileState.READY)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val normal by rememberPhoto(messageId, maxSide = 2048)
        val full by rememberPhoto(messageId, maxSide = 4096, hd = hdHere)
        val photo = if (hdHere) full ?: normal else normal
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale == 1f) {
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val p = photo
            if (p != null) {
                Image(
                    p,
                    contentDescription = "Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close photo", tint = Color.White)
            }
            if (message != null && media?.hd == true && !hdHere) {
                val progress = host?.fileProgress?.value?.get(messageId + "-hd")
                val gone = media.fileState == com.sotto.app.domain.model.FileState.GONE
                val queued = media.fileState == com.sotto.app.domain.model.FileState.DOWNLOADING
                Text(
                    text = when {
                        progress != null -> "Downloading HD ${(progress * 100).toInt()}%"
                        queued -> "Downloading HD…"
                        gone -> "The HD copy is no longer available"
                        else -> "Download HD · ${byteSize(media.size)}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable(enabled = progress == null && !gone && !queued) { host?.onFile?.invoke(message) }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** The composer's attach button: a photo (inline) or any file (through the relay's store). */
@Composable
internal fun AttachButton(enabled: Boolean, onPhoto: () -> Unit, onFile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.AttachFile,
                contentDescription = "Attach a photo or file",
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Photo") },
                leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                onClick = {
                    open = false
                    onPhoto()
                },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("File or video") },
                leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                onClick = {
                    open = false
                    onFile()
                },
            )
        }
    }
}

/** Where a voice recording is: held down, locked hands-free, or not at all. */
enum class RecordState { IDLE, HOLDING, LOCKED }

/**
 * The microphone in place of Send while the composer is empty. Hold to record, let go to
 * send, slide left to cancel, slide up to lock and keep recording hands-free, as in
 * WhatsApp. [onDrag] reports how far it's slid (px, negative = left/up) for the bar.
 */
@Composable
internal fun MicButton(
    enabled: Boolean,
    state: RecordState,
    onPress: () -> Boolean,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
) {
    val aurora = Aurora.colors
    val density = LocalDensity.current
    val cancelAt = with(density) { CancelSlide.toPx() }
    val lockAt = with(density) { LockSlide.toPx() }
    val holding = state == RecordState.HOLDING
    val grow by animateFloatAsState(if (holding) 1.35f else 1f, label = "micGrow")
    Box(
        modifier = Modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
            }
            .clip(CircleShape)
            .background(
                if (enabled) Brush.linearGradient(aurora.brand) else androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                ),
            )
            .semantics { contentDescription = "Hold to record a voice message" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!onPress()) return@awaitEachGesture
                    down.consume()
                    var done = false
                    while (!done) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        change.consume()
                        if (!change.pressed) {
                            onRelease()
                            done = true
                        } else if (dx < -cancelAt) {
                            onCancel()
                            done = true
                        } else if (dy < -lockAt) {
                            onLock()
                            done = true
                        } else {
                            onDrag(dx.coerceAtMost(0f), dy.coerceAtMost(0f))
                        }
                    }
                    // A cancel or lock ends the hold; swallow the rest of this touch.
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Mic,
            contentDescription = null,
            tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp),
        )
    }
}

private val CancelSlide = 120.dp
private val LockSlide = 90.dp

/**
 * Takes the text field's place while recording: a pulsing dot, the time, and either
 * "‹ Slide to cancel" with a lock hint (held), or a Delete button (locked).
 */
@Composable
internal fun RecordingBar(
    state: RecordState,
    elapsedMs: Long,
    level: Float,
    slideX: Float,
    slideY: Float,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pulse by animateFloatAsState(0.55f + 0.45f * level, label = "recLevel")
        Box(
            Modifier
                .size(12.dp)
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
                .clip(CircleShape)
                .background(colors.error),
        )
        Spacer(Modifier.width(10.dp))
        Text(clock(elapsedMs), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        Spacer(Modifier.weight(1f))
        if (state == RecordState.LOCKED) {
            // Send is the big button beside the bar.
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete recording", tint = colors.error)
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset { IntOffset((slideX * 0.6f).roundToInt(), 0) },
            ) {
                Text("‹ Slide to cancel", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.offset { IntOffset(0, (slideY * 0.4f).roundToInt()) },
                ) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Icon(Icons.Rounded.Lock, contentDescription = "Slide up to lock", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * A picked photo before it's sent, as in WhatsApp: the photo, an HD switch at the top
 * (off: the shrunk photo only; on: also its full-size copy), and the caption with Send.
 * Drawn over the chat in the app's own window (not a dialog), so the system bars and the
 * keyboard are kept clear the same way the composer keeps them clear.
 */
@Composable
fun PhotoSendScreen(
    uri: android.net.Uri,
    initialCaption: String,
    initialHd: Boolean,
    onSend: (caption: String, hd: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var caption by remember { mutableStateOf(initialCaption) }
    var hd by remember { mutableStateOf(initialHd) }
    val preview by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.Default) {
            runCatching { com.sotto.app.media.PhotoShrinker.decodeUpright(context, uri, 2048)?.asImageBitmap() }.getOrNull()
        }
    }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            // Touches stop here: nothing reaches the chat underneath.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Don't send", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            // The quality switch: HD sends the full-size copy too.
            val hdColors = if (hd) Color.White to Color.Black else Color.White.copy(alpha = 0.16f) to Color.White
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(hdColors.first)
                    .clickable(role = Role.Switch, onClickLabel = if (hd) "Send in standard quality" else "Send in HD") { hd = !hd }
                    .semantics { contentDescription = if (hd) "HD on" else "HD off" }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.Hd, contentDescription = null, tint = hdColors.second, modifier = Modifier.size(26.dp))
            }
        }
        // The photo takes whatever room is left between the two bars.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val p = preview
            if (p != null) {
                Image(p, contentDescription = "Photo to send", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.TextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = { Text("Add a caption") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(Aurora.colors.brand))
                    .clickable(role = Role.Button, onClickLabel = "Send photo") { onSend(caption.trim(), hd) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Send photo", tint = Color.White)
            }
        }
    }
}

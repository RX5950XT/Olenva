package app.olenva.scan

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.olenva.R
import app.olenva.container
import app.olenva.ui.DarkSystemBars
import app.olenva.data.Attachment
import app.olenva.data.AttachmentKind
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private val Ink = Color(0xFF14120F)
private val Paper = Color(0xFFF6F1E7)
private val Guide = Color(0xFFB9CDE6)

val ENHANCE_MODES = listOf("auto", "original", "color", "gray", "bw")

@Composable
fun enhanceLabel(mode: String) = stringResource(
    when (mode) {
        "original" -> R.string.mode_original
        "color" -> R.string.mode_color
        "gray" -> R.string.mode_gray
        "bw" -> R.string.mode_bw
        else -> R.string.mode_auto
    }
)

/**
 * Preview of one attachment. When [editable], images can be re-cropped, rotated and switched between
 * enhancement modes (always re-rendered from the untouched original); [onChange] receives the result.
 */
@Composable
fun AttachmentEditor(
    attachment: Attachment,
    editable: Boolean,
    onChange: (Attachment) -> Unit,
    onDelete: (() -> Unit)?,
    onRetake: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pipeline = remember { context.container.scan }
    val store = context.container.attachments
    var current by remember { mutableStateOf(attachment) }
    var busy by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun apply(quad: Quad?, rotation: Int, mode: String) {
        busy = true
        scope.launch {
            try {
                current = pipeline.reprocess(current, quad, rotation, mode)
                onChange(current)
                failed = false
            } catch (e: Exception) {
                failed = true
            }
            busy = false
        }
    }

    BackHandler { if (cropping) cropping = false else onClose() }
    DarkSystemBars()

    Surface(color = Ink, contentColor = Paper, modifier = Modifier.fillMaxSize()) {
        if (cropping) {
            CropEditor(
                attachment = current,
                onCancel = { cropping = false },
                onApply = { q -> cropping = false; apply(q, current.rotation, current.mode ?: "auto") },
            )
            return@Surface
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(current.name, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleSmall)
                if (onRetake != null) IconButton(onClick = onRetake) { Icon(Icons.Default.PhotoCamera, stringResource(R.string.retake)) }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (current.kind) {
                    AttachmentKind.IMAGE -> ZoomableImage(store.file(current.path))
                    AttachmentKind.PDF -> PdfPreview(store.file(current.path))
                    AttachmentKind.TEXT -> TextPreview(store.file(current.path))
                }
                if (busy) CircularProgressIndicator(color = Paper)
            }
            if (failed) {
                Text(stringResource(R.string.process_failed), Modifier.padding(16.dp), color = Color(0xFFE8B86A))
            }
            if (editable && current.kind == AttachmentKind.IMAGE) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val activeMode = current.mode ?: "original"
                    ENHANCE_MODES.forEach { m ->
                        val on = m == activeMode
                        Text(
                            enhanceLabel(m),
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) Paper else Color.White.copy(alpha = .08f))
                                .clickable(enabled = !busy && !on) { apply(current.quad?.toFloatArray(), current.rotation, m) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            color = if (on) Ink else Paper,
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { cropping = true }, enabled = !busy) {
                        Icon(Icons.Default.Crop, null, tint = Paper); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.crop), color = Paper)
                    }
                    TextButton(onClick = { apply(current.quad?.toFloatArray(), (current.rotation + 90) % 360, current.mode ?: "original") }, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, null, tint = Paper); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.rotate), color = Paper)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}

@Composable
fun ZoomableImage(file: java.io.File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
    )
}

@Composable
private fun PdfPreview(file: java.io.File) {
    val pages by produceState<List<Bitmap>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        (0 until minOf(r.pageCount, 20)).map { i ->
                            r.openPage(i).use { p ->
                                val w = 1200
                                val h = (w.toFloat() * p.height / p.width).toInt()
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
    val list = pages
    when {
        list == null -> CircularProgressIndicator(color = Paper)
        list.isEmpty() -> Text(stringResource(R.string.pdf_preview_failed))
        else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list) { bmp ->
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentScale = ContentScale.FillWidth)
            }
        }
    }
}

@Composable
private fun TextPreview(file: java.io.File) {
    val text by produceState("", file) { value = withContext(Dispatchers.IO) { runCatching { file.readText().take(200_000) }.getOrDefault("") } }
    SelectionContainer {
        Text(
            text,
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Paper,
        )
    }
}

/** Manual crop: drag the four corners on the original photo. */
@Composable
private fun CropEditor(attachment: Attachment, onCancel: () -> Unit, onApply: (Quad?) -> Unit) {
    val context = LocalContext.current
    val pipeline = remember { context.container.scan }
    val scope = rememberCoroutineScope()
    val bitmap by produceState<Bitmap?>(null, attachment.id) {
        value = withContext(Dispatchers.Default) { pipeline.decode(attachment.originalPath ?: attachment.path, 1600) }
    }
    val full = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    var quad by remember { mutableStateOf(attachment.quad?.toFloatArray() ?: full.copyOf()) }
    LaunchedEffect(Unit) {
        if (attachment.quad == null) pipeline.detect(attachment)?.let { quad = it }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Text(
            stringResource(R.string.crop_hint),
            Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Paper.copy(alpha = .85f),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            val bmp = bitmap
            if (bmp == null) {
                CircularProgressIndicator(color = Paper)
                return@BoxWithConstraints
            }
            val density = LocalDensity.current
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val s = minOf(boxW / bmp.width, boxH / bmp.height)
            val w = bmp.width * s
            val h = bmp.height * s
            Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                var dragging by remember { mutableStateOf(-1) }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                dragging = (0 until 4).minBy { hypot(quad[it * 2] * size.width - pos.x, quad[it * 2 + 1] * size.height - pos.y) }
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                        ) { change, drag ->
                            change.consume()
                            val i = dragging
                            if (i >= 0) {
                                quad = quad.copyOf().also {
                                    it[i * 2] = (it[i * 2] + drag.x / size.width).coerceIn(0f, 1f)
                                    it[i * 2 + 1] = (it[i * 2 + 1] + drag.y / size.height).coerceIn(0f, 1f)
                                }
                            }
                        }
                    },
                ) {
                    val pts = List(4) { Offset(quad[it * 2] * size.width, quad[it * 2 + 1] * size.height) }
                    val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                    drawPath(path, Guide.copy(alpha = .14f))
                    drawPath(path, Guide, style = Stroke(2.5f * density.density))
                    pts.forEachIndexed { i, p ->
                        drawCircle(Color.Black.copy(alpha = .35f), 16f * density.density, p)
                        drawCircle(if (i == dragging) Color(0xFFF3D27A) else Guide, 11f * density.density, p, style = Stroke(3f * density.density))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = Paper) }
            TextButton(onClick = { scope.launch { pipeline.detect(attachment)?.let { quad = it } } }) {
                Text(stringResource(R.string.crop_auto), color = Paper)
            }
            TextButton(onClick = { quad = full.copyOf() }) { Text(stringResource(R.string.crop_full), color = Paper) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onApply(if (quad.contentEquals(full)) null else DocDetector.order(quad)) }) { Text(stringResource(R.string.apply)) }
        }
    }
}

private fun DocDetector.order(q: Quad): Quad {
    val pts = Array(4) { org.opencv.core.Point(q[it * 2].toDouble(), q[it * 2 + 1].toDouble()) }
    val o = order(pts)
    return FloatArray(8) { if (it % 2 == 0) o[it / 2].x.toFloat() else o[it / 2].y.toFloat() }
}

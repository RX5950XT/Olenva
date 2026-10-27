package app.olenva.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.olenva.R
import app.olenva.container
import app.olenva.ui.DarkSystemBars
import app.olenva.data.Attachment
import app.olenva.data.AppSettings
import app.olenva.data.rotate
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.Executors

private val Ink = Color(0xFF14120F)
private val Paper = Color(0xFFF6F1E7)
private val Guide = Color(0xFFB9CDE6)

/**
 * Full-screen in-app camera. Auto mode tracks the page and shoots when it is steady;
 * results are handed back as attachments — never sent anywhere.
 */
@Composable
fun ScannerScreen(
    settings: AppSettings,
    onDone: (List<Attachment>) -> Unit,
    onClose: () -> Unit,
    onEditPage: (Attachment, (Attachment?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    DarkSystemBars()
    Surface(color = Ink, contentColor = Paper, modifier = Modifier.fillMaxSize()) {
        when {
            granted -> CameraContent(settings, onDone, onClose, onEditPage)
            denied -> PermissionDenied(onClose)
            else -> Box(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PermissionDenied(onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.camera_denied_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.camera_denied_body), style = MaterialTheme.typography.bodyMedium, color = Paper.copy(alpha = .8f))
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text(stringResource(R.string.open_settings)) }
        TextButton(onClick = onClose) { Text(stringResource(R.string.back_to_chat), color = Paper) }
    }
}

@Composable
private fun CameraContent(
    settings: AppSettings,
    onDone: (List<Attachment>) -> Unit,
    onClose: () -> Unit,
    onEditPage: (Attachment, (Attachment?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val pipeline = remember { context.container.scan }
    val orientation = LocalConfiguration.current.orientation

    var auto by remember { mutableStateOf(settings.autoScan) }
    var multi by remember { mutableStateOf(settings.multiPage) }
    var frame by remember { mutableStateOf(ScanFrame()) }
    var processing by remember { mutableIntStateOf(0) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var zoomRange by remember { mutableStateOf(1f..1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var retakeIndex by remember { mutableStateOf<Int?>(null) }
    val pages = remember { mutableStateListOf<Attachment>() }
    val sound = remember { MediaActionSound() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val captureFailed = stringResource(R.string.scan_capture_failed)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember(orientation) {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ratio43())
            .build()
    }

    lateinit var analyzer: DocAnalyzer
    fun capture(hint: Quad?) {
        if (settings.haptics) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        }
        if (settings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        processing++
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val upright = try {
                    image.toBitmap().rotate(image.imageInfo.rotationDegrees)
                } finally {
                    image.close()
                }
                analyzer.paused = false
                scope.launch {
                    try {
                        val mode = if (settings.autoEnhance) settings.enhanceMode else "original"
                        val a = pipeline.fromCapture(upright, hint, settings.autoCrop, mode)
                        val idx = retakeIndex
                        when {
                            idx != null && idx < pages.size -> {
                                context.container.attachments.delete(pages[idx])
                                pages[idx] = a
                                retakeIndex = null
                            }
                            multi -> pages.add(a)
                            else -> { processing--; onDone(listOf(a)); return@launch }
                        }
                    } catch (e: Exception) {
                        Log.e("Scanner", "processing failed", e)
                        error = captureFailed
                    }
                    processing--
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("Scanner", "capture failed", exception)
                analyzer.paused = false
                analyzer.rearm()
                scope.launch { processing--; error = captureFailed }
            }
        })
    }

    analyzer = remember {
        DocAnalyzer(
            onFrame = { f -> scope.launch { frame = f } },
            onTrigger = { q -> scope.launch { capture(q) } },
        )
    }
    analyzer.auto = auto

    LaunchedEffect(orientation) {
        val provider = suspendCancellableCoroutine { cont ->
            val f = ProcessCameraProvider.getInstance(context)
            f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(context))
        }
        val preview = Preview.Builder().setResolutionSelector(ratio43()).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(android.util.Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor, analyzer) }
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture)
            camera = cam
            val zs = cam.cameraInfo.zoomState.value
            if (zs != null) zoomRange = zs.minZoomRatio..zs.maxZoomRatio
            val target = if (settings.preferWide) zoomRange.start else 1f
            cam.cameraControl.setZoomRatio(target)
            zoom = target
        } catch (e: Exception) {
            Log.e("Scanner", "bind failed", e)
            error = context.getString(R.string.camera_unavailable)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
            sound.release()
        }
    }

    BackHandler { if (pages.isNotEmpty()) confirmDiscard = true else onClose() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Top controls
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (pages.isNotEmpty()) confirmDiscard = true else onClose() }) {
                Icon(Icons.Default.Close, stringResource(R.string.close))
            }
            Spacer(Modifier.weight(1f))
            Segmented(
                options = listOf(stringResource(R.string.scan_auto), stringResource(R.string.scan_manual)),
                selected = if (auto) 0 else 1,
                onSelect = { auto = it == 0 },
            )
            Spacer(Modifier.width(8.dp))
            Segmented(
                options = listOf(stringResource(R.string.scan_single), stringResource(R.string.scan_multi)),
                selected = if (multi) 1 else 0,
                onSelect = { multi = it == 1 },
            )
            Spacer(Modifier.weight(1f))
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) }) {
                    Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, stringResource(R.string.torch))
                }
            } else Spacer(Modifier.size(48.dp))
        }

        // Viewfinder
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = frame.frameW.toFloat() / frame.frameH
            Box(Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = ratio > 1f)) {
                AndroidView({ previewView }, Modifier.fillMaxSize())
                QuadOverlay(
                    frame = frame,
                    modifier = Modifier.fillMaxSize().pointerInput(camera) {
                        detectTapGestures { pos ->
                            val point = previewView.meteringPointFactory.createPoint(pos.x, pos.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        }
                    },
                )
                StatusPill(
                    text = statusText(frame.status, auto, processing > 0, retakeIndex != null),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }
        }

        // Zoom
        ZoomChips(zoomRange, zoom) { z ->
            zoom = z
            camera?.cameraControl?.setZoomRatio(z)
        }

        // Bottom: pages strip, shutter, done
        Row(
            Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (multi || pages.isNotEmpty()) {
                    PageStrip(
                        pages = pages,
                        onPreview = { i -> onEditPage(pages[i]) { updated -> if (updated == null) pages.removeAt(i) else pages[i] = updated } },
                        onRetake = { i -> retakeIndex = i; analyzer.rearm() },
                        onMove = { i, d ->
                            val j = i + d
                            if (j in pages.indices) { val p = pages[i]; pages[i] = pages[j]; pages[j] = p }
                        },
                        onDelete = { i -> context.container.attachments.delete(pages.removeAt(i)) },
                    )
                }
            }
            Shutter(busy = processing > 0 && !multi) {
                analyzer.disarm(analyzer.currentSignature())
                capture(if (settings.autoCrop) analyzer.lastQuad() else null)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (pages.isNotEmpty()) {
                    Button(
                        onClick = { onDone(pages.toList()) },
                        enabled = processing == 0,
                    ) { Text(stringResource(R.string.scan_done, pages.size)) }
                }
            }
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.ok)) } },
            text = { Text(msg) },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.scan_discard_title, pages.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone(pages.toList())
                }) { Text(stringResource(R.string.scan_keep_pages)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pages.forEach { context.container.attachments.delete(it) }
                    pages.clear()
                    confirmDiscard = false
                    onClose()
                }) { Text(stringResource(R.string.discard)) }
            },
        )
    }
}

private fun ratio43() = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()

@Composable
private fun statusText(s: ScanStatus, auto: Boolean, busy: Boolean, retake: Boolean): String = when {
    busy -> stringResource(R.string.scan_processing)
    retake && s != ScanStatus.HOLD -> stringResource(R.string.scan_retake_hint)
    !auto -> stringResource(if (s == ScanStatus.SEARCHING) R.string.scan_manual_hint else R.string.scan_detected_manual)
    else -> stringResource(
        when (s) {
            ScanStatus.SEARCHING -> R.string.scan_searching
            ScanStatus.DETECTED -> R.string.scan_detected
            ScanStatus.HOLD -> R.string.scan_hold
            ScanStatus.ADJUST -> R.string.scan_adjust
            ScanStatus.BLURRY -> R.string.scan_blurry
            ScanStatus.NEXT_PAGE -> R.string.scan_next_page
            ScanStatus.CAPTURING -> R.string.scan_capturing
            ScanStatus.PROCESSING -> R.string.scan_processing
        }
    )
}

@Composable
private fun QuadOverlay(frame: ScanFrame, modifier: Modifier) {
    Canvas(modifier) {
        val q = frame.quad ?: return@Canvas
        val pts = List(4) { Offset(q[it * 2] * size.width, q[it * 2 + 1] * size.height) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        val warn = frame.status == ScanStatus.ADJUST || frame.status == ScanStatus.BLURRY
        val color = if (warn) Color(0xFFE8B86A) else Guide
        val p = if (frame.status == ScanStatus.HOLD) frame.progress else 0f
        drawPath(path, color.copy(alpha = 0.10f + 0.18f * p))
        drawPath(path, color, style = Stroke(width = (2f + 2.5f * p) * density, join = StrokeJoin.Round))
        pts.forEach { drawCircle(color, radius = 5f * density, center = it) }
    }
}

@Composable
private fun StatusPill(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier
            .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .semantics { contentDescription = text },
        color = Paper,
        fontSize = 14.sp,
    )
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .10f)).padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Paper else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = if (on) Ink else Paper,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ZoomChips(range: ClosedFloatingPointRange<Float>, current: Float, onZoom: (Float) -> Unit) {
    val stops = buildList {
        if (range.start < 0.95f) add(range.start)
        add(1f)
        if (range.endInclusive >= 2f) add(2f)
    }
    if (stops.size < 2) return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        stops.forEach { z ->
            val on = kotlin.math.abs(current - z) < 0.05f
            val label = if (z < 1f) "%.1f×".format(z) else "${z.toInt()}×"
            Box(
                Modifier
                    .padding(horizontal = 5.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (on) Color.White.copy(alpha = .22f) else Color.White.copy(alpha = .08f))
                    .clickable { onZoom(z) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color(0xFFF3D27A) else Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Shutter(busy: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.shutter)
    Box(
        Modifier
            .size(76.dp)
            .border(3.dp, Paper, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(Paper)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = Ink, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun PageStrip(
    pages: List<Attachment>,
    onPreview: (Int) -> Unit,
    onRetake: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    val store = LocalContext.current.container.attachments
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        if (pages.isEmpty()) {
            Text(stringResource(R.string.scan_multi_hint), color = Paper.copy(alpha = .7f), fontSize = 12.sp)
        }
        pages.forEachIndexed { i, a ->
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.padding(end = 6.dp)) {
                AsyncImage(
                    model = store.file(a.path),
                    contentDescription = stringResource(R.string.page_n, i + 1),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp, 58.dp).clip(RoundedCornerShape(6.dp)).clickable { menu = true },
                )
                Text(
                    "${i + 1}",
                    Modifier.align(Alignment.BottomEnd).background(Ink.copy(alpha = .7f), RoundedCornerShape(topStart = 6.dp)).padding(horizontal = 4.dp),
                    color = Paper, fontSize = 10.sp,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.preview_edit)) }, leadingIcon = { Icon(Icons.Default.Visibility, null) }, onClick = { menu = false; onPreview(i) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.retake)) }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { menu = false; onRetake(i) })
                    if (i > 0) DropdownMenuItem(text = { Text(stringResource(R.string.move_earlier)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }, onClick = { menu = false; onMove(i, -1) })
                    if (i < pages.lastIndex) DropdownMenuItem(text = { Text(stringResource(R.string.move_later)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) }, onClick = { menu = false; onMove(i, 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete(i) })
                }
            }
        }
    }
}

package com.sotto.app.ui.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sotto.app.R
import com.sotto.app.ui.components.GlassIconButton
import com.sotto.app.ui.components.GlowingOutline
import com.sotto.app.ui.components.GradientButton
import com.sotto.app.ui.components.InfoPill
import com.sotto.app.ui.components.SoftButton
import com.sotto.app.ui.theme.ForcedDarkTheme
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

/** Key under which [ScanQrScreen]'s result goes to the previous screen's SavedStateHandle. */
const val SCANNED_SHARE_CODE_KEY = "scanned_share_code"

/** A share code from an opened invite link: filled in on Add contact, for the user to confirm. */
const val PREFILLED_SHARE_CODE_KEY = "prefilled_share_code"

/** Where the scanner hands back an invite it read (to setup's back-stack entry). */
const val SCANNED_INVITE_KEY = "scanned_invite"

/**
 * Reads someone's share-code QR with the camera. Frames are decoded on the phone (CameraX into
 * zxing) and never stored or sent, so this works without Google Play services.
 */
@Composable
fun ScanQrScreen(
    onBack: () -> Unit,
    onScanned: (value: String) -> Unit,
    /** What a QR must hold: the value to hand back, or null when it isn't one. */
    accept: (String) -> String? = ::shareCodeFromQr,
    title: String = stringResource(R.string.contacts_scan_share_code_title),
    hint: String = stringResource(R.string.contacts_scan_share_code_hint),
    rejectMessage: String = stringResource(R.string.contacts_not_a_share_code_qr),
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    fun cameraAllowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(cameraAllowed()) }
    var permissionAnswered by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionAnswered = true
    }
    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionAnswered) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // Back from the app's system settings, where the camera may have been allowed.
    LifecycleResumeEffect(Unit) {
        hasPermission = cameraAllowed()
        onPauseOrDispose { }
    }

    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_500)
            message = null
        }
    }

    ScanQrContent(
        hasPermission = hasPermission,
        permissionDenied = permissionAnswered && !hasPermission,
        torchOn = torchOn,
        torchAvailable = torchAvailable,
        message = message,
        onBack = onBack,
        onToggleTorch = { torchOn = !torchOn },
        title = title,
        hint = hint,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        camera = {
            CameraScanner(
                torchOn = torchOn,
                onTorchAvailable = { torchAvailable = it },
                onQrText = { text ->
                    if (scanned) return@CameraScanner
                    val value = accept(text)
                    if (value != null) {
                        scanned = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onScanned(value)
                    } else {
                        message = rejectMessage
                    }
                },
            )
        },
    )
}

@Composable
fun ScanQrContent(
    hasPermission: Boolean,
    permissionDenied: Boolean,
    torchOn: Boolean,
    torchAvailable: Boolean,
    message: String?,
    onBack: () -> Unit,
    onToggleTorch: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    title: String = stringResource(R.string.contacts_scan_share_code_title),
    hint: String = stringResource(R.string.contacts_scan_share_code_hint),
    camera: @Composable () -> Unit,
) = ForcedDarkTheme {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasPermission) {
            camera()
            ScanFrame()
        } else {
            CameraPermissionPrompt(
                denied = permissionDenied,
                onRequestPermission = onRequestPermission,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack,
            )
            Spacer(Modifier.weight(1f))
            if (hasPermission && torchAvailable) {
                GlassIconButton(
                    icon = if (torchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    contentDescription = stringResource(if (torchOn) R.string.contacts_flashlight_off else R.string.contacts_flashlight_on),
                    onClick = onToggleTorch,
                )
            }
        }

        if (hasPermission) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedContent(
                    targetState = message,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "scanMessage",
                ) { text ->
                    if (text != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            InfoPill(
                                text = text,
                                icon = Icons.Rounded.ErrorOutline,
                                tint = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Dims everything but a rounded square in the middle and rings it with the brand glow. */
@Composable
private fun ScanFrame() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = minOf(maxWidth, maxHeight) * 0.68f
        val radius = 32.dp
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(Color.Black.copy(alpha = 0.55f))
            val sidePx = side.toPx()
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset((size.width - sidePx) / 2f, (size.height - sidePx) / 2f),
                size = Size(sidePx, sidePx),
                cornerRadius = CornerRadius(radius.toPx()),
                blendMode = BlendMode.Clear,
            )
        }
        GlowingOutline(
            active = true,
            shape = RoundedCornerShape(radius),
            fill = Color.Transparent,
            idleBorder = Color.White.copy(alpha = 0.4f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(side),
        ) {}
    }
}

@Composable
private fun CameraPermissionPrompt(
    denied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.NoPhotography,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.contacts_camera_needed),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.contacts_camera_why),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
        if (denied) {
            Spacer(Modifier.height(24.dp))
            GradientButton(text = stringResource(R.string.contacts_allow_camera), onClick = onRequestPermission, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            SoftButton(text = stringResource(R.string.contacts_open_settings), onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Camera preview plus a frame analyzer. Uses the back camera when there is one; QR text goes to
 * [onQrText] on the main thread.
 */
@Composable
private fun CameraScanner(
    torchOn: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    onQrText: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQrText by rememberUpdatedState(onQrText)
    val currentOnTorchAvailable by rememberUpdatedState(onTorchAvailable)
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, QrAnalyzer { text -> mainExecutor.execute { currentOnQrText(text) } })
            val hasBackCamera = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            val selector = if (hasBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }.onSuccess { bound ->
                camera = bound
                currentOnTorchAvailable(bound.cameraInfo.hasFlashUnit())
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
            analysisExecutor.shutdown()
            camera = null
        }
    }

    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * Decodes each frame's luminance plane on the analysis thread. Reports a text at most once per
 * [REPORT_INTERVAL_MS], so a QR code held in view doesn't fire on every frame.
 */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private var luminance = ByteArray(0)
    private var lastText: String? = null
    private var lastReportedAt = 0L

    override fun analyze(image: ImageProxy) {
        image.use { frame ->
            val plane = frame.planes[0]
            val buffer = plane.buffer
            val size = buffer.remaining()
            if (luminance.size < size) luminance = ByteArray(size)
            buffer.get(luminance, 0, size)
            val text = decodeQr(luminance, frame.width, frame.height, plane.rowStride, reader) ?: return
            val now = System.currentTimeMillis()
            if (text == lastText && now - lastReportedAt < REPORT_INTERVAL_MS) return
            lastText = text
            lastReportedAt = now
            onText(text)
        }
    }
}

private const val REPORT_INTERVAL_MS = 3_000L

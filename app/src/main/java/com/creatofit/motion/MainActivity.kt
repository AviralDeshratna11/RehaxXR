package com.creatofit.motion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.creatofit.motion.core.*
import com.creatofit.motion.render.*
import com.creatofit.motion.tracking.TrackingController
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

// ---- Design tokens --------------------------------------------------------------------------------
private val Bg = Color(0xFFF5F6F8)
private val CardBg = Color.White
private val Line = Color(0xFFE5E7EB)
private val Ink = Color(0xFF0F172A)
private val Body = Color(0xFF334155)
private val Muted = Color(0xFF64748B)
private val Faint = Color(0xFF94A3B8)
private val Accent = Color(0xFF2563EB)
private val AccentSoft = Color(0xFFEFF4FF)
private val Live = Color(0xFF16A34A)
private val Amber = Color(0xFFD97706)
private val Stage = Color(0xFFF1F3F6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Accent, background = Bg, surface = CardBg, onSurface = Ink)) {
                CompositionLocalProvider(LocalContentColor provides Ink) { MotionApp() }
            }
        }
    }
}

private enum class Tab(val label: String) { MUSCLES("Muscles"), JOINTS("Joints"), SETTINGS("Settings") }

private data class Hud(val status: String = "Ready", val tone: Color = Faint, val analysis: MuscleAnalysis = MuscleAnalysis.EMPTY,
                       val tracking: Boolean = false, val fps: Float = 0f, val orbited: Boolean = false)

@Composable
private fun MotionApp() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val controller = remember { TrackingController(context.applicationContext) }
    val preferences = remember { context.getSharedPreferences("tracking", Context.MODE_PRIVATE) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var desired by rememberSaveable { mutableStateOf(true) }
    var front by rememberSaveable { mutableStateOf(true) }
    var skeleton by rememberSaveable { mutableStateOf(true) }
    var highlights by rememberSaveable { mutableStateOf(true) }
    var callouts by rememberSaveable { mutableStateOf(true) }
    // Debug builds accept `--ez demo true` (and `--es tab SETTINGS`) for automated UI checks.
    val launch = (context as? ComponentActivity)?.intent
    var demo by rememberSaveable { mutableStateOf(BuildConfig.DEBUG && launch?.getBooleanExtra("demo", false) == true) }
    var gpu by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableStateOf(Tab.entries.firstOrNull { BuildConfig.DEBUG && it.name == launch?.getStringExtra("tab") } ?: Tab.MUSCLES) }
    val initialModel = remember {
        // v0.5 switches the default to MediaPipe Full (3D landmarks) once; later choices are kept.
        if (!preferences.getBoolean("v05Model", false)) {
            preferences.edit().putBoolean("v05Model", true).putString("model", PoseModel.FULL.name).apply()
            PoseModel.FULL.name
        } else PoseModel.fromSetting(preferences.getString("model", null)).name
    }
    var modelName by rememberSaveable { mutableStateOf(initialModel) }
    val model = PoseModel.fromSetting(modelName)
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var hud by remember { mutableStateOf(Hud()) }
    var metrics by remember { mutableStateOf(controller.metrics.snapshot(SystemClock.uptimeMillis())) }
    var thermal by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var captureNote by remember { mutableStateOf<String?>(null) }
    var pendingCapture by remember { mutableStateOf<String?>(null) }
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var avatarView by remember { mutableStateOf<AvatarView?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it; asked = true }
    val exportCapture = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = pendingCapture
        if (uri != null && data != null) captureNote = runCatching {
            checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(data) }
            "Markers saved. Use tools/opensim_bridge.py on a computer to convert them for OpenSim."
        }.getOrElse { "Export failed: ${it.message}" }
        pendingCapture = null
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(demo) { controller.demo = demo }
    val active = permission && desired && foreground && !demo
    DisposableEffect(active, front, gpu, modelName) {
        if (active) controller.start(owner, preview, front, gpu, model)
        val window = (context as ComponentActivity).window
        if (active || demo) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { controller.stop(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(controller) {
        while (true) {
            val now = SystemClock.uptimeMillis()
            val s = controller.displaySnapshot(now)
            val age = if (s == null) Long.MAX_VALUE else now - s.pose.capturedMs
            val fresh = s != null && (active || demo) && age < controller.uncertaintyMs
            val (status, tone) = when {
                demo -> "Demo" to Accent
                !permission -> "Camera off" to Faint
                !desired -> "Paused" to Faint
                fresh -> "Live" to Live
                s != null && age < controller.lostTimeoutMs -> "Re-acquiring" to Amber
                active -> "Looking for you" to Amber
                else -> "Ready" to Faint
            }
            metrics = controller.metrics.snapshot(now)
            hud = Hud(status, tone, if (fresh) s!!.analysis else MuscleAnalysis.EMPTY, fresh, metrics.poseFps, avatarView?.isOrbited == true)
            if (recording && !controller.capture.recording) {
                recording = false; pendingCapture = controller.capture.finish()
                if (pendingCapture != null) exportCapture.launch("creatofit-motion.json")
            }
            if (Build.VERSION.SDK_INT >= 29) thermal = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
            delay(160)
        }
    }

    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = windowSize.width > windowSize.height
    Column(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(hud, active || demo, onToggle = {
            when {
                demo -> demo = false
                !permission -> permissionLauncher.launch(Manifest.permission.CAMERA)
                else -> desired = !desired
            }
        })
        val camera: @Composable (Modifier) -> Unit = { m ->
            CameraCard(m, permission, active, asked, front, demo, preview, controller, skeleton,
                onEnable = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                onFlip = { front = !front })
        }
        val avatar: @Composable (Modifier) -> Unit = { m ->
            AvatarCard(m, foreground, controller, active || demo, highlights, callouts, hud, onView = { avatarView = it },
                onReset = { avatarView?.resetView() }, onTurn = { avatarView?.turnAround() })
        }
        val panel: @Composable (Modifier) -> Unit = { m ->
            Panel(m, tab, { tab = it }, hud) {
                SettingsTab(front, { front = it }, model, { modelName = it.name; preferences.edit().putString("model", it.name).apply() },
                    gpu, { gpu = it }, skeleton, { skeleton = it }, highlights, { highlights = it }, callouts, { callouts = it },
                    demo, { demo = it }, recording, onCapture = {
                        if (recording) {
                            recording = false; pendingCapture = controller.capture.finish()
                            if (pendingCapture != null) exportCapture.launch("creatofit-motion.json")
                            else captureNote = "No motion to export. Keep your joints visible and record at least two poses."
                        } else if (!model.hasWorldLandmarks) captureNote = "MoveNet estimates 2D joints only. Choose a MediaPipe model to export 3D markers."
                        else if (!active) captureNote = "Start the camera before recording markers."
                        else { controller.capture.start(); recording = true }
                    }, diagnostics = report(controller, metrics, thermal))
            }
        }
        if (landscape) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                camera(Modifier.weight(1f).fillMaxHeight())
                avatar(Modifier.weight(1.15f).fillMaxHeight())
                panel(Modifier.width(360.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.weight(.92f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    camera(Modifier.weight(.85f).fillMaxHeight())
                    avatar(Modifier.weight(1.15f).fillMaxHeight())
                }
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    captureNote?.let { note ->
        AlertDialog(onDismissRequest = { captureNote = null }, containerColor = CardBg, title = { Text("Marker export", fontWeight = FontWeight.SemiBold) },
            text = { Text(note, color = Body) }, confirmButton = { TextButton(onClick = { captureNote = null }) { Text("OK") } })
    }
}

// ---- Top bar ----------------------------------------------------------------------------------------
@Composable
private fun TopBar(hud: Hud, running: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Ink), contentAlignment = Alignment.Center) {
            Text("C", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(6.dp).background(Color(0xFFFF7A3D), CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        // The title yields space first so the status pill and the Pause button never get clipped
        // (large system font sizes on narrow phones).
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Ink)) { append("Creatofit") }
            withStyle(SpanStyle(color = Muted)) { append(" Motion") }
        }, Modifier.weight(1f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        StatusPill(hud.status, hud.tone, if (hud.tracking && hud.fps > 0) "${hud.fps.roundToInt()} fps" else null)
        Spacer(Modifier.width(8.dp))
        Button(onClick = onToggle, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Color.White), modifier = Modifier.height(34.dp)) {
            Text(if (running) "Pause" else "Start", fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun StatusPill(text: String, tone: Color, detail: String?) {
    val color by animateColorAsState(tone, label = "status")
    Row(Modifier.clip(RoundedCornerShape(50)).background(CardBg).border(1.dp, Line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink, maxLines = 1, softWrap = false)
        if (detail != null) Text("  $detail", fontSize = 12.sp, color = Muted, maxLines = 1, softWrap = false)
    }
}

// ---- Cards --------------------------------------------------------------------------------------------
@Composable
private fun Card(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(CardBg).border(1.dp, Line, RoundedCornerShape(18.dp)), content = content)
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, dark: Boolean = false) {
    Text(text, modifier.clip(RoundedCornerShape(7.dp)).background(if (dark) Color(0x99000000) else Color(0xF2FFFFFF))
        .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp,
        color = if (dark) Color.White else Ink)
}

@Composable
private fun CameraCard(modifier: Modifier, permission: Boolean, active: Boolean, asked: Boolean, front: Boolean, demo: Boolean,
                       preview: PreviewView, controller: TrackingController, skeleton: Boolean,
                       onEnable: () -> Unit, onSettings: () -> Unit, onFlip: () -> Unit) {
    Card(modifier) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
            if (permission && !demo) {
                AndroidView(factory = { ctx -> FrameLayout(ctx).apply {
                    (preview.parent as? android.view.ViewGroup)?.removeView(preview)
                    addView(preview, FrameLayout.LayoutParams(-1, -1))
                    addView(LandmarkOverlay(ctx, controller), FrameLayout.LayoutParams(-1, -1))
                } }, modifier = Modifier.fillMaxSize(), update = { v ->
                    (v.getChildAt(1) as LandmarkOverlay).apply { mirrored = front; enabledOverlay = skeleton && active }
                })
                if (!active) Text("Paused", Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(Color(0xCC0F172A))
                    .padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp)
            } else if (demo) {
                Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Demo motion", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Squats, raises, curls and push-ups are being simulated. Turn off the demo in Settings to use the camera.",
                        color = Color(0xFFB6C0CC), fontSize = 12.sp, lineHeight = 16.sp)
                }
            } else {
                Column(Modifier.align(Alignment.Center).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Camera access", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Frames are analysed on this phone and never saved or uploaded.", color = Color(0xFFB6C0CC), fontSize = 12.sp, lineHeight = 16.sp)
                    Button(onClick = onEnable, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink)) {
                        Text(if (asked) "Try again" else "Enable camera", fontSize = 13.sp)
                    }
                    if (asked) TextButton(onClick = onSettings) { Text("Open app settings", color = Color.White, fontSize = 12.sp) }
                }
            }
            Chip(if (demo) "SIMULATION" else "CAMERA", Modifier.align(Alignment.TopStart).padding(10.dp), dark = true)
            if (permission && !demo) Text("Flip", Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0x99000000)).clickable(onClick = onFlip).padding(horizontal = 10.dp, vertical = 5.dp),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (permission && !demo) Text(if (front) "Front camera · mirrored" else "Rear camera", Modifier.align(Alignment.BottomStart).padding(10.dp),
                color = Color(0xCCFFFFFF), fontSize = 10.sp)
        }
    }
}

@Composable
private fun AvatarCard(modifier: Modifier, foreground: Boolean, controller: TrackingController, running: Boolean, highlights: Boolean,
                       callouts: Boolean, hud: Hud, onView: (AvatarView?) -> Unit, onReset: () -> Unit, onTurn: () -> Unit) {
    Card(modifier) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFFAFBFC), Stage)))) {
            if (foreground) AndroidView(factory = { ctx ->
                FrameLayout(ctx).apply {
                    val labels = LabelOverlay(ctx)
                    val avatar = AvatarView(ctx, controller).also { it.labels = labels; onView(it) }
                    addView(avatar, FrameLayout.LayoutParams(-1, -1))
                    addView(labels, FrameLayout.LayoutParams(-1, -1))
                }
            }, modifier = Modifier.fillMaxSize(), update = { v ->
                (v.getChildAt(0) as AvatarView).apply { musclesVisible = highlights; this.running = running }
                (v.getChildAt(1) as LabelOverlay).enabledLabels = callouts && highlights
            }, onRelease = { onView(null) })
            Chip("3D REPLICA", Modifier.align(Alignment.TopStart).padding(10.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hud.orbited) SmallButton("Reset", onReset)
                SmallButton("Turn", onTurn)
            }
            // While nobody is tracked the legend is meaningless, so the hint takes its place below the feet.
            if (!hud.tracking && !controller.demo) Text(if (running) "Step back to show your full body" else "Tap Start to begin",
                Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xE6FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 5.dp), color = Muted, fontSize = 10.sp, lineHeight = 13.sp,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else HeatLegend(Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(CardBg).border(1.dp, Line, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp), color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun HeatLegend(modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xE6FFFFFF)).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Light", fontSize = 9.sp, color = Muted, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(5.dp))
        Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
            .background(Brush.horizontalGradient(listOf(Color(Heat.argb(0f)), Color(Heat.argb(.5f)), Color(Heat.argb(1f))))))
        Spacer(Modifier.width(5.dp))
        Text("High", fontSize = 9.sp, color = Muted, maxLines = 1, softWrap = false)
    }
}

// ---- Panel ----------------------------------------------------------------------------------------------
@Composable
private fun Panel(modifier: Modifier, tab: Tab, onTab: (Tab) -> Unit, hud: Hud, settings: @Composable () -> Unit) {
    Card(modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(11.dp)).background(Stage).padding(3.dp)) {
                Tab.entries.forEach { t ->
                    val selected = t == tab
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (selected) CardBg else Color.Transparent)
                        .then(if (selected) Modifier.border(1.dp, Line, RoundedCornerShape(9.dp)) else Modifier)
                        .clickable { onTab(t) }.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text(t.label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) Ink else Muted)
                    }
                }
            }
            HorizontalDivider(color = Line)
            when (tab) {
                Tab.MUSCLES -> MusclesTab(hud)
                Tab.JOINTS -> JointsTab(hud)
                Tab.SETTINGS -> settings()
            }
        }
    }
}

@Composable
private fun MusclesTab(hud: Hud) {
    val a = hud.analysis
    LazyColumnCompat {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hud.tracking) a.movement.title else "Waiting for you", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                        modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hud.tracking) { Spacer(Modifier.width(8.dp)); Tag(a.movement.phase, Accent, AccentSoft) }
                }
                Spacer(Modifier.height(3.dp))
                Text(if (hud.tracking) (if (a.movement.support.isNotEmpty()) "Support · ${a.movement.support}" else "No floor contact detected")
                    else "Stand 2–3 m from the phone with your whole body in view.", fontSize = 12.sp, color = Muted, lineHeight = 16.sp)
            }
            HorizontalDivider(color = Line)
        }
        val entries = a.entries
        if (hud.tracking && entries.isEmpty()) item {
            EmptyNote("Relaxed", "No muscle group is working noticeably in this position. Move, lift a limb or hold an exercise.")
        } else if (!hud.tracking) item {
            EmptyNote("Muscles appear here as you move", "Each muscle shows its role, whether it is shortening, lengthening or holding, and what it does.")
        }
        entries.forEach { e -> item { MuscleRow(e); HorizontalDivider(color = Line, modifier = Modifier.padding(start = 16.dp)) } }
        item {
            Text("Estimated from your posture using body-segment physics (de Leva, 1996) and joint motion. External weights aren't visible to the camera, so effort is relative to your body weight.",
                Modifier.padding(16.dp), fontSize = 11.sp, color = Faint, lineHeight = 15.sp)
        }
    }
}

/** Simple scrolling column (keeps dependencies minimal). */
@Composable
private fun LazyColumnCompat(content: ColumnItems.() -> Unit) {
    val items = ColumnItems().apply(content)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { items.list.forEach { it() } }
}
private class ColumnItems { val list = mutableListOf<@Composable () -> Unit>(); fun item(c: @Composable () -> Unit) { list += c } }

@Composable
private fun EmptyNote(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(4.dp))
        Text(body, fontSize = 12.sp, color = Muted, lineHeight = 17.sp)
    }
}

@Composable
private fun Tag(text: String, fg: Color, bg: Color) {
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp),
        fontSize = 11.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
}

@Composable
private fun MuscleRow(e: MuscleEntry) {
    val heat = Color(Heat.argb(e.level))
    val level by animateFloatAsState(e.level.coerceIn(0f, 1f), label = "level")
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Box(Modifier.padding(top = 3.dp).width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(heat))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.muscle.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Text(e.sideLabel, fontSize = 12.sp, color = Muted, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val (fg, bg) = when (e.role) {
                    MuscleRole.PRIME -> Color(0xFFB42318) to Color(0xFFFEF3F2)
                    MuscleRole.SYNERGIST -> Color(0xFFB54708) to Color(0xFFFFFAEB)
                    MuscleRole.STABILIZER -> Color(0xFF175CD3) to Color(0xFFEFF8FF)
                }
                Tag(e.role.label, fg, bg)
                Tag(e.contraction.label, Body, Stage)
            }
            Spacer(Modifier.height(6.dp))
            Text(e.summary.replaceFirstChar { it.uppercase() }, fontSize = 13.sp, color = Body, lineHeight = 17.sp)
            Spacer(Modifier.height(2.dp))
            Text("${e.muscle.location}. ${e.muscle.function}", fontSize = 12.sp, color = Muted, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(58.dp)) {
            Text("${(e.level * 100).roundToInt()}%", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(e.intensity.label, fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Stage)) {
                Box(Modifier.fillMaxWidth(level).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(heat))
            }
        }
    }
}

@Composable
private fun JointsTab(hud: Hud) {
    val a = hud.analysis.angles
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!hud.tracking || a == null) {
            EmptyNote("No live pose", "Joint angles appear once your body is tracked.")
            return@Column
        }
        fun f(v: Float?) = v?.let { "${it.roundToInt()}°" } ?: "—"
        JointRow("Trunk lean", "from vertical", f(a.trunkLean), null)
        JointRow("Trunk side bend", "+ right / − left", f(a.trunkSideBend), null)
        JointRow("Shoulder elevation", "arm from trunk", f(a.shoulderElevation[Side.LEFT]), f(a.shoulderElevation[Side.RIGHT]))
        JointRow("Elbow flexion", "0° = straight", f(a.elbowFlexion[Side.LEFT]), f(a.elbowFlexion[Side.RIGHT]))
        JointRow("Hip flexion", "thigh from trunk", f(a.hipFlexion[Side.LEFT]), f(a.hipFlexion[Side.RIGHT]))
        JointRow("Knee flexion", "0° = straight", f(a.kneeFlexion[Side.LEFT]), f(a.kneeFlexion[Side.RIGHT]))
        JointRow("Ankle", "+ toes up / − pointed", f(a.ankle[Side.LEFT]), f(a.ankle[Side.RIGHT]))
        Text("Angles come from the 3D replica (fixed proportions). Limbs out of view keep their last estimate.", fontSize = 11.sp, color = Faint, lineHeight = 15.sp)
    }
}

@Composable
private fun JointRow(label: String, hint: String, left: String, right: String?) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(hint, fontSize = 11.sp, color = Muted)
        }
        if (right == null) Text(left, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        else {
            Value("L", left); Spacer(Modifier.width(14.dp)); Value("R", right)
        }
    }
}

@Composable
private fun Value(side: String, v: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(side, fontSize = 11.sp, color = Faint, modifier = Modifier.padding(bottom = 2.dp))
        Spacer(Modifier.width(4.dp))
        Text(v, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

@Composable
private fun SettingsTab(front: Boolean, onFront: (Boolean) -> Unit, model: PoseModel, onModel: (PoseModel) -> Unit,
                        gpu: Boolean, onGpu: (Boolean) -> Unit, skeleton: Boolean, onSkeleton: (Boolean) -> Unit,
                        highlights: Boolean, onHighlights: (Boolean) -> Unit, callouts: Boolean, onCallouts: (Boolean) -> Unit,
                        demo: Boolean, onDemo: (Boolean) -> Unit, recording: Boolean, onCapture: () -> Unit, diagnostics: String) {
    val context = LocalContext.current
    var showDiagnostics by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Section("Camera") {
            Segmented(listOf("Front", "Rear"), if (front) 0 else 1) { onFront(it == 0) }
        }
        Section("Pose model") {
            PoseModel.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onModel(m) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = m == model, onClick = { onModel(m) }, colors = RadioButtonDefaults.colors(selectedColor = Accent))
                    Column {
                        Text(m.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink)
                        Text(m.description, fontSize = 12.sp, color = Muted)
                    }
                }
            }
            Toggle("GPU acceleration", "Falls back to CPU automatically", gpu, onGpu)
        }
        Section("Display") {
            Toggle("Skeleton on camera", null, skeleton, onSkeleton)
            Toggle("Muscle highlights on 3D model", null, highlights, onHighlights)
            Toggle("Muscle labels on 3D model", null, callouts, onCallouts)
            Toggle("Demo motion", "Simulated squats, raises, curls and push-ups", demo, onDemo)
        }
        Section("Advanced") {
            OutlinedButton(onClick = onCapture, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (recording) "Stop & export OpenSim markers" else "Record OpenSim markers (60 s max)", color = Ink, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { showDiagnostics = !showDiagnostics }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (showDiagnostics) "Hide diagnostics" else "Performance diagnostics", color = Ink, fontSize = 13.sp)
            }
            if (showDiagnostics) {
                Spacer(Modifier.height(8.dp))
                Text(diagnostics, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Stage).padding(12.dp),
                    style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, color = Body))
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Creatofit diagnostics", diagnostics))
                }) { Text("Copy report", color = Accent, fontSize = 13.sp) }
            }
        }
        Text("Creatofit Motion ${BuildConfig.VERSION_NAME} · processed on-device · no frames stored", fontSize = 11.sp, color = Faint)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title.uppercase(Locale.US), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp, color = Muted)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Stage).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (i == selected) CardBg else Color.Transparent)
                .clickable { onSelect(i) }.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                Text(o, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium, color = if (i == selected) Ink else Muted)
            }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = Ink)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White,
            uncheckedTrackColor = Stage, uncheckedBorderColor = Line, uncheckedThumbColor = Faint))
    }
}

private fun report(c: TrackingController, m: MetricsSnapshot, thermal: Int): String {
    fun f(v: Float) = String.format(Locale.US, "%.1f", v)
    return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
        "${c.modelSummary} · ${c.delegate}\nInput: ${c.cameraResolution}\nGravity: ${c.gravityStatus}\n" +
        "Camera: ${f(m.cameraFps)} fps · Pose: ${f(m.poseFps)} fps · Render: ${f(m.renderFps)} fps\n" +
        "Inference median: ${f(m.inferenceMedian)} ms\nCapture → render median / p95: ${f(m.poseAgeMedian)} / ${f(m.poseAgeP95)} ms\n" +
        "Displayed pose age: ${m.displayedPoseAgeMs} ms\nAnalyzer frames: ${m.captured} · busy drops: ${m.analysisDropped}\n" +
        "Clock: ${c.timestampPolicy}\nThermal status: $thermal (0 = nominal)"
}

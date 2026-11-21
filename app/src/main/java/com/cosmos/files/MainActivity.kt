@file:OptIn(ExperimentalFoundationApi::class)

package com.cosmos.files

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.core.graphics.drawable.toBitmap
import java.net.InetSocketAddress
import java.net.Socket
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.security.MessageDigest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

val Ink = Color(0xFF0B0B0D)
val Violet = Color(0xFF7878FF)
val Lime = Color(0xFF3DEB48)
val Grey = Color(0xFF8A8A92)

// ---------- Activity (immersive + permission) ----------
class MainActivity : ComponentActivity() {
    private val granted = mutableStateOf(false)
    private val legacy = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private fun hasAccess() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun refresh() { granted.value = hasAccess() }

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30)
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        else legacy.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { CosmosApp(granted.value) { askAccess() } }
    }

    override fun onResume() { super.onResume(); refresh() }
}

// ---------- Data ----------
data class Stats(val files: Int = 0, val folders: Int = 0, val bytes: Long = 0, val cats: Map<String, Int> = emptyMap())

val CATS = listOf("Images", "Videos", "Audio", "Docs", "Archives", "Other")

fun category(ext: String) = when (ext.lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> "Images"
    "mp4", "mkv", "mov", "avi", "webm", "3gp" -> "Videos"
    "mp3", "wav", "flac", "m4a", "ogg", "aac" -> "Audio"
    "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "md", "csv" -> "Docs"
    "zip", "rar", "7z", "tar", "gz", "apk" -> "Archives"
    else -> "Other"
}

fun scan(root: File): Stats {
    var f = 0; var d = 0; var b = 0L
    val c = mutableMapOf<String, Int>()
    root.walkTopDown().onFail { _, _ -> }.forEach {
        if (it.isDirectory) d++ else { f++; b += it.length(); c.merge(category(it.extension), 1, Int::plus) }
    }
    return Stats(f, d, b, c)
}

fun fmt(b: Long): String {
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$b B" else "%.1f %s".format(v, u[i])
}

fun openFile(ctx: Context, f: File) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) { }
}

// ---------- Haptic + bounce press ----------
fun Modifier.tap(haptic: HapticFeedback, strong: Boolean = false, onClick: () -> Unit) = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press"
    )
    this.scale(s).clickable(interactionSource = src, indication = null, onClick = {
        haptic.performHapticFeedback(if (strong) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
        onClick()
    })
}

@Composable
fun Reveal(delayMs: Int, content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); show = true }
    AnimatedVisibility(show, enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 3 }) { content() }
}

// ---------- Root ----------
@Composable
fun CosmosApp(granted: Boolean, onAsk: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver = remember { currentVersion(ctx) }
    var upd by remember { mutableStateOf<Upd>(Upd.Idle) }
    val runCheck: () -> Unit = { scope.launch { upd = Upd.Checking; upd = checkUpdate(ctx) } }
    LaunchedEffect(Unit) { runCheck() }
    val onUpdate: () -> Unit = {
        when (val u = upd) {
            is Upd.Available -> scope.launch {
                upd = Upd.Downloading(0)
                try {
                    val f = downloadApk(ctx, u.url) { upd = Upd.Downloading(it) }
                    upd = Upd.Ready(f); installApk(ctx, f)
                } catch (e: Exception) { upd = Upd.Failed }
            }
            is Upd.Ready -> installApk(ctx, u.file)
            is Upd.Downloading, Upd.Checking -> {}
            else -> runCheck()
        }
    }
    var screen by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var unlocked by remember { mutableStateOf(false) }
    var sheetFile by remember { mutableStateOf<File?>(null) }
    var inVault by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember { mutableStateOf(root) }
    val stats by produceState(Stats(), granted, refresh) {
        value = if (granted) withContext(Dispatchers.IO) { scan(root) } else Stats()
    }
    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    val archive: (File) -> Unit = { f ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Vault.add(ctx, f) }
            toast(if (ok) "Moved to Archive" else "Couldn't archive"); refresh++
        }
    }
    val deleteIt: (File) -> Unit = { f ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { f.deleteRecursively().also { Vault.scan(ctx, f.path) } }
            toast(if (ok) "Deleted" else "Couldn't delete"); refresh++
        }
    }
    val restore: (File) -> Unit = { f ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Vault.restore(ctx, f) }
            toast(if (ok) "Restored" else "Couldn't restore"); refresh++
        }
    }
    val deleteForever: (File) -> Unit = { f ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Vault.delete(ctx, f) }
            toast(if (ok) "Deleted" else "Couldn't delete"); refresh++
        }
    }

    BackHandler(screen == 1) { if (dir != root) dir = dir.parentFile ?: root else screen = 0 }
    BackHandler(screen == 2) { screen = if (granted) 1 else 0 }
    BackHandler(screen == 3) { screen = 0 }
    BackHandler(sheetFile != null) { sheetFile = null }
    BackHandler(confirm != null) { confirm = null }

    // Archive screen: block screenshots / recents preview, and re-lock when leaving or backgrounded
    DisposableEffect(screen) {
        val w = (ctx as Activity).window
        if (screen == 2) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else { w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); unlocked = false }
        onDispose { }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) unlocked = false
            else if (e == Lifecycle.Event.ON_START) Booster.endSession(ctx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val up = targetState > initialState
                (if (up) (slideInVertically(tween(650)) { it } + fadeIn(tween(500)))
                    togetherWith (slideOutVertically(tween(650)) { -it / 4 } + fadeOut(tween(400)))
                else (slideInVertically(tween(650)) { -it / 4 } + fadeIn(tween(500)))
                    togetherWith (slideOutVertically(tween(650)) { it } + fadeOut(tween(400))))
            }, label = "nav"
        ) { s ->
            when (s) {
                0 -> Home(stats, granted, haptic, upd, ver, onUpdate, { screen = 2 }, { screen = 3 }) { if (granted) screen = 1 else onAsk() }
                1 -> Explorer(stats, root, dir, haptic, refresh, { dir = it },
                    { dir = dir.parentFile?.takeIf { p -> dir != root } ?: root }, { screen = 0 },
                    { sheetFile = it; inVault = false }, { screen = 2 })
                3 -> BoostScreen(haptic) { screen = 0 }
                else -> VaultScreen(unlocked, { unlocked = true }, refresh, haptic,
                    { screen = if (granted) 1 else 0 }, { sheetFile = it; inVault = true })
            }
        }

        sheetFile?.let { f ->
            val name = if (inVault) Vault.shownName(f) else f.name
            val acts = if (!inVault) listOf(
                Act(if (f.isDirectory) "Open folder" else "Open") { if (f.isDirectory) dir = f else openFile(ctx, f) },
                Act("Move to Archive") { archive(f) },
                Act("Delete", true) {
                    confirm = ("Delete \"${f.name}\"" + (if (f.isDirectory) " and everything inside it" else "") + "? This can't be undone.") to { deleteIt(f) }
                }
            ) else listOf(
                Act("Open") { openFile(ctx, f) },
                Act("Restore to original place") { restore(f) },
                Act("Delete forever", true) {
                    confirm = ("Delete \"$name\" forever? This can't be undone.") to { deleteForever(f) }
                }
            )
            ActionSheet(name, acts, haptic) { sheetFile = null }
        }
        confirm?.let { c -> ConfirmDialog(c.first, haptic, { confirm = null }) { val run = c.second; confirm = null; run() } }
    }
}

// ---------- Screen 1: Home ----------
@Composable
fun Home(stats: Stats, granted: Boolean, haptic: HapticFeedback, upd: Upd, ver: String, onUpdate: () -> Unit, onVault: () -> Unit, onBoost: () -> Unit, onOpen: () -> Unit) {
    val files by animateIntAsState(stats.files, tween(1400), label = "f")
    val folders by animateIntAsState(stats.folders, tween(1400), label = "d")
    val bob by rememberInfiniteTransition(label = "chev").animateFloat(0f, -6f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "bob")

    Column(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxWidth().weight(0.40f).padding(12.dp)) {
            CosmicHero()
            UpdatePill(upd, ver, haptic, onUpdate, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 6.dp))
        }
        Column(Modifier.padding(horizontal = 22.dp)) {
            Reveal(200) {
                Column {
                    Text("Cosmos", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, lineHeight = 52.sp)
                    Text(buildAnnotatedString {
                        append("of ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light)) { append("digital") }
                    }, color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, lineHeight = 52.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Files", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, lineHeight = 52.sp)
                        Spacer(Modifier.width(16.dp))
                        Row(
                            Modifier.tap(haptic, true, onOpen).border(1.dp, Color.White.copy(0.7f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                            Spacer(Modifier.width(8.dp))
                            Text(if (granted) "OPEN NOW" else "GRANT ACCESS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Reveal(500) {
                Column {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A3A40)))
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Total files", color = Grey, fontSize = 13.sp)
                            Text("$files", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.width(1.dp).height(56.dp).background(Color(0xFF3A3A40)))
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text("Total folders", color = Grey, fontSize = 13.sp)
                            Text("$folders", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A3A40)))
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp)) {
            val prog = remember { Animatable(0f) }
            LaunchedEffect(Unit) { delay(900); prog.animateTo(1f, tween(1600)) }
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val path = Path().apply {
                    moveTo(w * .52f, h * .62f)
                    cubicTo(w * .78f, h * .05f, w * .22f, h * .0f, w * .40f, h * .56f)
                    lineTo(w * .36f, h * .46f); moveTo(w * .40f, h * .56f); lineTo(w * .47f, h * .47f)
                }
                val pm = android.graphics.PathMeasure(path.asAndroidPath(), false)
                val seg = android.graphics.Path()
                pm.getSegment(0f, pm.length * prog.value, seg, true)
                drawPath(seg.asComposePath(), Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
            Box(Modifier.align(Alignment.BottomStart).padding(bottom = 22.dp)) { Stamp("GAME · BOOST · COSMOS · ", Lime, Ink, 112.dp, haptic, false, onBoost) }
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 22.dp)) { Stamp("COSMOS · STORAGE · VAULT · ", Violet, Ink, 112.dp, haptic, true, onVault) }
            Box(
                Modifier.align(Alignment.BottomCenter).offset(y = bob.dp).width(58.dp).height(24.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Color.White).tap(haptic, true, onOpen),
                contentAlignment = Alignment.Center
            ) { Text("⌃", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------- Rotating text stamp with globe ----------
@Composable
fun Stamp(text: String, fill: Color, ink: Color, size: androidx.compose.ui.unit.Dp, haptic: HapticFeedback, reverse: Boolean = false, onClick: () -> Unit = {}) {
    var boost by remember { mutableFloatStateOf(0f) }
    val spin by animateFloatAsState(boost, tween(900, easing = FastOutSlowInEasing), label = "boost")
    val rot by rememberInfiniteTransition(label = "stamp").animateFloat(0f, if (reverse) -360f else 360f,
        infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "rot")
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true; typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    Canvas(Modifier.size(size).tap(haptic, true) { boost += 360f; onClick() }) {
        val r = this.size.width / 2
        drawCircle(fill)
        paint.color = ink.toArgb(); paint.textSize = r * 0.2f
        val ring = android.graphics.Path().apply { addCircle(r, r, r * 0.72f, android.graphics.Path.Direction.CW) }
        val circ = (2 * PI * r * 0.72f).toFloat()
        var s = text; while (paint.measureText(s) < circ * 0.97f) s += text
        while (paint.measureText(s) > circ * 0.99f) s = s.dropLast(1)
        rotate(rot + spin) { drawContext.canvas.nativeCanvas.drawTextOnPath(s, ring, 0f, 0f, paint) }
        val g = r * 0.36f; val sw = 2.dp.toPx(); val c = center
        drawCircle(ink, g, style = Stroke(sw))
        drawOval(ink, Offset(c.x - g * .45f, c.y - g), Size(g * .9f, g * 2), style = Stroke(sw))
        drawLine(ink, Offset(c.x - g, c.y), Offset(c.x + g, c.y), sw)
    }
}

// ---------- Cosmic hero (animated tentacle-planet) ----------
@Composable
fun CosmicHero() {
    val ph by rememberInfiniteTransition(label = "hero").animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "ph")
    Canvas(Modifier.fillMaxSize()) {
        val c = center; val R = min(size.width, size.height) / 2 * 0.98f
        for (i in 0 until 8) {
            val base = (i * PI / 4).toFloat(); val dir = if (i % 2 == 0) 1f else -1f
            var prev = c
            for (j in 0..36) {
                val u = j / 36f
                val rad = R * (0.25f + 0.75f * u)
                val a = base + dir * (1.7f * u + 0.5f * sin(ph + i) * u * u)
                val p = Offset(c.x + rad * cos(a), c.y + rad * sin(a))
                if (j > 0) drawLine(if (i % 2 == 0) Color.White else Color(0xFFE8E8EE), prev, p,
                    strokeWidth = (22f * (1f - u) + 4f).dp.toPx(), cap = StrokeCap.Round)
                prev = p
            }
        }
        val hr = R * 0.34f
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFCFCFD6)), c - Offset(hr * .3f, hr * .3f), hr * 1.3f), hr, c)
        drawCircle(Color(0xFFB4501C), hr * .09f, c + Offset(-hr * .3f, hr * .1f))
        drawCircle(Color(0xFFB4501C), hr * .09f, c + Offset(hr * .3f, hr * .1f))
    }
}

// ---------- Screen 2: Explorer ----------
@Composable
fun Explorer(
    stats: Stats, root: File, dir: File, haptic: HapticFeedback, refresh: Int,
    onDir: (File) -> Unit, onUp: () -> Unit, onHome: () -> Unit,
    onLong: (File) -> Unit, onVault: () -> Unit
) {
    val ctx = LocalContext.current
    val entries = remember(dir, refresh) {
        (dir.listFiles()?.toList() ?: emptyList()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }
    val free = remember(dir) { runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L) }
    var liked by remember { mutableStateOf(false) }
    val heart by animateFloatAsState(if (liked) 1.25f else 1f, spring(Spring.DampingRatioHighBouncy), label = "heart")
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy")) }

    Column(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxWidth().weight(0.36f).padding(16.dp)) {
            Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CATS.chunked(3).forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEachIndexed { k, n -> Blob(n, stats.cats[n] ?: 0, r * 3 + k) }
                    }
                }
            }
            Text("←", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.TopStart).tap(haptic, true, onHome).padding(4.dp))
            Box(Modifier.align(Alignment.BottomStart)) { Stamp("COSMOS · FILES · ", Violet, Ink, 84.dp, haptic, false, onVault) }
        }
        Box(Modifier.fillMaxWidth().weight(0.64f)) {
            LazyColumn(
                Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)).background(Color.White),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 30.dp)
            ) {
                item {
                    Text(buildAnnotatedString {
                        append("Opened on "); withStyle(SpanStyle(color = Violet)) { append(date) }
                    }, fontSize = 12.sp, color = Ink)
                    Text("Cosmos.//" + (dir.name.ifEmpty { "Storage" }), fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth().drawBehind {
                        drawLine(Color(0xFFDDDDDD), Offset(0f, 0f), Offset(size.width, 0f), 2f)
                        drawLine(Color(0xFFDDDDDD), Offset(0f, size.height), Offset(size.width, size.height), 2f)
                    }.padding(vertical = 8.dp)) {
                        Stat("Total size", fmt(stats.bytes), Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(44.dp).background(Color(0xFFDDDDDD)))
                        Stat("Free", fmt(free), Modifier.weight(1f).padding(start = 12.dp))
                        Box(Modifier.width(1.dp).height(44.dp).background(Color(0xFFDDDDDD)))
                        Stat("Items", "${entries.size}", Modifier.weight(1f).padding(start = 12.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.weight(1f).height(46.dp).tap(haptic, true, onUp).border(1.5.dp, Ink, CircleShape).padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Go up", fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(dir.path.removePrefix(root.path).ifEmpty { "/" }, color = Grey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                        }
                        Row(
                            Modifier.height(46.dp).tap(haptic, true, onVault).clip(CircleShape).background(Ink).padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Lime))
                            Spacer(Modifier.width(8.dp))
                            Text("Archive", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Hold any file or folder to archive or delete", color = Grey, fontSize = 11.sp)
                    Spacer(Modifier.height(18.dp))
                    Text("Next folders", fontSize = 12.sp, color = Ink)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(entries.filter { it.isDirectory }.take(8)) { f ->
                            Box(Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)).background(Ink).tap(haptic) { onDir(f) }.padding(10.dp)) {
                                Box(Modifier.size(34.dp, 20.dp).clip(CircleShape).background(Color.White).align(Alignment.TopStart))
                                Text(f.name, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                items(entries, key = { it.path }) { f ->
                    Row(Modifier.fillMaxWidth().tapLong(haptic, { onLong(f) }) { if (f.isDirectory) onDir(f) else openFile(ctx, f) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Thumb(f, 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (f.isDirectory) "Folder" else fmt(f.length()), color = Grey, fontSize = 12.sp)
                        }
                    }
                }
            }
            Row(Modifier.align(Alignment.TopEnd).offset(y = (-24).dp).padding(end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.scale(heart).size(66.dp, 46.dp).clip(RoundedCornerShape(23.dp)).background(Lime)
                        .tap(haptic, true) { liked = !liked },
                    contentAlignment = Alignment.Center
                ) { Text(if (liked) "♥" else "♡", fontSize = 22.sp, color = Ink) }
                Spacer(Modifier.width(10.dp))
                Text("↗", color = Color.White, fontSize = 22.sp, modifier = Modifier.tap(haptic) {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "I'm organising my files with Cosmos"), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Grey, fontSize = 11.sp)
        Text(value, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun Blob(name: String, count: Int, seed: Int) {
    val bob by rememberInfiniteTransition(label = "blob").animateFloat(-5f, 5f,
        infiniteRepeatable(tween(1800 + seed * 250, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(84.dp, 48.dp).offset(y = bob.dp)) {
            val w = size.width; val h = size.height
            val tail = Path().apply { moveTo(w * .78f, h / 2); lineTo(w, h * .08f); lineTo(w, h * .92f); close() }
            drawPath(tail, Color(0xFFDADAE0))
            drawOval(Brush.verticalGradient(listOf(Color.White, Color(0xFFC9C9D1))), size = Size(w * .85f, h))
            drawCircle(Color(0xFFD9822B), 3.dp.toPx(), Offset(w * .18f, h * .4f))
        }
        Text("$name $count", color = Grey, fontSize = 10.sp)
    }
}


// ---------- In-app updater (GitHub Releases) ----------
const val REPO = "faliqfaizullah-cloud/cosmos-files"

sealed interface Upd {
    data object Idle : Upd
    data object Checking : Upd
    data object Latest : Upd
    data object Failed : Upd
    data class Available(val ver: String, val url: String) : Upd
    data class Downloading(val pct: Int) : Upd
    data class Ready(val file: File) : Upd
}

fun currentVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"

fun isNewer(a: String, b: String): Boolean {
    val x = a.split(".").map { it.toIntOrNull() ?: 0 }
    val y = b.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
        if (p != q) return p > q
    }
    return false
}

suspend fun checkUpdate(ctx: Context): Upd = withContext(Dispatchers.IO) {
    try {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10000; c.readTimeout = 10000
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val tag = j.getString("tag_name").removePrefix("v")
        val assets = j.getJSONArray("assets")
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
        }
        if (url != null && isNewer(tag, currentVersion(ctx))) Upd.Available(tag, url) else Upd.Latest
    } catch (e: Exception) { Upd.Failed }
}

suspend fun downloadApk(ctx: Context, url: String, onPct: (Int) -> Unit): File = withContext(Dispatchers.IO) {
    val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
    val out = File(dir, "Cosmos.apk")
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000; c.readTimeout = 30000
    val total = c.contentLengthLong
    c.inputStream.use { inp ->
        out.outputStream().use { o ->
            val buf = ByteArray(32768); var read = 0L; var last = -1
            while (true) {
                val n = inp.read(buf); if (n < 0) break
                o.write(buf, 0, n); read += n
                if (total > 0) { val p = (read * 100 / total).toInt(); if (p != last) { last = p; onPct(p) } }
            }
        }
    }
    out
}

fun installApk(ctx: Context, f: File) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun UpdatePill(upd: Upd, ver: String, haptic: HapticFeedback, onClick: () -> Unit, modifier: Modifier) {
    val label = when (upd) {
        Upd.Idle -> "v$ver · CHECK UPDATE"
        Upd.Checking -> "CHECKING…"
        Upd.Latest -> "v$ver · UP TO DATE"
        Upd.Failed -> "OFFLINE · RETRY"
        is Upd.Available -> "UPDATE TO v${upd.ver}"
        is Upd.Downloading -> "DOWNLOADING ${upd.pct}%"
        is Upd.Ready -> "TAP TO INSTALL"
    }
    val hot = upd is Upd.Available || upd is Upd.Ready
    val bg by animateColorAsState(if (hot) Lime else Color.Transparent, tween(400), label = "pillbg")
    Row(
        modifier.tap(haptic, true, onClick).clip(CircleShape).background(bg)
            .border(1.dp, if (hot) Lime else Color.White.copy(0.5f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (hot) Ink else Lime))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (hot) Ink else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}


// ---------- Long-press + haptic ----------
fun Modifier.tapLong(haptic: HapticFeedback, onLong: () -> Unit, onClick: () -> Unit) = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "pressL"
    )
    this.scale(sc).combinedClickable(
        interactionSource = src, indication = null,
        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLong() },
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
    )
}

// ---------- Private archive (app-private storage + PIN) ----------
object Vault {
    fun dir(ctx: Context) = File(ctx.filesDir, "archive").apply { mkdirs() }
    private fun p(ctx: Context) = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private fun idx(ctx: Context) = JSONObject(p(ctx).getString("idx", "{}") ?: "{}")
    private fun hash(pin: String) =
        MessageDigest.getInstance("SHA-256").digest("cosmos:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasPin(ctx: Context) = p(ctx).contains("pin")
    fun setPin(ctx: Context, pin: String) { p(ctx).edit().putString("pin", hash(pin)).apply() }
    fun checkPin(ctx: Context, pin: String) = p(ctx).getString("pin", null) == hash(pin)
    fun shownName(f: File) = f.name.substringAfter("_", f.name)
    fun list(ctx: Context) = (dir(ctx).listFiles()?.toList() ?: emptyList()).sortedByDescending { it.lastModified() }
    fun original(ctx: Context, f: File) = idx(ctx).optString(f.name, "")
    fun scan(ctx: Context, path: String) { runCatching { MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null) } }

    private fun copy(src: File, dst: File): Boolean =
        if (src.isDirectory) src.copyRecursively(dst, true) else { src.copyTo(dst, true); true }

    private fun record(ctx: Context, name: String, orig: String?) {
        val j = idx(ctx)
        if (orig == null) j.remove(name) else j.put(name, orig)
        p(ctx).edit().putString("idx", j.toString()).apply()
    }

    fun add(ctx: Context, f: File): Boolean = try {
        val dst = File(dir(ctx), "${System.currentTimeMillis()}_${f.name}")
        if (!copy(f, dst)) false
        else if (!f.deleteRecursively()) { dst.deleteRecursively(); false }
        else { record(ctx, dst.name, f.path); scan(ctx, f.path); true }
    } catch (e: Exception) { false }

    fun restore(ctx: Context, f: File): Boolean = try {
        val o = original(ctx, f)
        val orig = File(if (o.isEmpty()) File(Environment.getExternalStorageDirectory(), "Restored/" + shownName(f)).path else o)
        orig.parentFile?.mkdirs()
        val dst = if (orig.exists())
            File(orig.parentFile, orig.nameWithoutExtension + "_restored" + (if (orig.extension.isEmpty()) "" else "." + orig.extension))
        else orig
        if (!copy(f, dst)) false else { f.deleteRecursively(); record(ctx, f.name, null); scan(ctx, dst.path); true }
    } catch (e: Exception) { false }

    fun delete(ctx: Context, f: File): Boolean = try {
        val ok = f.deleteRecursively(); if (ok) record(ctx, f.name, null); ok
    } catch (e: Exception) { false }
}

// ---------- Thumbnail (photo / video preview, else extension tile) ----------
@Composable
fun Thumb(f: File, size: androidx.compose.ui.unit.Dp) {
    val cat = if (f.isDirectory) "Folder" else category(f.extension)
    val bmp by produceState<ImageBitmap?>(null, f.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when (cat) {
                    "Images" -> {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(f.path, o)
                        var sm = 1; while (o.outWidth / sm > 256) sm *= 2
                        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sm })?.asImageBitmap()
                    }
                    "Videos" -> if (Build.VERSION.SDK_INT >= 29)
                        ThumbnailUtils.createVideoThumbnail(f, android.util.Size(256, 256), null).asImageBitmap() else null
                    else -> null
                }
            }.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(Ink), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (cat == "Videos") Text("▶", color = Color.White, fontSize = 14.sp)
        } else Text(if (f.isDirectory) "▸" else f.extension.take(3).uppercase().ifEmpty { "•" },
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

// ---------- PIN screen ----------
@Composable
fun PinScreen(haptic: HapticFeedback, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val has = remember { Vault.hasPin(ctx) }
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf(if (has) "Enter your PIN" else "Create a 4-digit PIN") }
    val press: (String) -> Unit = { d ->
        if (pin.length < 4) {
            pin += d
            if (pin.length == 4) {
                if (has) {
                    if (Vault.checkPin(ctx, pin)) onDone()
                    else { msg = "Wrong PIN, try again"; pin = ""; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                } else if (first == null) { first = pin; pin = ""; msg = "Confirm your PIN" }
                else if (first == pin) { Vault.setPin(ctx, pin); onDone() }
                else { msg = "PINs didn't match. Start again"; first = null; pin = "" }
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Stamp("COSMOS · ARCHIVE · PRIVATE · ", Lime, Ink, 76.dp, haptic)
        Spacer(Modifier.height(16.dp))
        Text("Cosmos.//Archive", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(msg, color = Grey, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (i in 0 until 4) {
                val on = i < pin.length
                val sc by animateFloatAsState(if (on) 1.25f else 1f, spring(Spring.DampingRatioHighBouncy), label = "dot")
                Box(Modifier.scale(sc).size(16.dp).clip(CircleShape)
                    .background(if (on) Lime else Color.Transparent).border(1.5.dp, if (on) Lime else Color.White.copy(0.4f), CircleShape))
            }
        }
        Spacer(Modifier.height(28.dp))
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("✕", "0", "⌫")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { k ->
                    Box(Modifier.size(70.dp).tap(haptic) {
                        when (k) { "✕" -> onBack(); "⌫" -> pin = pin.dropLast(1); else -> press(k) }
                    }.clip(CircleShape).background(Color(0xFF1B1B20)), contentAlignment = Alignment.Center) {
                        Text(k, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ---------- Screen 3: Archive ----------
@Composable
fun VaultScreen(unlocked: Boolean, onUnlock: () -> Unit, refresh: Int, haptic: HapticFeedback, onBack: () -> Unit, onLong: (File) -> Unit) {
    if (!unlocked) { PinScreen(haptic, onBack, onUnlock) } else {
        val ctx = LocalContext.current
        val stash = remember(refresh) { Vault.list(ctx) }
        val total = remember(refresh) {
            stash.sumOf { if (it.isDirectory) it.walkTopDown().filter { x -> x.isFile }.sumOf { x -> x.length() } else it.length() }
        }
        Column(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(Modifier.fillMaxWidth().weight(0.26f).padding(22.dp)) {
                Text("←", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.TopStart).tap(haptic, true, onBack).padding(4.dp))
                Box(Modifier.align(Alignment.TopEnd)) { Stamp("PRIVATE · LOCKED · ", Lime, Ink, 72.dp, haptic) }
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text("Cosmos", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, lineHeight = 40.sp)
                    Text(buildAnnotatedString {
                        append("//"); withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light)) { append("archive") }
                    }, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, lineHeight = 40.sp)
                }
                Text("${stash.size} items · ${fmt(total)}", color = Grey, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd))
            }
            LazyColumn(
                Modifier.weight(0.74f).fillMaxWidth().clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)).background(Color.White),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 30.dp)
            ) {
                if (stash.isEmpty()) item {
                    Text("Nothing here yet.\nHold any photo, video or file in Explorer and choose Move to Archive.\n\nArchived files are hidden from the Gallery and other apps. They live inside Cosmos, so uninstalling the app erases them.",
                        color = Grey, fontSize = 14.sp, lineHeight = 20.sp)
                }
                items(stash, key = { it.path }) { f ->
                    val from = Vault.original(ctx, f).let { o -> if (o.isEmpty()) "" else File(o).parentFile?.name ?: "" }
                    Row(Modifier.fillMaxWidth().tapLong(haptic, { onLong(f) }) { openFile(ctx, f) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Thumb(f, 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Vault.shownName(f), color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (f.isDirectory) "Folder" else fmt(f.length())) + (if (from.isEmpty()) "" else " · from $from"), color = Grey, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Action sheet + confirm ----------
data class Act(val label: String, val danger: Boolean = false, val run: () -> Unit)

@Composable
fun ActionSheet(title: String, acts: List<Act>, haptic: HapticFeedback, onDismiss: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { show = true }
    val none = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.55f))
        .clickable(interactionSource = none, indication = null) { onDismiss() }) {
        AnimatedVisibility(
            show, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(Spring.DampingRatioLowBouncy)) { it } + fadeIn()
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)).background(Color.White)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(22.dp)
            ) {
                Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                acts.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp).height(52.dp)
                            .tap(haptic, a.danger) { onDismiss(); a.run() }
                            .clip(CircleShape).background(if (a.danger) Color(0xFFFF4D4D) else Ink),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                    ) { Text(a.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).height(52.dp).tap(haptic) { onDismiss() }
                        .border(1.5.dp, Ink, CircleShape),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) { Text("Cancel", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
fun ConfirmDialog(text: String, haptic: HapticFeedback, onCancel: () -> Unit, onOk: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.65f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(28.dp).clip(RoundedCornerShape(32.dp)).background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.padding(24.dp)
        ) {
            Text("Are you sure?", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(text, color = Grey, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).height(48.dp).tap(haptic) { onCancel() }.border(1.5.dp, Ink, CircleShape),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("Cancel", color = Ink, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.weight(1f).height(48.dp).tap(haptic, true) { onOk() }.clip(CircleShape).background(Color(0xFFFF4D4D)),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


// ---------- Game booster engine ----------
data class AppItem(val pkg: String, val label: String, val auto: Boolean)
data class Snap(val avail: Long = 0, val total: Long = 1, val temp: Float = 0f, val level: Int = 0, val thermal: String = "-")

object Booster {
    fun prefs(ctx: Context) = ctx.getSharedPreferences("boost", Context.MODE_PRIVATE)
    private fun set(ctx: Context, key: String) = HashSet(prefs(ctx).getStringSet(key, emptySet()) ?: emptySet())

    @Suppress("DEPRECATION")
    private fun isGame(ai: ApplicationInfo) =
        (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    fun launchables(ctx: Context): List<AppItem> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString(), isGame(it.activityInfo.applicationInfo)) }
            .filter { it.pkg != ctx.packageName }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun games(ctx: Context, all: List<AppItem>): List<AppItem> {
        val pinned = set(ctx, "pinned"); val hidden = set(ctx, "hidden")
        return all.filter { (it.auto || it.pkg in pinned) && it.pkg !in hidden }
    }

    fun pin(ctx: Context, pkg: String) {
        val pn = set(ctx, "pinned").apply { add(pkg) }; val hd = set(ctx, "hidden").apply { remove(pkg) }
        prefs(ctx).edit().putStringSet("pinned", pn).putStringSet("hidden", hd).apply()
    }

    fun hide(ctx: Context, pkg: String) {
        val pn = set(ctx, "pinned").apply { remove(pkg) }; val hd = set(ctx, "hidden").apply { add(pkg) }
        prefs(ctx).edit().putStringSet("pinned", pn).putStringSet("hidden", hd).apply()
    }

    fun snapshot(ctx: Context): Snap {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val th = if (Build.VERSION.SDK_INT >= 29) when (ctx.getSystemService(PowerManager::class.java).currentThermalStatus) {
            0 -> "Cool"; 1 -> "Light"; 2 -> "Warm"; 3 -> "Hot"; else -> "Critical"
        } else "-"
        return Snap(mi.availMem, mi.totalMem.coerceAtLeast(1), temp, lvl, th)
    }

    suspend fun ping(): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val t = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 2000) }
            ((System.nanoTime() - t) / 1_000_000).toInt()
        }.getOrNull()
    }

    /** Ends cached background processes of other apps (the strongest thing a non-root app may do). */
    fun clean(ctx: Context, except: String?) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        launchables(ctx).filter { it.pkg != except }.forEach { runCatching { am.killBackgroundProcesses(it.pkg) } }
    }

    fun hasDnd(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun startSession(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        prefs(ctx).edit().putInt("prev", nm.currentInterruptionFilter).putBoolean("session", true).apply()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    fun endSession(ctx: Context) {
        val pr = prefs(ctx)
        if (!pr.getBoolean("session", false)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            val prev = pr.getInt("prev", NotificationManager.INTERRUPTION_FILTER_ALL).takeIf { it in 1..4 } ?: NotificationManager.INTERRUPTION_FILTER_ALL
            nm.setInterruptionFilter(prev)
        }
        pr.edit().putBoolean("session", false).apply()
    }

    fun launch(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(160, 160).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Color(0xFFEDEDF0)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize()) }
    }
}

@Composable
fun TogglePill(label: String, on: Boolean, haptic: HapticFeedback, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Lime else Color.Transparent, tween(300), label = "tg")
    Row(
        Modifier.tap(haptic, true, onClick).clip(CircleShape).background(bg)
            .border(1.dp, if (on) Lime else Color.White.copy(0.5f), CircleShape).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Ink else Color.White.copy(0.6f)))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (on) Ink else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Screen 4: Game boost ----------
@Composable
fun BoostScreen(haptic: HapticFeedback, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf(Snap()) }
    var ping by remember { mutableStateOf<Int?>(null) }
    var all by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var games by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var clearRam by remember { mutableStateOf(Booster.prefs(ctx).getBoolean("clear", true)) }
    var dnd by remember { mutableStateOf(Booster.prefs(ctx).getBoolean("dnd", true)) }
    var dndOk by remember { mutableStateOf(Booster.hasDnd(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var removeT by remember { mutableStateOf<AppItem?>(null) }

    BackHandler(picker || removeT != null) { picker = false; removeT = null }

    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { val a = Booster.launchables(ctx); all = a; games = Booster.games(ctx, a) }
    }
    LaunchedEffect(Unit) {
        var i = 0
        while (true) {
            snap = Booster.snapshot(ctx); dndOk = Booster.hasDnd(ctx)
            if (i % 5 == 0) ping = Booster.ping()
            i++; delay(2000)
        }
    }

    fun boost(target: AppItem?) {
        if (busy) return
        scope.launch {
            busy = true
            val before = Booster.snapshot(ctx).avail
            if (clearRam) withContext(Dispatchers.IO) { Booster.clean(ctx, target?.pkg) }
            if (target != null && dnd && dndOk) Booster.startSession(ctx)
            delay(1300)
            val after = Booster.snapshot(ctx); snap = after
            ping = Booster.ping()
            val freed = (after.avail - before).coerceAtLeast(0)
            report = (if (clearRam) (if (freed > 0) "Freed ${fmt(freed)}" else "Memory already optimal") else "RAM clean skipped") +
                " · " + (ping?.let { "ping $it ms" } ?: "offline")
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            busy = false
            if (target != null) Booster.launch(ctx, target.pkg)
        }
    }

    val used = (1f - snap.avail.toFloat() / snap.total.toFloat()).coerceIn(0f, 1f)
    val usedAnim by animateFloatAsState(used, tween(800), label = "used")
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "sp")

    Box(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(160.dp).padding(22.dp)) {
                Text("←", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.TopStart).tap(haptic, true, onBack).padding(4.dp))
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text("Cosmos", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, lineHeight = 38.sp)
                    Text(buildAnnotatedString {
                        append("//"); withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Light)) { append("boost") }
                    }, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, lineHeight = 38.sp)
                }
                Box(Modifier.align(Alignment.CenterEnd).size(108.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val sw = 10.dp.toPx(); val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
                        drawArc(Color(0xFF2A2A30), -90f, 360f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                        drawArc(if (usedAnim > 0.85f) Color(0xFFFF4D4D) else Lime, -90f, 360f * usedAnim, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                        if (busy) drawArc(Color.White, spin, 50f, false, tl, sz, style = Stroke(sw / 2, cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${(usedAnim * 100).toInt()}%", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("RAM used", color = Grey, fontSize = 10.sp)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 22.dp)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A3A40)))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Free RAM", color = Grey, fontSize = 12.sp)
                        Text(fmt(snap.avail), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Temp · ${snap.thermal}", color = Grey, fontSize = 12.sp)
                        Text("%.1f°C".format(snap.temp), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(0.8f).tap(haptic) { scope.launch { ping = Booster.ping() } }) {
                        Text("Ping", color = Grey, fontSize = 12.sp)
                        Text(ping?.let { "$it ms" } ?: "-", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3A3A40)))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TogglePill("Clear RAM", clearRam, haptic) { clearRam = !clearRam; Booster.prefs(ctx).edit().putBoolean("clear", clearRam).apply() }
                    TogglePill(if (dndOk) "Silence alerts" else "Silence alerts · allow", dnd && dndOk, haptic) {
                        if (!dndOk) ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else { dnd = !dnd; Booster.prefs(ctx).edit().putBoolean("dnd", dnd).apply() }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(if (report.isEmpty()) "Tap a game to clean memory and launch it" else report,
                    color = if (report.isEmpty()) Grey else Lime, fontSize = 12.sp, modifier = Modifier.padding(end = 120.dp).height(32.dp))
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyVerticalGrid(
                    GridCells.Fixed(4),
                    Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)).background(Color.White),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 30.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text("Your games", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Tap to boost and play · hold to remove", color = Grey, fontSize = 11.sp)
                        }
                    }
                    gridItems(games, key = { it.pkg }) { g ->
                        Column(Modifier.tapLong(haptic, { removeT = g }) { boost(g) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            AppIcon(g.pkg, 62.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(g.label, color = Ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                    item {
                        Column(Modifier.tap(haptic, true) { picker = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(62.dp).clip(RoundedCornerShape(17.dp)).border(1.5.dp, Ink, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                                Text("+", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Light)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Add", color = Ink, fontSize = 11.sp)
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).offset(y = (-23).dp).padding(end = 18.dp).size(104.dp, 46.dp)
                        .tap(haptic, true) { boost(null) }.clip(RoundedCornerShape(23.dp)).background(Lime),
                    contentAlignment = Alignment.Center
                ) { Text("BOOST", color = Ink, fontWeight = FontWeight.Black, fontSize = 16.sp) }
            }
        }

        if (picker) {
            val none = remember { MutableInteractionSource() }
            val listed = games.map { it.pkg }.toSet()
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f)).clickable(interactionSource = none, indication = null) { picker = false }) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.68f)
                        .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)).background(Color.White)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(top = 22.dp, start = 22.dp, end = 22.dp)
                ) {
                    Text("Add to Boost", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(all.filter { it.pkg !in listed }, key = { it.pkg }) { a ->
                            Row(Modifier.fillMaxWidth().tap(haptic) { Booster.pin(ctx, a.pkg); tick++; picker = false }.padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(a.pkg, 42.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(a.label, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        removeT?.let { t ->
            ActionSheet(t.label, listOf(Act("Remove from list", true) { Booster.hide(ctx, t.pkg); tick++ }), haptic) { removeT = null }
        }
        if (busy) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.78f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Stamp("OPTIMIZING · COSMOS · BOOST · ", Lime, Ink, 150.dp, haptic)
                    Spacer(Modifier.height(16.dp))
                    Text("Optimizing…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

package com.cosmos.files

import android.content.ActivityNotFoundException
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
    var screen by remember { mutableIntStateOf(0) }
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember { mutableStateOf(root) }
    val stats by produceState(Stats(), granted) {
        value = if (granted) withContext(Dispatchers.IO) { scan(root) } else Stats()
    }
    BackHandler(screen == 1) { if (dir != root) dir = dir.parentFile ?: root else screen = 0 }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7474FB), Color(0xFF5B5BE6))))) {
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
            if (s == 0) Home(stats, granted, haptic) { if (granted) screen = 1 else onAsk() }
            else Explorer(stats, root, dir, haptic, { dir = it }, { dir = dir.parentFile?.takeIf { p -> dir != root } ?: root }, { screen = 0 })
        }
    }
}

// ---------- Screen 1: Home ----------
@Composable
fun Home(stats: Stats, granted: Boolean, haptic: HapticFeedback, onOpen: () -> Unit) {
    val files by animateIntAsState(stats.files, tween(1400), label = "f")
    val folders by animateIntAsState(stats.folders, tween(1400), label = "d")
    val bob by rememberInfiniteTransition(label = "chev").animateFloat(0f, -6f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "bob")

    Column(Modifier.fillMaxSize().padding(14.dp).clip(RoundedCornerShape(40.dp)).background(Ink)) {
        Box(Modifier.fillMaxWidth().weight(0.40f).padding(12.dp)) { CosmicHero() }
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
            Box(Modifier.align(Alignment.BottomStart).padding(bottom = 22.dp)) { Stamp("DIGITAL · FILES · COSMOS · ", Lime, Ink, 112.dp, haptic) }
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 22.dp)) { Stamp("COSMOS · STORAGE · VAULT · ", Violet, Ink, 112.dp, haptic, true) }
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
fun Stamp(text: String, fill: Color, ink: Color, size: androidx.compose.ui.unit.Dp, haptic: HapticFeedback, reverse: Boolean = false) {
    var boost by remember { mutableFloatStateOf(0f) }
    val spin by animateFloatAsState(boost, tween(900, easing = FastOutSlowInEasing), label = "boost")
    val rot by rememberInfiniteTransition(label = "stamp").animateFloat(0f, if (reverse) -360f else 360f,
        infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "rot")
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true; typeface = android.graphics.Typeface.DEFAULT_BOLD } }
    Canvas(Modifier.size(size).tap(haptic, true) { boost += 360f }) {
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
    stats: Stats, root: File, dir: File, haptic: HapticFeedback,
    onDir: (File) -> Unit, onUp: () -> Unit, onHome: () -> Unit
) {
    val ctx = LocalContext.current
    val entries = remember(dir) {
        (dir.listFiles()?.toList() ?: emptyList()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }
    val free = remember(dir) { runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L) }
    var liked by remember { mutableStateOf(false) }
    val heart by animateFloatAsState(if (liked) 1.25f else 1f, spring(Spring.DampingRatioHighBouncy), label = "heart")
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy")) }

    Column(Modifier.fillMaxSize().padding(14.dp).clip(RoundedCornerShape(40.dp)).background(Ink)) {
        Box(Modifier.fillMaxWidth().weight(0.36f).padding(16.dp)) {
            Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CATS.chunked(3).forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEachIndexed { k, n -> Blob(n, stats.cats[n] ?: 0, r * 3 + k) }
                    }
                }
            }
            Text("←", color = Color.White, fontSize = 22.sp, modifier = Modifier.align(Alignment.TopStart).tap(haptic, true, onHome).padding(4.dp))
            Box(Modifier.align(Alignment.BottomStart)) { Stamp("COSMOS · FILES · ", Violet, Ink, 84.dp, haptic) }
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
                    Row(
                        Modifier.fillMaxWidth().height(46.dp).tap(haptic, true, onUp).border(1.5.dp, Ink, CircleShape).padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Go up", fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(dir.path.removePrefix(root.path).ifEmpty { "/" }, color = Grey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp))
                    }
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
                    Row(Modifier.fillMaxWidth().tap(haptic) { if (f.isDirectory) onDir(f) else openFile(ctx, f) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Ink), contentAlignment = Alignment.Center) {
                            Text(if (f.isDirectory) "▸" else f.extension.take(3).uppercase().ifEmpty { "•" }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
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

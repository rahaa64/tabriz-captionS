package com.turkishfacts.captions

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Bg = Color(0xFF101418)
private val Card = Color(0xFF1B2128)
private val Accent = Color(0xFFE53935)
private val Gold = Color(0xFFFFD400)

sealed class Screen {
    object Home : Screen()
    data class Busy(val title: String, val progress: Float?) : Screen()
    object Editor : Screen()
    data class Done(val file: File) : Screen()
}

class MainActivity : ComponentActivity() {
    private lateinit var mm: ModelManager
    private lateinit var exporter: Exporter
    private val converter by lazy { TabrizConverter(assets.open("tabriz_rules.json").readBytes().toString(Charsets.UTF_8)) }

    private var screen by mutableStateOf<Screen>(Screen.Home)
    private var model by mutableStateOf(SpeechModel.ACCURATE)
    private var ready by mutableStateOf(mapOf<SpeechModel, Boolean>())
    private var videoUri by mutableStateOf<Uri?>(null)
    private val caps = mutableStateListOf<Caption>()
    private var look by mutableStateOf(CaptionLook())
    private var aspect by mutableStateOf(Aspect.ORIGINAL)
    private var maxWords by mutableStateOf(4)
    private var selected by mutableStateOf(0)
    private var message by mutableStateOf<String?>(null)
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var cloud by mutableStateOf(false)
    private var apiKey by mutableStateOf("")
    private var job: Job? = null
    @Volatile private var cancelFlag = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mm = ModelManager(this)
        exporter = Exporter(this)
        refreshReady()
        apiKey = prefs.getString("eleven_key", "") ?: ""
        cloud = prefs.getBoolean("cloud", true)
        handleShare(intent)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Gold, background = Bg, surface = Card)) {
                    Surface(Modifier.fillMaxSize(), color = Bg) { App() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val u: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        if (u != null) { videoUri = u; screen = Screen.Home }
    }

    private fun chooseCloud(on: Boolean) { cloud = on; prefs.edit().putBoolean("cloud", on).apply() }

    private fun refreshReady() { ready = SpeechModel.values().associateWith { mm.isReady(it) } }

    private fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------- actions ----------------
    private fun download(m: SpeechModel) {
        cancelFlag = false; keepAwake(true)
        job = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    mm.download(m, { cancelFlag }) { got, total ->
                        screen = Screen.Busy("دانلود مدل ${m.title}\n${got / 1_000_000} از ${total / 1_000_000} مگابایت", got.toFloat() / total)
                    }
                }
                message = "مدل آماده است. فقط همین یک بار لازم بود."
            } catch (e: Exception) {
                message = if (cancelFlag) "دانلود متوقف شد؛ دفعه بعد از همان‌جا ادامه می‌دهد." else "دانلود نشد: ${e.message}\nاینترنت را چک کن و دوباره بزن (از همان‌جا ادامه می‌دهد)."
            } finally {
                refreshReady(); keepAwake(false); screen = Screen.Home
            }
        }
    }

    private fun transcribe() {
        val uri = videoUri ?: return
        cancelFlag = false; keepAwake(true)
        job = lifecycleScope.launch {
            try {
                val useCloud = cloud
                val key = apiKey.trim()
                val result = withContext(Dispatchers.Default) {
                    screen = Screen.Busy("استخراج صدای ویدیو…", null)
                    val audio = AudioDecoder.decode(this@MainActivity, uri) { p -> screen = Screen.Busy("استخراج صدای ویدیو…", p) }
                    if (useCloud) {
                        screen = Screen.Busy("ارسال صدا به ElevenLabs و تشخیص گفتار…\n(به اینترنت نیاز دارد)", null)
                        val words = withContext(Dispatchers.IO) { Scribe.transcribe(key, audio, AudioDecoder.RATE) }
                        return@withContext Scribe.toCaptions(words, maxWords, converter)
                    }
                    screen = Screen.Busy("آماده‌سازی مدل گفتار…", null)
                    val tr = Transcriber(mm, model, converter)
                    try {
                        tr.transcribe(audio, maxWords, { cancelFlag }) { p ->
                            screen = Screen.Busy("تشخیص گفتار و نوشتن کپشن تبریزی…\n${(p * 100).toInt()}٪", p)
                        }
                    } finally { tr.release() }
                }
                caps.clear(); caps.addAll(result); selected = 0
                if (result.isEmpty()) { message = "گفتاری پیدا نشد."; screen = Screen.Home } else screen = Screen.Editor
            } catch (e: Throwable) {
                message = "خطا: ${e.message ?: e.javaClass.simpleName}"; screen = Screen.Home
            } finally { keepAwake(false) }
        }
    }

    private fun export() {
        val uri = videoUri ?: return
        keepAwake(true)
        screen = Screen.Busy("ساخت ویدیو با کپشن…", 0f)
        job = lifecycleScope.launch {
            try {
                val f = exporter.export(uri, caps.toList(), look, aspect) { p -> screen = Screen.Busy("ساخت ویدیو با کپشن…\n$p٪", p / 100f) }
                withContext(Dispatchers.IO) { exporter.saveToGallery(f) }
                screen = Screen.Done(f)
            } catch (e: Throwable) {
                message = "ساخت ویدیو نشد: ${e.message ?: e.javaClass.simpleName}"; screen = Screen.Editor
            } finally { keepAwake(false) }
        }
    }

    private fun cancel() {
        cancelFlag = true; exporter.cancel(); job?.cancel()
        screen = if (caps.isNotEmpty() && videoUri != null) Screen.Editor else Screen.Home
        keepAwake(false); refreshReady()
    }

    private fun share(f: File) {
        val u = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "اشتراک ویدیو"))
    }

    // ---------------- UI ----------------
    @Composable
    private fun App() {
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            when (val s = screen) {
                Screen.Home -> HomeScreen()
                is Screen.Busy -> BusyScreen(s)
                Screen.Editor -> EditorScreen()
                is Screen.Done -> DoneScreen(s.file)
            }
            message?.let { m ->
                AlertDialog(onDismissRequest = { message = null }, confirmButton = { TextButton({ message = null }) { Text("باشه") } },
                    text = { Text(m, textAlign = TextAlign.Right) })
            }
        }
    }

    @Composable
    private fun HomeScreen() {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) videoUri = u }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("کپشن آذری تبریز", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("ویدیو بده، کپشن ترکی تبریز و ارومیه با خط فارسی و انیمیشن بگیر.",
                color = Color(0xFFB8C0C8), fontSize = 14.sp)
            Text("تشخیص گفتار", color = Color.White, fontWeight = FontWeight.Bold)
            Column(Modifier.fillMaxWidth().clip12().background(if (cloud) Color(0xFF2A3038) else Card)
                .border(1.dp, if (cloud) Accent else Color.Transparent, RoundedCornerShape(12.dp))
                .clickable { chooseCloud(true) }.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = cloud, onClick = { chooseCloud(true) })
                    Column(Modifier.weight(1f)) {
                        Text("ElevenLabs Scribe — دقیق‌ترین (آنلاین)", color = Color.White)
                        Text("با کلید API حساب ElevenLabs خودت؛ هر دقیقه حدود ۳۳۰ اعتبار", color = Color(0xFFB8C0C8), fontSize = 12.sp)
                    }
                }
                if (cloud) {
                    OutlinedTextField(value = apiKey, onValueChange = { v -> apiKey = v.trim(); prefs.edit().putString("eleven_key", apiKey).apply() },
                        label = { Text("کلید API (elevenlabs.io ← Settings ← API Keys)", fontSize = 12.sp) },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            }
            Text("یا آفلاین روی گوشی (Whisper؛ برای آذری ضعیف‌تر)", color = Color(0xFFB8C0C8), fontSize = 13.sp)
            SpeechModel.values().forEach { m ->
                val ok = ready[m] == true
                val on = !cloud && model == m
                Row(Modifier.fillMaxWidth().clip12().background(if (on) Color(0xFF2A3038) else Card)
                    .border(1.dp, if (on) Accent else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { model = m; chooseCloud(false) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = on, onClick = { model = m; chooseCloud(false) })
                    Column(Modifier.weight(1f)) {
                        Text(m.title, color = Color.White)
                        Text(if (ok) "دانلود شده ✓" else "هنوز دانلود نشده", color = if (ok) Color(0xFF7CD992) else Color(0xFFB8C0C8), fontSize = 12.sp)
                    }
                    if (!ok) Button(onClick = { download(m) }) { Text("دانلود") }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }, Modifier.fillMaxWidth()) {
                Text(if (videoUri == null) "انتخاب ویدیو" else "ویدیو انتخاب شد ✓ (عوض کردن)")
            }
            Text("حداکثر کلمه در هر خط: $maxWords", color = Color.White)
            Slider(value = maxWords.toFloat(), onValueChange = { maxWords = it.toInt() }, valueRange = 2f..7f, steps = 4)
            val canRun = if (cloud) apiKey.isNotBlank() else ready[model] == true
            Button(onClick = { transcribe() }, enabled = videoUri != null && canRun, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("ساخت کپشن", fontSize = 18.sp)
            }
            if (cloud && apiKey.isBlank()) Text("کلید API ElevenLabs را وارد کن.", color = Gold, fontSize = 13.sp)
            if (!cloud && ready[model] != true) Text("اول مدل را دانلود کن (فقط یک بار، بهتر است با وای‌فای).", color = Gold, fontSize = 13.sp)
        }
    }

    @Composable
    private fun BusyScreen(s: Screen.Busy) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.title, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            if (s.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { s.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { cancel() }) { Text("توقف") }
        }
    }

    @Composable
    private fun EditorScreen() {
        val uri = videoUri ?: return
        val info = remember(uri) { VideoProbe.info(this, uri) }
        val renderer = remember { CaptionRenderer(this) }
        var preview by remember { mutableStateOf<Bitmap?>(null) }
        val sel = selected.coerceIn(0, (caps.size - 1).coerceAtLeast(0))
        val cap = caps.getOrNull(sel)
        LaunchedEffect(cap, look, aspect) {
            if (cap == null) return@LaunchedEffect
            preview = withContext(Dispatchers.IO) { makePreview(uri, info, cap, renderer) }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ screen = Screen.Home }) { Text("← برگشت") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { export() }) { Text("ساخت ویدیو") }
            }
            Row(Modifier.fillMaxWidth().height(230.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(130.dp).fillMaxHeight().clip12().background(Color.Black), contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chips(CaptionStyle.values().toList(), look.style, { it.title }) { look = look.copy(style = it) }
                    Chips(Aspect.values().toList(), aspect, { it.title }) { aspect = it }
                    Text("اندازه متن", color = Color.White, fontSize = 12.sp)
                    Slider(look.size, { look = look.copy(size = it) }, valueRange = 0.7f..1.5f, modifier = Modifier.height(24.dp))
                    Text("جای کپشن (بالا ← پایین)", color = Color.White, fontSize = 12.sp)
                    Slider(look.yFrac, { look = look.copy(yFrac = it) }, valueRange = 0.2f..0.9f, modifier = Modifier.height(24.dp))
                }
            }
            Text("روی هر خط بزن تا پیش‌نمایش ببینی؛ متن را مستقیم درست کن.", color = Color(0xFFB8C0C8), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(caps, key = { i, c -> "${c.startMs}_$i" }) { i, c ->
                    Column(Modifier.fillMaxWidth().clip12().background(if (i == sel) Color(0xFF2A3038) else Card)
                        .clickable { selected = i }.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${fmt(c.startMs)} – ${fmt(c.endMs)}", color = Gold, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton({ caps.removeAt(i); if (selected >= caps.size) selected = (caps.size - 1).coerceAtLeast(0) }) { Text("حذف", color = Color(0xFFFF8A80)) }
                        }
                        OutlinedTextField(value = c.text, onValueChange = { v -> caps[i] = c.copy(text = v); selected = i },
                            modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Right, fontSize = 17.sp))
                    }
                }
            }
        }
    }

    @Composable
    private fun <T> Chips(items: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { it2 ->
                    FilterChip(selected = it2 == current, onClick = { onPick(it2) }, label = { Text(label(it2), fontSize = 12.sp) })
                }
            }
        }
    }

    @Composable
    private fun DoneScreen(f: File) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ویدیو آماده شد ✓", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("در گالری، پوشه Movies/TabrizCaptions ذخیره شد.", color = Color(0xFFB8C0C8), textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { share(f) }, Modifier.fillMaxWidth()) { Text("اشتراک‌گذاری") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { screen = Screen.Editor }, Modifier.fillMaxWidth()) { Text("ویرایش دوباره") }
            Spacer(Modifier.height(8.dp))
            TextButton({ caps.clear(); videoUri = null; screen = Screen.Home }) { Text("ویدیوی جدید") }
        }
    }

    // ---------------- helpers ----------------
    private fun makePreview(uri: Uri, info: VideoInfo, c: Caption, r: CaptionRenderer): Bitmap {
        val (ow, oh) = VideoProbe.outputSize(info, aspect)
        val s = 540f / maxOf(ow, oh)
        val pw = (ow * s).toInt().coerceAtLeast(2); val ph = (oh * s).toInt().coerceAtLeast(2)
        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(android.graphics.Color.DKGRAY)
        VideoProbe.frame(this, uri, (c.startMs + c.endMs) / 2)?.let { fr ->
            val sc = maxOf(pw.toFloat() / fr.width, ph.toFloat() / fr.height) // crop to fill
            val m = Matrix().apply { postScale(sc, sc); postTranslate((pw - fr.width * sc) / 2, (ph - fr.height * sc) / 2) }
            cv.drawBitmap(fr, m, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val local = ((c.endMs - c.startMs) * 0.75).toLong()
        r.drawCaption(cv, pw, ph, c, local, look)
        return out
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100) }
}

private fun Modifier.clip12() = this.then(Modifier.clip(RoundedCornerShape(12.dp)))

package com.vortexx.booster

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.vortexx.booster.network.TowerIpAndSwitchEngine
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.*
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs

// ── پالت سایبرپانک VORTEX-X (نئون قرمز/فیروزه‌ای روی مشکی مات) ──
val VortexBg = Color(0xFF07080D)
val VortexPanel = Color(0xFF141824)
val VortexCyan = Color(0xFF00F2FE)
val VortexRed = Color(0xFFFF0844)
val VortexText = Color(0xFFE8F6FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VortexXScreen() }
    }
}

/** پینگ واقعی: پرس‌وجوی DNS به 1.1.1.1 با انتظار برای پاسخ (RTT واقعی — نه پینگ کاذب!) */
fun dnsPingRealRtt(): Long {
    return try {
        DatagramSocket().use { s ->
            s.soTimeout = 900
            val id = (0 until 65535).random()
            val q = ByteArrayOutputStream().apply {
                write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
                for (label in listOf("example", "com")) { write(label.length); write(label.toByteArray(Charsets.US_ASCII)) }
                write(0); write(byteArrayOf(0, 1, 0, 1))
            }.toByteArray()
            val t0 = System.nanoTime()
            s.send(DatagramPacket(q, q.size, InetAddress.getByName("1.1.1.1"), 53))
            s.receive(DatagramPacket(ByteArray(512), 512))
            (System.nanoTime() - t0) / 1_000_000
        }
    } catch (_: Exception) { -1L }
}

@Composable
fun VortexXScreen() {
    val ctx = LocalContext.current
    var ping by remember { mutableStateOf<Long?>(null) }
    var jitter by remember { mutableStateOf<Long?>(null) }
    var lastPing by remember { mutableStateOf<Long?>(null) }
    var ghostOn by remember { mutableStateOf(false) }
    var towerInfo by remember { mutableStateOf("برای اسکن دکل، مجوز موقعیت را بده و «اسکن» را بزن") }
    var nearbyTowers by remember { mutableStateOf(listOf<String>()) }

    // حلقه پینگ زنده (RTT واقعی هر ثانیه)
    LaunchedEffect(Unit) {
        while (true) {
            val r = dnsPingRealRtt()
            if (r > 0) {
                ping = r
                val lp = lastPing
                jitter = if (lp != null) abs(r - lp) else 0
                lastPing = r
            } else { ping = null }
            delay(1000)
        }
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) startVpn(ctx, ghostOn)
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) scanTowers(ctx) { info, list -> towerInfo = info; nearbyTowers = list }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(VortexBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("VORTEX-X", color = VortexText, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text("موتور پینگِ صفر برای کالاف دیوتی موبایل", color = VortexCyan, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))

        // ── توربین انیمیشنی (Rive در صورت وجود .riv وگرنه Lottie 120FPS) ──
        ReactorView(ghostOn = ghostOn)
        Spacer(Modifier.height(12.dp))

        // ── داشبورد زنده پینگ / جیتر ──
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("پینگ (ms)", ping?.toString() ?: "--", VortexCyan, Modifier.weight(1f))
            StatCard("نوسان Jitter", jitter?.toString() ?: "--", VortexRed, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        // ── پنل رادار دکل ──
        NeonPanel {
            Text("📡 رادار دکل و شبکه", color = VortexCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(towerInfo, color = VortexText, fontSize = 12.sp)
            nearbyTowers.take(5).forEach {
                Text(it, color = Color(0xFF9FB8C8), fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("اسکن دکل‌ها", VortexCyan, Modifier.weight(1f)) {
                    permLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                }
                NeonButton("⚡ قفل روی دکل پرسرعت", VortexRed, Modifier.weight(1f)) {
                    try { TowerIpAndSwitchEngine(ctx).switchToFastestTowerAndLockBand() } catch (_: Exception) {}
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── سوییچ حالت شبح (ضد DPI) ──
        NeonPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("👻 حالت شبح (ضد دیوار آتش و DPI)", color = VortexText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("استتار ترافیک + نویز فریب اسکنر + پالس بیدارباش آنتن", color = Color(0xFF9FB8C8), fontSize = 10.sp)
                }
                Switch(checked = ghostOn, onCheckedChange = { ghostOn = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = VortexRed, checkedThumbColor = VortexText))
            }
        }
        Spacer(Modifier.height(16.dp))

        // ── دکمه BOOST ──
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    Brush.horizontalGradient(listOf(VortexCyan, VortexRed)),
                    RoundedCornerShape(18.dp)
                )
                .clickable {
                    val activity = ctx as? Activity ?: return@clickable
                    val consent = android.net.VpnService.prepare(activity)
                    if (consent != null) vpnConsentLauncher.launch(consent)
                    else startVpn(ctx, ghostOn)
                },
            contentAlignment = Alignment.Center
        ) {
            Text("🔥 BOOST — شروع توربین turbo", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }
        if (VortexVpnService.engineRunning) {
            Spacer(Modifier.height(8.dp))
            Text("✅ توربین فعال است — بازی را باز کن!", color = VortexCyan, fontSize = 12.sp)
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun startVpn(ctx: android.content.Context, ghost: Boolean) {
    val intent = Intent(ctx, VortexVpnService::class.java).putExtra(VortexVpnService.EXTRA_GHOST, ghost)
    ContextCompat.startForegroundService(ctx, intent)
}

private fun scanTowers(ctx: android.content.Context, onDone: (String, List<String>) -> Unit) {
    try {
        val engine = TowerIpAndSwitchEngine(ctx)
        val d = engine.getConnectedTowerIpAndPort()
        val info = "IP شما: ${d.clientIp}\n" +
                "درگاه دکل: ${d.towerGatewayIp} | پورت UDP: ${d.activeUdpPort}\n" +
                "PCI دکل: ${d.physicalTowerId_PCI} | کانال: ${d.frequencyChannel}\n" +
                "پینگ دکل: ${d.towerPingMs}ms | وضعیت: ${d.recommendation}"
        onDone(info, engine.scanAllNearbyTowers())
    } catch (e: Exception) {
        onDone("خطا در اسکن دکل: ${e.message}", emptyList())
    }
}

@Composable
fun StatCard(title: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    NeonPanel(modifier) {
        Text(title, color = Color(0xFF9FB8C8), fontSize = 11.sp)
        Text(value, color = accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun NeonPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(VortexPanel, RoundedCornerShape(14.dp))
            .border(1.dp, VortexCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        content = content
    )
}

@Composable
fun NeonButton(text: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .border(1.dp, accent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = VortexText, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

/** توربین: اگر assets/animations/vortex_reactor.riv موجود باشد Rive (State Machine) اجرا می‌شود،
 *  وگرنه Lottie (vortex_pulse.json) و در نبود آن، قلب نبض‌دار نئونی. */
@Composable
fun ReactorView(ghostOn: Boolean) {
    val ctx = LocalContext.current
    val hasRiv = remember { try { ctx.assets.list("animations")?.contains("vortex_reactor.riv") == true } catch (_: Exception) { false } }
    val hasLottie = remember { try { ctx.assets.list("animations")?.contains("vortex_pulse.json") == true } catch (_: Exception) { false } }

    if (hasRiv) {
        // مسیر Rive: کلاس با بازتاب (Reflection) لود می‌شود تا تغییر نسخه API هرگز بیلد را نشکند
        AndroidView(factory = { c ->
            try {
                val cls = Class.forName("app.rive.runtime.kotlin.RiveAnimationView")
                val riv = cls.getConstructor(android.content.Context::class.java)
                    .newInstance(c) as android.view.View
                // فایل .riv از assets به مسیر قابل خواندن کپی و با setRiveUrl لود می‌شود
                val f = java.io.File(c.filesDir, "vortex_reactor.riv")
                c.assets.open("animations/vortex_reactor.riv").use { ins ->
                    f.outputStream().use { ins.copyTo(it) }
                }
                try {
                    cls.methods.firstOrNull { it.name == "setRiveUrl" }
                        ?.invoke(riv, f.toURI().toString())
                } catch (_: Throwable) { }
                riv
            } catch (t: Throwable) {
                android.widget.TextView(c).apply { text = "RIVE FILE UNREADABLE" }
            }
        }, modifier = Modifier.fillMaxWidth().height(190.dp))
    } else if (hasLottie) {
        val comp by rememberLottieComposition(LottieCompositionSpec.Asset("animations/vortex_pulse.json"))
        val progress by animateLottieCompositionAsState(comp, iterations = LottieConstants.IterateForever, speed = if (ghostOn) 2f else 1f)
        com.airbnb.lottie.compose.LottieAnimation(
            composition = comp, progress = { progress },
            modifier = Modifier.fillMaxWidth().height(190.dp)
        )
    } else {
        // فالبک نئونی (بدون هیچ فایل انیمیشن)
        val inf = rememberInfiniteTransition(label = "core")
        val glow by inf.animateFloat(0.35f, 1f, infiniteRepeatable(tween(if (ghostOn) 500 else 1200)), label = "glow")
        Box(
            Modifier
                .size(170.dp)
                .background(
                    Brush.radialGradient(listOf(if (ghostOn) VortexRed.copy(alpha = glow) else VortexCyan.copy(alpha = glow), Color.Transparent))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(if (ghostOn) "👻 GHOST" else "VX CORE", color = VortexText, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
    }
}

package com.vortexx.booster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.vortexx.booster.socks5.LocalSocks5Server
import java.io.File
import kotlin.concurrent.thread

class VortexVpnService : VpnService() {

    companion object {
        const val EXTRA_GHOST = "ghost"
        const val CHANNEL_ID = "vortex_vpn_channel"
        const val NOTIF_ID = 4601
        @Volatile var engineRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var socksServer: LocalSocks5Server? = null
    @Volatile private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { ghostMode = it.getBooleanExtra(EXTRA_GHOST, ghostMode) }
        startForegroundSafely()
        if (!isRunning) {
            isRunning = true
            engineRunning = true
            lockHardwareWifiLowLatency()
            startGhostNetTunnel()
        }
        return START_STICKY
    }

    private var ghostMode = false

    // قفل کردن سخت‌افزار Wi-Fi گوشی روی حالت گیمینگ بدون تاخیر
    private fun lockHardwareWifiLowLatency() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "VortexX_UltraLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    // راه‌اندازی تونل ایزوله مخصوص کالاف دیوتی (بدون روت) + رله واقعی TCP/UDP
    private fun startGhostNetTunnel() {
        val builder = Builder()
            .setSession("VORTEX-X CODM TURBO")
            .setMtu(1388) // سایز طلایی ضد تیکه‌تکه شدن تیرها
            .addAddress("10.88.0.2", 30)
            .addDnsServer("1.1.1.1") // DNS فوق‌سریع کلودفلر
            .addRoute("0.0.0.0", 0)

        // فقط کالاف دیوتی وارد تونل VIP می‌شود!
        try {
            builder.addAllowedApplication("com.activision.callofduty.shooter")
        } catch (e: Exception) { e.printStackTrace() }

        vpnInterface = builder.establish()
        val tun = vpnInterface
        if (tun == null) {
            // رضایت کاربر داده نشده یا تونل ساخته نشد
            isRunning = false
            engineRunning = false
            stopSelf()
            return
        }

        // [اصلاح مینِ سیم مرده] قفل اولویت کرنل روی fd تونل — واقعاً صدا زده می‌شود
        try { NativeCore.lockKernelSocketPriority(tun.fd) } catch (_: Throwable) {}

        // ۱. سرور SOCKS5 محلی (سوکت‌های محافظت‌شده → اینترنت واقعی)
        socksServer = LocalSocks5Server(this).also { it.start() }

        // ۲. کانفیگ تونل hev — ورودی: fd تونل، خروجی: SOCKS5 محلی
        val cfg = File(cacheDir, "vortex_tun.yaml")
        cfg.writeText(
            "tun-fd: ${tun.fd}\n" +
            "mtu: 1388\n" +
            "socks5: 127.0.0.1:1080\n" +
            "log-level: warn\n"
        )

        thread(name = "VortexHevWorker", isDaemon = true) {
            NativeCore.startSocksTunnel(cfg.absolutePath)
        }

        // ۳. [اصلاح مینِ پالس بی‌مهار] پالس 5G با دریچه نرخ: هر ۳ ثانیه (نه هر بسته!)
        thread(name = "Vortex5GPulse", isDaemon = true) {
            while (isRunning) {
                try { NativeCore.trigger5GKeepAlive() } catch (_: Throwable) {}
                Thread.sleep(3000)
            }
        }
    }

    private fun startForegroundSafely() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VORTEX-X Turbo", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VORTEX-X فعال است ⚡")
            .setContentText("توربین turbo روی بازی شما در حال چرخش است")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        isRunning = false
        engineRunning = false
        NativeCore.stopSocksTunnel()
        socksServer?.stop()
        wifiLock?.let { if (it.isHeld) it.release() }
        try { vpnInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

package com.vortexx.booster

import java.nio.ByteBuffer

object NativeCore {
    init {
        System.loadLibrary("vortex_engine")      // هسته Rust + پل C++ (کد فرمانده)
        System.loadLibrary("hev-socks5-tunnel")  // موتور تونل TCP/UDP (ساخته‌شده در CI)
    }

    external fun lockKernelSocketPriority(fd: Int): Boolean
    external fun injectVipHeaderNative(buffer: ByteBuffer, length: Int): Boolean
    external fun trigger5GKeepAlive()
    external fun startSocksTunnel(configPath: String): Int
    external fun stopSocksTunnel()
}

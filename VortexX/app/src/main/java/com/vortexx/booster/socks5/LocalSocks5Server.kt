package com.vortexx.booster.socks5

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.vortexx.booster.NativeCore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import kotlin.concurrent.thread

/**
 * سرور SOCKS5 محلی (TCP CONNECT + UDP ASSOCIATE) با سوکت‌های محافظت‌شده (protect).
 * موتور تونل (hev) بسته‌های اپلیکیشن گیم را به اینجا می‌آورد و ما آن‌ها را با
 * سوکت‌های آزادشده از تونل، مستقیم به اینترنت واقعی می‌فرستیم.
 */
class LocalSocks5Server(private val vpn: VpnService) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start(port: Int = 1080) {
        if (running) return
        running = true
        thread(name = "VortexSocks5Accept") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", port))
                serverSocket = ss
                while (running) {
                    val client = ss.accept()
                    thread(name = "VortexSocks5Conn") { handleClient(client) }
                }
            } catch (_: Exception) { /* سرور بسته شد */ }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val ins = DataInputStream(client.getInputStream())
            if (ins.readByte() != 5.toByte()) { client.close(); return }
            val nmethods = ins.readUnsignedByte()
            ins.skipBytes(nmethods)
            val outs = DataOutputStream(client.getOutputStream())
            outs.write(byteArrayOf(5, 0)); outs.flush() // بدون احراز هویت

            val cmd = ins.readUnsignedByte()
            ins.readUnsignedByte() // RSV
            val atyp = ins.readUnsignedByte()

            val dst: InetAddress
            val dport: Int
            when (atyp) {
                1 -> { val b = ByteArray(4); ins.readFully(b); dst = InetAddress.getByAddress(b); dport = ins.readUnsignedShort() }
                3 -> { val len = ins.readUnsignedByte(); val h = ByteArray(len); ins.readFully(h); dst = InetAddress.getByName(String(h)); dport = ins.readUnsignedShort() }
                4 -> { val b = ByteArray(16); ins.readFully(b); dst = InetAddress.getByAddress(b); dport = ins.readUnsignedShort() }
                else -> { client.close(); return }
            }

            when (cmd) {
                1 -> handleConnect(client, dst, dport)
                3 -> handleUdpAssociate(client)
                else -> client.close()
            }
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    // ─── TCP: اتصال مستقیم با سوکت محافظت‌شده ───
    private fun handleConnect(client: Socket, dst: InetAddress, dport: Int) {
        var remote: Socket? = null
        try {
            vpn.protect(client)
            applyLowLatency(ParcelFileDescriptor.fromSocket(client).fd)

            val r = Socket()
            remote = r
            r.tcpNoDelay = true
            r.connect(InetSocketAddress(dst, dport), 8000)

            val outs = DataOutputStream(client.getOutputStream())
            outs.write(byteArrayOf(5, 0, 0, 1) + r.localAddress.address +
                    byteArrayOf((r.port shr 8).toByte(), r.port.toByte()))
            outs.flush()

            val up = thread { pump(client.getInputStream(), r.getOutputStream()) }
            pump(r.getInputStream(), client.getOutputStream())
            up.join(1500)
            try { client.close() } catch (_: Exception) {}
            try { r.close() } catch (_: Exception) {}
        } catch (_: Exception) {
            try {
                val outs = DataOutputStream(client.getOutputStream())
                outs.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)); outs.flush()
                client.close()
            } catch (_: Exception) {}
        }
    }

    // ─── UDP: رله تک‌حلقه‌ای با تفکیک جهت (بازی ↔ سرور واقعی) ───
    private fun handleUdpAssociate(client: Socket) {
        var udp: DatagramSocket? = null
        try {
            val u = DatagramSocket()
            u.reuseAddress = true
            vpn.protect(u)
            applyLowLatency(ParcelFileDescriptor.fromDatagramSocket(u).fd)
            udp = u

            val outs = DataOutputStream(client.getOutputStream())
            outs.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1,
                (u.localPort shr 8).toByte(), u.localPort.toByte()))
            outs.flush()

            var clientAddr: SocketAddress? = null
            val pk = DatagramPacket(ByteArray(65535), 65535)

            while (running) {
                u.receive(pk)
                val src = pk.socketAddress
                if (src == clientAddr) {
                    // جهت ۱: بسته بازی → بازکردن هدر SOCKS → ارسال واقعی
                    val data = pk.data
                    if (pk.length < 10) continue
                    val atyp = data[3].toInt() and 0xFF
                    var off: Int
                    val dst: InetAddress
                    when (atyp) {
                        1 -> { dst = InetAddress.getByAddress(data.copyOfRange(4, 8)); off = 8 }
                        4 -> { dst = InetAddress.getByAddress(data.copyOfRange(4, 20)); off = 20 }
                        3 -> {
                            val len = data[4].toInt() and 0xFF
                            dst = InetAddress.getByName(String(data, 5, len)); off = 5 + len
                        }
                        else -> continue
                    }
                    if (off + 2 > pk.length) continue
                    val port = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                    off += 2
                    val payload = data.copyOfRange(off, pk.length)
                    u.send(DatagramPacket(payload, payload.size, InetSocketAddress(dst, port)))
                } else {
                    // جهت ۲: پاسخ سرور واقعی → بسته‌بندی SOCKS → تحویل به بازی
                    if (clientAddr == null) continue
                    val addr = pk.address.address
                    val out = ByteArray(10 + addr.size + pk.length)
                    out[2] = 0; out[3] = 1
                    System.arraycopy(addr, 0, out, 4, addr.size)
                    out[4 + addr.size] = (pk.port shr 8).toByte()
                    out[5 + addr.size] = pk.port.toByte()
                    System.arraycopy(pk.data, 0, out, 6 + addr.size, pk.length)
                    u.send(DatagramPacket(out, out.size, clientAddr))
                }
            }
        } catch (_: Exception) { /* پایان اتصال */ }
        finally {
            try { udp?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun applyLowLatency(fd: Int) {
        try { NativeCore.lockKernelSocketPriority(fd) } catch (_: Throwable) {}
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(32768)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {}
        try { output.flush() } catch (_: Exception) {}
    }
}

#include <jni.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <string>
#include <thread>

extern "C" {

// لینک شدن به توابع موتور Rust (هسته‌ی فرمانده)
extern bool vortex_process_packet_nanosecond(unsigned char* packet, size_t len);
extern void vortex_fire_5g_keepalive_pulse(const unsigned char* ip, size_t len);

// [اصلاح آلفا] لینک به موتور تونل SOCKS5 (hev-socks5-tunnel — ساخته شده در CI)
extern int hev_socks5_tunnel_main(const char *config_path);
extern void hev_socks5_tunnel_terminate(void);

static std::thread *g_hev_thread = nullptr;

// ۱. قفل سخت‌افزاری سوکت شبکه روی اولویت ۶ کرنل (Ultra-Low Latency Socket Lock)
JNIEXPORT jboolean JNICALL
Java_com_vortexx_booster_NativeCore_lockKernelSocketPriority(JNIEnv* env, jobject thiz, jint fd) {
    if (fd < 0) return JNI_FALSE;
    int priority = 6; // بالاترین اولویت صف مخابراتی لینوکس
    setsockopt(fd, SOL_SOCKET, SO_PRIORITY, &priority, sizeof(priority));

    int tos = IPTOS_LOWDELAY | 0xB8; // [اصلاح آلفا] IP_PROTO_IP -> IPPROTO_IP (استاندارد Bionic/NDK)
    setsockopt(fd, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));

    int buf = 524288; // بافر ۵۱۲ کیلوبایت برای جلوگیری از صف کشیدن گلوله‌ها
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buf, sizeof(buf));
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf, sizeof(buf));
    return JNI_TRUE;
}

// ۲. ارسال بسته از اندروید به موتور Rust در کسری از نانوثانیه (Zero-Copy)
JNIEXPORT jboolean JNICALL
Java_com_vortexx_booster_NativeCore_injectVipHeaderNative(JNIEnv* env, jobject thiz, jobject direct_buffer, jint length) {
    auto* raw_pkt = static_cast<unsigned char*>(env->GetDirectBufferAddress(direct_buffer));
    if (!raw_pkt) return JNI_FALSE;
    return vortex_process_packet_nanosecond(raw_pkt, static_cast<size_t>(length)) ? JNI_TRUE : JNI_FALSE;
}

// ۳. شلیک پالس ضد خواب آنتن 5G
JNIEXPORT void JNICALL
Java_com_vortexx_booster_NativeCore_trigger5GKeepAlive(JNIEnv* env, jobject thiz) {
    vortex_fire_5g_keepalive_pulse(nullptr, 0);
}

// ۴. [تایتان] راه‌اندازی موتور تونل (TCP+UDP) روی یک ترد بومی — مسیر فایل کانفیگ YAML
JNIEXPORT jint JNICALL
Java_com_vortexx_booster_NativeCore_startSocksTunnel(JNIEnv* env, jobject thiz, jstring config_path) {
    if (g_hev_thread != nullptr) return -1; // در حال اجراست
    const char* p = env->GetStringUTFChars(config_path, nullptr);
    std::string cfg(p);
    env->ReleaseStringUTFChars(config_path, p);

    g_hev_thread = new std::thread([cfg]() {
        hev_socks5_tunnel_main(cfg.c_str()); // بلوک می‌شود تا terminate
    });
    return 0;
}

// ۵. [تایتان] توقف ایمن موتور تونل
JNIEXPORT void JNICALL
Java_com_vortexx_booster_NativeCore_stopSocksTunnel(JNIEnv* env, jobject thiz) {
    hev_socks5_tunnel_terminate();
    if (g_hev_thread != nullptr) {
        if (g_hev_thread->joinable()) g_hev_thread->join();
        delete g_hev_thread;
        g_hev_thread = nullptr;
    }
}

} // extern "C"

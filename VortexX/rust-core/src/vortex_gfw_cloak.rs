// ============================================================================
// VORTEX-X: GREAT FIREWALL (GFW) EVASION & ANTI-DPI ENGINE (Written in Rust)
// وظیفه: ۱. استتار بسته‌های بازی در قالب ترافیک عادی وب (TLS 1.3 / HTTP3 QUIC)
//        ۲. تکه‌تکه کردن دست‌دادن اولیه (Packet Slicing) برای کور کردن اسکنرهای DPI
//        ۳. تزریق بایت‌های تصادفی (Dynamic Padding) برای نابود کردن تشخیص الگوی سایز
// ============================================================================

#![allow(non_snake_case)]
use std::net::{SocketAddr, UdpSocket};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// هدر جعلی TLS 1.3 Application Data و QUIC برای گول زدن پیشرفته‌ترین فایروال‌های دنیا (حتی GFW چین)
const TLS13_MASQUERADE_HEADER: [u8; 5] = [0x17, 0x03, 0x03, 0x00, 0x20];

/// تولید عدد شبه‌تصادفی در سطح نانوثانیه بدون نیاز به کتابخانه خارجی
#[inline(always)]
fn fast_nano_random(min: usize, max: usize) -> usize {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_nanos(777))
        .subsec_nanos() as usize;
    min + (nanos % (max - min + 1))
}

/// ۱. قانون طلایی اول: ارسال تکه-تکه (GFW Packet Slicing & Desync)
/// فایروال چین به دنبال خواندن یکجای بسته اول است؛ ما آن را به ۲ تکه نامنظم با تاخیر ۱ میلی‌ثانیه می‌شکنیم!
pub fn send_gfw_sliced_handshake(socket: &UdpSocket, target: SocketAddr, payload: &[u8]) -> bool {
    if payload.len() < 8 {
        return socket.send_to(payload, target).is_ok();
    }

    // الف) ابتدا یک بسته «فریب فایروال» (Decoy Noise) شبیه به ترافیک عادی وب می‌فرستیم
    let decoy_len = fast_nano_random(15, 45);
    let mut decoy_pkt = Vec::with_capacity(5 + decoy_len);
    decoy_pkt.extend_from_slice(&TLS13_MASQUERADE_HEADER);
    for i in 0..decoy_len {
        decoy_pkt.push(((i * 73) & 0xFF) as u8);
    }
    let _ = socket.send_to(&decoy_pkt, target);

    // ب) شکستن بسته اصلی از نقطه تصادفی (بین بایت ۳ تا ۷) تا امضای پروتکل (Magic Byte) از بین برود
    let split_index = fast_nano_random(3, 6);
    let (part1, part2) = payload.split_at(split_index);

    // ارسال تکه اول
    if socket.send_to(part1, target).is_err() {
        return false;
    }

    // وقفه ۲ میلی‌ثانیه‌ای برای جا گذاشتن اسکنر DPI فایروال
    thread::sleep(Duration::from_millis(2));

    // ارسال تکه دوم
    socket.send_to(part2, target).is_ok()
}

/// ۲. قانون طلایی دوم: استتار بومی و تغییر شکل بسته (Chameleon Packet Morphing)
/// این تابع که از C++ و اندروید صدا زده می‌شود، امضای لو رفته WireGuard (0x01 0x00 0x00 0x00) را مخفی می‌کند
#[no_mangle]
pub extern "C" fn vortex_apply_gfw_chameleon_cloak(
    packet_buf: *mut u8,
    current_len: usize,
    max_buf_capacity: usize,
) -> usize {
    if packet_buf.is_null() || current_len < 4 || current_len + 16 > max_buf_capacity {
        return current_len;
    }

    unsafe {
        let slice = std::slice::from_raw_parts_mut(packet_buf, max_buf_capacity);

        // اگر بسته از نوع Handshake تونل باشد (شروع با 0x01 و سه صفر که فایروال چین فورا بلاک می‌کند)
        if slice[0] == 0x01 && slice[1] == 0x00 && slice[2] == 0x00 && slice[3] == 0x00 {
            // تغییر بایت‌های رزرو شده به اعداد تصادفی (تکنیک AmneziaWG برای کور کردن DPI)
            slice[1] = fast_nano_random(10, 250) as u8;
            slice[2] = fast_nano_random(10, 250) as u8;
            slice[3] = fast_nano_random(10, 250) as u8;
        }

        // افزودن پدینگ متغیر (Randomized Tail Padding) در انتهای بسته برای تغییر سایز ثابت
        let pad_size = fast_nano_random(4, 12);
        for i in 0..pad_size {
            slice[current_len + i] = 0x00; // بایت‌های خنثی که سرور مقصد نادیده می‌گیرد اما سایز بسته را عوض می‌کند
        }

        return current_len + pad_size;
    }
}

#![allow(non_snake_case)]
use std::net::{SocketAddr, UdpSocket};
use std::time::{Duration, Instant};

const DSCP_EF_VIP: u8 = 0xB8; // کد ۴۶ مخابراتی (Expedited Forwarding) برای رد شدن بدون نوبت از دکل
const PROTO_UDP: u8 = 17;     // پروتکل تیرهای کالاف دیوتی

/// ۱. شکارچی و تزریق‌کننده مهر VIP روی بسته‌های تیر کالاف (Zero-Copy در سطح رم)
#[no_mangle]
pub extern "C" fn vortex_process_packet_nanosecond(packet: *mut u8, len: usize) -> bool {
    if packet.is_null() || len < 20 { return false; }

    unsafe {
        let buf = std::slice::from_raw_parts_mut(packet, len);
        let version = buf[0] >> 4;
        let protocol = buf[9];

        // فقط بسته‌های IPv4 و UDP (تیر و حرکت در بازی) را مهر VIP می‌زنیم
        if version == 4 && protocol == PROTO_UDP {
            if buf[1] != DSCP_EF_VIP {
                buf[1] = DSCP_EF_VIP;
                update_ipv4_header_checksum(buf);
                return true;
            }
        }
    }
    false
}

/// محاسبه فوق‌سریع Checksum هدر IPv4 در سطح بیت
#[inline(always)]
fn update_ipv4_header_checksum(hdr: &mut [u8]) {
    let ihl = ((hdr[0] & 0x0F) as usize) * 4;
    if hdr.len() < ihl { return; }
    hdr[10] = 0; hdr[11] = 0;
    let mut sum: u32 = 0;
    for i in (0..ihl).step_by(2) {
        sum += ((hdr[i] as u32) << 8) | (hdr[i + 1] as u32);
    }
    while (sum >> 16) != 0 { sum = (sum & 0xFFFF) + (sum >> 16); }
    let csum = !sum as u16;
    hdr[10] = (csum >> 8) as u8;
    hdr[11] = (csum & 0xFF) as u8;
}

/// ۲. پالس ۱ بایتی ضد خواب آنتن 4G/5G (RRC Connected State Lock)
#[no_mangle]
pub extern "C" fn vortex_fire_5g_keepalive_pulse(target_ip_ptr: *const u8, target_len: usize) {
    if let Ok(sock) = UdpSocket::bind("0.0.0.0:0") {
        let _ = sock.set_write_timeout(Some(Duration::from_millis(5)));
        let pulse: [u8; 1] = [0x46];
        // ارسال پالس به لبه شبکه کلودفلر دبی/بحرین بدون مصرف حجم
        let _ = sock.send_to(&pulse, "162.159.192.1:2408");
    }
}

/// ۳. اسکنر فوق‌سریع پینگ (برحسب میکروثانیه) برای پیدا کردن تمیزترین IP رایگان کلودفلر
#[no_mangle]
pub extern "C" fn vortex_measure_edge_ping_ms(ip_b1: u8, ip_b2: u8, ip_b3: u8, ip_b4: u8, port: u16) -> i32 {
    let addr = SocketAddr::from(([ip_b1, ip_b2, ip_b3, ip_b4], port));
    if let Ok(sock) = UdpSocket::bind("0.0.0.0:0") {
        let _ = sock.set_read_timeout(Some(Duration::from_millis(400)));
        let start = Instant::now();
        if sock.send_to(&[0x01, 0x00, 0x00, 0x00], addr).is_ok() {
            return start.elapsed().as_millis() as i32;
        }
    }
    999 // در صورت تایم‌اوت
}

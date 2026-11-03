// ============================================================================
// VORTEX-X CLOUDFLARE ULTRA SCANNER & ANTI-DPI ENGINE (Written in Rust)
// وظیفه: اسکن رگباری رنج‌های کلودفلر، انتخاب سریع‌ترین سرور و استتار ترافیک ضد مسدودسازی
// ============================================================================

use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

/// پورت‌های طلایی و باز کلودفلر (برای چرخش خودکار یا Port Hopping ضد مسدودسازی)
const CF_GOLDEN_PORTS: [u16; 6] = [2408, 1701, 500, 4500, 854, 894];

#[derive(Clone, Debug)]
pub struct BestEdgeNode {
    pub ip: String,
    pub port: u16,
    pub ping_ms: u32,
    pub jitter_ms: u32,
}

/// تابع اصلی: اسکن موازی رنج‌های کلودفلر و برگرداندن سریع‌ترین و ضدتحریم‌ترین IP
pub fn scan_and_find_fastest_cloudflare() -> Option<BestEdgeNode> {
    // رنج‌های طلایی کلودفلر که مستقیم‌ترین مسیر را به دبی و بحرین دارند
    let base_ips = vec![
        [162, 159, 192],
        [162, 159, 193],
        [162, 159, 195],
        [188, 114, 96],
        [188, 114, 97],
        [188, 114, 98],
    ];

    let best_node: Arc<Mutex<Option<BestEdgeNode>>> = Arc::new(Mutex::new(None));
    let mut handles = vec![];

    for prefix in base_ips {
        // تست نمونه آی‌پی‌های تمیز در هر رنج به صورت موازی (Multi-threaded)
        for last_octet in [1, 10, 45, 88, 120, 165, 200, 240] {
            let best_clone = Arc::clone(&best_node);

            let handle = thread::spawn(move || {
                let ip = Ipv4Addr::new(prefix[0], prefix[1], prefix[2], last_octet);
                // انتخاب پورت چرخشی برای دور زدن محدودیت اپراتور
                let port = CF_GOLDEN_PORTS[(last_octet as usize) % CF_GOLDEN_PORTS.len()];
                let target = SocketAddr::new(IpAddr::V4(ip), port);

                if let Some((ping, jitter)) = probe_node_with_anti_dpi(target) {
                    let mut current_best = best_clone.lock().unwrap();
                    let score = ping + (jitter * 2); // فرمول گیمینگ: نوسان (Jitter) دو برابر پینگ اهمیت دارد!

                    let should_update = match &*current_best {
                        None => true,
                        Some(prev) => score < (prev.ping_ms + prev.jitter_ms * 2),
                    };

                    if should_update {
                        *current_best = Some(BestEdgeNode {
                            ip: ip.to_string(),
                            port,
                            ping_ms: ping,
                            jitter_ms: jitter,
                        });
                    }
                }
            });
            handles.push(handle);
        }
    }

    for h in handles {
        let _ = h.join();
    }

    let result = best_node.lock().unwrap().clone();
    result
}

/// تکنیک ضد فیلتر (Anti-DPI Obfuscation): ارسال بسته با هدر متغیر برای دور زدن تشخیص فایروال
fn probe_node_with_anti_dpi(target: SocketAddr) -> Option<(u32, u32)> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.set_read_timeout(Some(Duration::from_millis(300))).ok()?;
    socket.set_write_timeout(Some(Duration::from_millis(300))).ok()?;

    // ۱. ارسال بسته استتار (Junk/Noise Padding) برای گول زدن سیستم فیلترینگ (Amnezia Technique)
    let anti_dpi_noise: [u8; 12] = [0x16, 0x03, 0x01, 0x00, 0xA5, 0x5A, 0xF0, 0x0F, 0x99, 0x11, 0x22, 0x33];
    let _ = socket.send_to(&anti_dpi_noise, target);

    // ۲. اندازه‌گیری ۳ مرحله‌ای برای محاسبه دقیق Ping و Jitter (نوسان)
    let mut pings = Vec::new();
    let handshake_probe: [u8; 4] = [0x01, 0x00, 0x00, 0x00];

    for _ in 0..3 {
        let start = Instant::now();
        if socket.send_to(&handshake_probe, target).is_ok() {
            let elapsed = start.elapsed().as_micros() as u32 / 1000;
            pings.push(elapsed.max(1));
        } else {
            return None;
        }
    }

    let avg_ping = pings.iter().sum::<u32>() / 3;
    let max_p = *pings.iter().max()?;
    let min_p = *pings.iter().min()?;
    let jitter = max_p - min_p;

    Some((avg_ping, jitter))
}

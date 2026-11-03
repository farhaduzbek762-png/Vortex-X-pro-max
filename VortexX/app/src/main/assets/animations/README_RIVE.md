# 📌 راهنمای دقیق انیمیشن‌ها (وکتور — کارگردان انیمیشن)

اپلیکیشن VORTEX-X به‌صورت هوشمند انیمیشن را انتخاب می‌کند (اولویت: Rive → Lottie → فالبک نئونی).
برای فعال‌سازی، فایل‌ها را دقیقاً در این مسیر بگذار:

```
app/src/main/assets/animations/
├── vortex_reactor.riv    ← (Rive، اولویت اول)
└── vortex_pulse.json     ← (Lottie، پشتیبان)
```

## گزینه ۱: Rive (رایگان — ر死的 توصیه‌شده)
1. برو به `https://rive.app/community` (حساب رایگان بساز).
2. جستجو کن: `reactor` یا `turbine` یا `energy core` یا `speed gauge`.
3. یک انیمیشن حلقه‌ای (Loop) انتخاب کن → دکمه **Open in Editor**.
4. در ادیتور:
   - یک **State Machine** جدید بساز و اسمش را بگذار: `VortexSM`
   - یک **Trigger Input** اضافه کن با نام دقیق: `Overdrive_Boost`
   - دو حالت بساز: `Idle` (چرخش آرام) و `Overdrive_Boost` (چرخش تند و قرمز)
   - اتصال Trigger: ورود از Idle به Overdrive_Boost
5. **Export → Runtime File (.riv)** بزن.
6. فایل خروجی را با نام `vortex_reactor.riv` در مسیر بالا کپی کن.

> اپ به‌صورت خودکار فایل را می‌خواند؛ اگر State Machine/Trigger با نام‌های بالا باشد،
> دکمه BOOST همان‌جا فعال می‌شود. کد به‌صورت بازتابی (Reflection) لود می‌کند،
> پس تغییر نسخه کتابخانه هرگز بیلد را نمی‌شکند.

## گزینه ۲: LottieFiles (ساده‌ترین)
1. برو به `https://lottiefiles.com` → جستجو: `pulse ring` یا `energy loop` یا `radar`.
2. دانلود **Lottie JSON** (رایگان).
3. با نام `vortex_pulse.json` در مسیر بالا بگذار.
4. حالت شبح (👻) سرعت انیمیشن را ۲ برابر می‌کند (تا ۱۲۰FPS روی صفحه‌های سازگار).

اگر هیچ فایلی نگذاری، اپ با «قلب نبض‌دار نئونی» داخلی اجرا می‌شود (بدون کرش).

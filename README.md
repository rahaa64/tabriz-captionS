# کپشن تبریز — Tabriz Captions (Android)

اپ رایگان اندروید: ویدیو می‌دهی، اپ روی خود گوشی گفتار را تشخیص می‌دهد و کپشن **ترکی آذری تبریز/ارومیه با خط فارسی** با انیمیشن (شیشه‌ای، پاپ، کارائوکه، بلوک رنگی، تایپی) روی ویدیو می‌سازد. سرور ندارد؛ هزینه نگهداری صفر.

## نصب روی گوشی
1. صفحه Releases → **Latest APK** → `TabrizCaptions.apk` را دانلود و نصب کن (اجازه «نصب از منابع ناشناس» را بده).
2. اولین بار مدل گفتار را دانلود کن (دقیق ≈۱ گیگ یا سبک ≈۳۷۵ مگ؛ با وای‌فای).
3. ویدیو انتخاب کن → «ساخت کپشن» → متن‌ها را در صورت نیاز اصلاح کن → سبک و اندازه → «ساخت ویدیو». خروجی در گالری: `Movies/TabrizCaptions`.

## ساختار
| فایل | کار |
|---|---|
| `TabrizConverter.kt` | تبدیل لاتین باکویی ویسپر ← تبریزی خط فارسی (قواعد در `assets/tabriz_rules.json`) |
| `Transcriber.kt` | تشخیص گفتار آفلاین (sherpa-onnx + Whisper int8، زبان az) و تقسیم به خطوط کوتاه |
| `AudioDecoder.kt` | صدای ویدیو ← ۱۶ کیلوهرتز مونو |
| `CaptionRenderer.kt` | ۵ سبک انیمیشن متن راست‌به‌چپ با فونت وزیرمتن |
| `Exporter.kt` | Media3 Transformer + Overlay روی GPU، ذخیره در گالری |
| `ModelManager.kt` | دانلود قابل‌ادامه مدل از Release همین مخزن |
| `tools/tabriz.py` | نسخه مرجع پایتون؛ `vectors.json` تست‌های واحد کاتلین را می‌سازد |

## ساخت (خودکار، رایگان)
- هر push به `main` → GitHub Actions تست‌ها را اجرا و APK را در Release «apk-latest» می‌گذارد.
- یک بار: Actions → **Mirror speech models** → Run workflow (مدل‌ها را در Release «models-v1» می‌گذارد).
- امضای ثابت برای آپدیت روی همان نصب (اختیاری): secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## مجوزها
کد: MIT. فونت Vazirmatn: SIL OFL 1.1. sherpa-onnx: Apache-2.0. مدل‌های Whisper: MIT.

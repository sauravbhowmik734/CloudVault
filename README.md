# Cloud Vault (Android) - server.py ab app ke andar chalta hai

Install -> icon tap -> app khule. Termux / `python server.py` ki zaroorat nahi.

## APK kaise banayein (phone se bhi ho jata hai)
1. GitHub par new repo banao, is folder ki saari files upload karo (`.github` folder samet).
2. Repo -> Actions -> "Build APK" -> Run workflow (ya push par apne aap chalta hai).
3. ~10-15 min baad run ke Artifacts me `CloudVault-debug-apk` download karo, zip kholo, `app-debug.apk` install karo.

Laptop par: `gradle assembleDebug` (JDK 17, Android SDK, Python 3.11 chahiye).
Release APK: env `VAULT_KEYSTORE, VAULT_KS_PASS, VAULT_KEY_ALIAS, VAULT_KEY_PASS` set karke `gradle assembleRelease`.

## server.py me kya badla (sirf 3 jagah)
1. `ensure()`: `VAULT_ANDROID=1` par runtime pip install band.
2. `STORAGE`: `VAULT_DATA_DIR` se app ke private folder me.
3. `__main__` block -> `run_main(host, port)` function (android_entry.py ise thread me chalata hai).

## Limits
- cryptg, ffmpeg, tesseract, HEIC, poppler Android build me nahi hain -> transfer thoda slow, video/HEIC/PDF thumbnails aur OCR limited.
- Server sirf 127.0.0.1 par sunta hai (LAN/FTP sharing is app me kaam nahi karegi).
- Notification me "Stop" dabane par poora process band hota hai.

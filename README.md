# InstaDownload

📱 **InstaDownload** is an Android app for downloading Instagram videos directly to your phone.  
Originally built with a server backend, it’s now fully self-contained using [Chaquopy](https://chaquo.com/chaquopy/) to run [Instaloader](https://instaloader.github.io/) (a Python library) inside the APK.  

---

## ✨ Features
- 🔗 Paste an Instagram post URL and download the video directly.
- 🚀 Everything runs locally on your device.
- 🐍 Integrated Python (via Chaquopy) with Instaloader 4.13.2.
- 📂 Videos are saved to your device storage.

---

## 📸 Screenshots
Will be added later

---

## 🏗️ Build Instructions

### Prerequisites
- Android Studio (Giraffe or newer recommended).
- Gradle 8.x (comes with Android Studio).
- JDK 17.
- Python 3.11 installed locally (for Chaquopy build).
  - Example path: `C:/Miniconda3/envs/py311/python.exe`
- Internet connection (first build downloads Chaquopy/Instaloader dependencies).

### Clone
```bash
git clone https://github.com/your-username/InstaDownload.git
cd InstaDownload
````

### Configure Chaquopy

In `app/build.gradle.kts`, check this section:

```kotlin
chaquopy {
    defaultConfig {
        version = "3.11"
        buildPython("C:/Miniconda3/envs/py311/python.exe")
        pip {
            install("instaloader==4.14.2")
        }
    }
}
```

### Build APK

```bash
./gradlew assembleDebug
```

APK output:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install on device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🚀 Usage

1. Copy an Instagram post URL (must be a public post or a private one you have access to).
2. Open **InstaDownload** and paste the link.
3. Tap **Download**.
4. Video is saved in your Downloads folder.

---

## ⚠️ Disclaimer

This project is for **educational purposes**.
Respect Instagram’s [Terms of Service](https://help.instagram.com/581066165581870) and only download content you have rights to.

---

## 📜 License

MIT License – see [LICENSE](LICENSE) for details.

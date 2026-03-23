# InstaDownload

**InstaDownload** is an Android app for downloading Instagram videos directly to your phone with no ads.  

---

## Features
-  Paste an Instagram post URL and download the video directly.
-  Everything runs locally on your device.
-  Videos are saved to your device storage.

---

## Screenshots

| Light theme | Dark theme |
|-------------|------------|
| <img src="/img/lighttheme.png" width="250"/> | <img src="/img/darktheme.png" width="250"/> |

---

## Build Instructions

### Prerequisites
- Android Studio (Giraffe or newer recommended).
- Gradle 8.x (comes with Android Studio).
- JDK 17.

### Clone
```bash
git clone https://github.com/InterJava-Studio/InstaDownload.git
cd InstaDownload
````

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

## Usage

1. Copy an Instagram post URL (must be a public post or a private one you have access to).
2. Open **InstaDownload** and paste the link.
3. Tap **Download**.
4. Video is saved in your Downloads folder.

---

## Disclaimer

This project is for **educational purposes**.
Respect Instagram’s [Terms of Service](https://help.instagram.com/581066165581870) and only download content you have rights to.

---

## License

MIT License – see [LICENSE](LICENSE) for details.

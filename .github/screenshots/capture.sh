#!/usr/bin/env bash
# Seeds the downloads library with sample files and captures screenshots of it.
set -euo pipefail

PACKAGE=com.vakarux.instadownload.debug
ACTIVITY=com.vakarux.instadownload.MainActivity
DEVICE_DIR=/storage/emulated/0/Download/InstaDownload
OUT=screenshots-out
WORK=$(mktemp -d)
mkdir -p "$OUT"

adb install -r -g InstaDownload/app/build/outputs/apk/debug/app-debug.apk
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true

# Sample media: synthetic test patterns, not real Instagram content.
ffmpeg -loglevel error -f lavfi -i "mandelbrot=size=720x1280:rate=30" -t 4 -pix_fmt yuv420p "$WORK/sample_reel_1.mp4"
ffmpeg -loglevel error -f lavfi -i "testsrc2=size=1080x1920:rate=30" -t 12 -pix_fmt yuv420p "$WORK/sample_reel_2.mp4"
ffmpeg -loglevel error -f lavfi -i "cellauto=size=720x1280:rate=30:rule=110" -t 32 -pix_fmt yuv420p "$WORK/sample_reel_3.mp4"
ffmpeg -loglevel error -f lavfi -i "smptehdbars=size=1080x1350" -frames:v 1 "$WORK/sample_photo_1.jpg"
adb shell mkdir -p "$DEVICE_DIR"
for f in "$WORK"/*; do adb push "$f" "$DEVICE_DIR/" > /dev/null; done

now_ms=$(( $(date +%s) * 1000 ))
python3 - "$now_ms" "$DEVICE_DIR" > "$WORK/download_history.json" <<'PY'
import json, sys
now, folder = int(sys.argv[1]), sys.argv[2]
hour = 3600 * 1000
def record(name, mime, ago_h, user, caption, song, posted_days, w, h, dur, shortcode):
    return {
        "uri": f"file://{folder}/{name}", "fileName": name, "mimeType": mime,
        "downloadedAtMs": now - ago_h * hour, "postUrl": f"https://www.instagram.com/p/{shortcode}/",
        "username": user, "caption": caption, "song": song,
        "takenAtSec": (now // 1000) - posted_days * 86400 if posted_days else 0,
        "width": w, "height": h, "durationSec": dur,
    }
records = [
    record("sample_reel_1.mp4", "video/mp4", 0, "sample.creator",
           "Sunset timelapse from the ridge 🌄 Took three tries to get the light right. "
           "Full route in the highlights! #hiking #timelapse #mountains", "Sample Artist - Golden Hour",
           2, 720, 1280, 4.0, "SAMPLE00001"),
    record("sample_reel_2.mp4", "video/mp4", 5, "kitchen.demo",
           "15-minute weeknight pasta 🍝 Recipe: garlic, chili flakes, lemon zest, parmesan.",
           None, 9, 1080, 1920, 12.0, "SAMPLE00002"),
    record("sample_photo_1.jpg", "image/jpeg", 26, "design.notes",
           None, None, 30, 1080, 1350, 0.0, "SAMPLE00003"),
    record("sample_reel_3.mp4", "video/mp4", 50, None, None, None, 0, 720, 1280, 32.0, "SAMPLE00004"),
]
records[3]["postUrl"] = None
print(json.dumps(records))
PY
adb push "$WORK/download_history.json" /data/local/tmp/download_history.json > /dev/null
adb shell chmod 644 /data/local/tmp/download_history.json
adb shell am start -W -n "$PACKAGE/$ACTIVITY" > /dev/null
sleep 2
adb shell am force-stop "$PACKAGE"
adb shell run-as "$PACKAGE" sh -c "'mkdir -p files && cp /data/local/tmp/download_history.json files/download_history.json'"

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }

# Center of the first node whose text or content-desc matches $1.
center_of() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb shell cat /sdcard/ui.xml | python3 -c '
import re, sys
xml, needle = sys.stdin.read(), sys.argv[1]
for node in re.findall(r"<node [^>]*>", xml):
    if f"text=\"{needle}\"" in node or f"content-desc=\"{needle}\"" in node:
        x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2); break
else:
    sys.exit("not found: " + needle)
' "$1"
}

capture_all() {
  local suffix=$1
  adb shell am force-stop "$PACKAGE"
  adb shell am start -W -n "$PACKAGE/$ACTIVITY" > /dev/null
  shot "01-home$suffix" 4
  read -r x y < <(center_of "Downloads")
  adb shell input tap "$x" "$y"
  shot "02-library$suffix" 5
  read -r x y < <(center_of "@sample.creator")
  adb shell input swipe "$x" "$y" "$x" "$y" 1200
  sleep 1
  read -r x y < <(center_of "@kitchen.demo")
  adb shell input tap "$x" "$y"
  shot "03-selection$suffix" 2
  read -r x y < <(center_of "Delete")
  adb shell input tap "$x" "$y"
  shot "04-delete-dialog$suffix" 2
  read -r x y < <(center_of "Cancel")
  adb shell input tap "$x" "$y"
  read -r x y < <(center_of "Share")
  adb shell input tap "$x" "$y"
  shot "05-share-sheet$suffix" 4
  adb shell input keyevent KEYCODE_BACK
}

capture_all ""
adb shell "cmd uimode night yes" || true
capture_all "-dark"
adb shell "cmd uimode night no" || true
ls -l "$OUT"

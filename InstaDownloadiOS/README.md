# InstaDownload for iOS

This is a SwiftUI remake of the InstaDownload Android app. It uses Apple's Liquid Glass design language. You distribute it through AltStore.

The layout, text, colors, and behavior match the Android build. The app uses the same Instagram gradient, the same hero tile, the same card, and the same pink Download button. The material is new. The flat Material 3 surfaces are now Liquid Glass. Platform-specific code (photo library, Files, haptics) is native to iOS.

<img src="../img/lighttheme.png" width="240"> <img src="../img/darktheme.png" width="240">

These are the Android screenshots. The iOS layout matches them.

## Requirements

| Task | Requirement |
|---|---|
| Build | A Mac with Xcode 26. The iOS 26 SDK contains the Liquid Glass APIs. |
| Run | iOS 17.0 or later. Liquid Glass renders on iOS 26 and later. Older iOS versions use a system-material fallback. |
| Distribute | AltStore. AltStore installs the app and signs it with your own Apple ID. |

## File layout

```
InstaDownload.xcodeproj          Xcode 16+ synchronized-folder project.
                                 New .swift files are picked up with no extra step.
InstaDownload/
  InstaDownloadApp.swift         The app entry point. Sets up theme and settings.
  Core/
    InstagramDownloader.swift    A Swift port of the Kotlin extractor.
                                 Tries the embed page first, then a crawler
                                 fallback, then a public story probe.
    MediaSaver.swift             Saves media to the photo library or to Files.
    AppSettings.swift            Stores user preferences with UserDefaults.
    Haptics.swift                Plays haptic feedback.
  UI/
    LiquidGlass.swift            The design layer. Wraps the Liquid Glass APIs
                                 and provides an iOS 17/18 fallback.
    Theme.swift                  Holds brand colors, the theme setting, and the
                                 gradient background.
    DownloaderView.swift         The main screen.
    SettingsView.swift           The settings sheet.
  Assets.xcassets                The app icon, the accent color, the GitHub mark.
altstore/source.json             The AltStore source file.
Scripts/build-ipa.sh             Builds and packages the unsigned .ipa file.
```

## How the app uses Liquid Glass

Apple's Human Interface Guidelines set strict rules for Liquid Glass. These rules shaped the app layout. The file `UI/LiquidGlass.swift` exists to enforce these rules for the rest of the app.

**Glass belongs in the functional layer, not in the content layer.** The Instagram gradient is content. It has no glass. Every element that floats above the gradient has glass. This includes the hero tile, the input card, the preview card, the error card, the toolbar button, and the GitHub credit link.

**The app uses only the regular glass variant.** Apple's guidelines reserve the clear variant for controls over photos and video. The guidelines call for the regular variant on any surface with meaningful text. Every glass surface in this app has meaningful text.

**The app never stacks glass on glass.** This rule shaped the input card. The URL field and the pink Download button sit inside a glass card. For this reason, they stay solid, not glass. This also matches the original Android screenshots. The "Update to latest release" button inside the error card uses a bordered style for the same reason. The GitHub credit button is the one real glass button in the app. It floats directly over the gradient, with no card behind it.

**The app groups all glass surfaces in one container.** Every glass surface shares one `GlassEffectContainer` and one `@Namespace`. This lets the system blend and morph the surfaces together. For example, the preview card grows out of the input card through `glassEffectID`, instead of a plain fade.

**The system handles accessibility settings.** The `glassEffect` API already adapts to Reduce Transparency and to Increase Contrast. The app code does not add extra logic for these settings. Only the iOS 17/18 fallback path checks `accessibilityReduceTransparency` directly. In that case, it swaps `.ultraThinMaterial` for a solid fill.

Standard system components (the toolbar button, the settings `Form`, the sheet) get Liquid Glass from iOS with no extra code.

## Differences from the Android build

| Android | iOS |
|---|---|
| MediaStore or a Storage Access Framework document tree | The photo library by default, or a Files folder through a security-scoped bookmark |
| The `ACTION_SEND` share target | The `PasteButton` control. It reads the clipboard with no permission prompt. |
| Custom `VibrationEffect` compositions | `UIImpactFeedbackGenerator` and `UINotificationFeedbackGenerator` |
| The `WRITE_EXTERNAL_STORAGE` permission and related permissions | Only the `NSPhotoLibraryAddUsageDescription` key |
| The Material 3 `ElevatedCard` component | Liquid Glass surfaces |

**The app has no Share Extension.** A share-sheet entry (Instagram, then Share, then InstaDownload) needs a second app target with its own bundle ID. Under a free Apple ID, each bundle ID counts against the sideloading limit. This is a real cost for AltStore users. The paste flow covers the same task, because the Instagram share sheet offers a Copy Link option. If you want a share-sheet entry later, add an extension target and pass the URL through an App Group. The current code does not block this.

The story-download code is a direct port of the Android logic, with the same limit. Instagram serves normal stories only to logged-in accounts. For this reason, the public probe usually returns no result. The app shows a warning before you try to download a story, the same as the Android app.

## How to build the app

1. Open `InstaDownload.xcodeproj`.
2. Go to Signing and Capabilities. Select your team. (The project ships with an empty `DEVELOPMENT_TEAM` value on purpose.)
3. Run the app.

To build an artifact for AltStore, run one of these commands:

```sh
./Scripts/build-ipa.sh                  # Builds build/InstaDownload.ipa
./Scripts/build-ipa.sh --update-source  # Also writes size and date into altstore/source.json
```

The `.ipa` file has no signature. This is on purpose. AltStore signs the app when it installs it.

## How to publish to AltStore

1. Run `./Scripts/build-ipa.sh --update-source`.
2. Attach `build/InstaDownload.ipa` to a GitHub release. Tag the release `v<version>-ios`.
3. Check that `downloadURL` in `altstore/source.json` points to that release asset.
4. Commit the change. Push it. The AltStore source file is then live at this URL:

   ```
   https://raw.githubusercontent.com/Orang-Studio/InstaDownload/main/InstaDownloadiOS/altstore/source.json
   ```

5. Tell users to add that URL in AltStore. They do this under Sources, then the plus button.

The file `source.json` follows the current AltSource schema. It uses `appPermissions` with `entitlements` and `privacy` keys. It uses `versions[].buildVersion`. It gives `size` in bytes. The `size` value starts at 0. The build script fills in the real value. AltStore rejects the file if this value does not match the real file size.

Under a free Apple ID, a sideloaded app expires after 7 days. AltStore refreshes the app in the background before this happens. A paid Apple Developer account extends this period to one year.

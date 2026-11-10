# Releasing TapFlow on Google Play

Checklist for publishing. Items marked **(you)** need your accounts or decisions. Everything else is already in the code.

## 1. Upload key (you, once)

Play needs an upload key you keep forever. Create it and keep the `.jks` file and passwords somewhere safe, outside the repo:

```bash
keytool -genkeypair -v -keystore tapflow-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore.properties` in the project root. It is git-ignored:

```properties
storeFile=../keys/tapflow-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Without this file, release builds are signed with the debug key. That works for testing on your phone, but Play rejects those builds.

## 2. Build

```bash
./gradlew bundleRelease
```

The bundle lands at `app/build/outputs/bundle/release/app-release.aab`. It is minified (R8, with debug logs stripped) and contains native code for arm64, armeabi-v7a and x86_64. Play splits it, so each phone downloads only its own copy.

To install a release build on a connected phone for a final check:

```bash
./gradlew installRelease
```

Bump `versionCode` in `app/build.gradle.kts` for every upload.

## 3. Play Console: App content

### Privacy policy (you)
Host `docs/privacy-policy.md` at a public URL (GitHub Pages works) and fill in the support email first.

### Data safety
- Data collected: **None**
- Data shared: **None**
- No account or login, and no internet permission. Scripts and images stay in app-private storage.

### Accessibility API declaration
TapFlow is **not** an accessibility tool (`isAccessibilityTool` is not set). Google lists automation apps as not qualifying. Paste and adjust:

> **Which core feature uses the AccessibilityService API?**
> TapFlow is an auto clicker. The user places targets on screen, records gestures, or captures small images, then presses Play on TapFlow's floating panel. The AccessibilityService performs those taps, long presses and swipes with `dispatchGesture`, and shows the floating panel and markers as accessibility overlays. For image steps only, it takes screenshots with `takeScreenshot` while such a step runs, compares them on the device with the user's captured images to find where to tap, and discards them immediately.
>
> **Does the app collect or share data through the API?**
> No. TapFlow cannot read other apps' window content (`canRetrieveWindowContent` is false), subscribes to no accessibility events, has no internet permission, and never stores or transmits screenshots.
>
> **Does it act autonomously?**
> No. It only performs the fixed sequence of actions the user defined, and only after the user presses Play (or at a start time the user set). It does not plan or decide actions.

### Declaration video (you record it, following this script)
Record on a phone with the release build, about 60–90 seconds, with no cuts between steps 2 and 5:

1. Fresh install, then open TapFlow.
2. Tap **Turn on accessibility service**. The disclosure appears. Show it in full.
3. Tap **No thanks**. Show the "Accessibility access declined" card, then tap **Review explanation** to show the disclosure can be reopened.
4. Tap **Agree and continue**. Android's accessibility settings open. Turn on **TapFlow Auto Clicker** and accept the system dialog.
5. Back in an app such as Settings: add a tap target with **+**, drag it onto an item, press **▶**, and show the tap happening.
6. Optional: tap **🖼**, box an item, scroll so it moves, press **▶**, and show it being found and tapped.

Upload the video unlisted (YouTube, for example) and paste the link in the declaration.

## 4. Store listing (you)

- App icon: export the adaptive icon (`res/drawable/ic_launcher_*.xml`) at 512×512 from Android Studio (**Resource Manager → right-click → Export**), or render it from the APK.
- Feature graphic 1024×500, plus at least 2 phone screenshots. Use a clean test device or emulator so no personal notifications or accounts show up.
- Short description idea: "Auto tap, swipe and record. Find images on screen. No ads, works offline."

## 5. Every release

- [ ] `versionCode` bumped
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew installRelease`, then on the phone: tap, swipe, record, image step, save/load
- [ ] If the app's use of the AccessibilityService changed: bump `Prefs.DISCLOSURE_VERSION`, update the disclosure, `strings.xml`, the privacy policy (in-app and docs) and the Play declaration

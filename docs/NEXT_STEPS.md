# TapFlow: state of the project and next steps

Handoff notes for a fresh session. Read this first, then `docs/research.md` (market and policy research) and `docs/release.md` (publishing checklist).

## What TapFlow is
An Android auto clicker, similar to AG Auto Clicker, built on an `AccessibilityService` with no root needed. Kotlin; Compose for the app screens and plain Views for the overlays. Package `dev.tapflow.autoclicker`, minSdk 26, target/compileSdk 37.

## Done so far
| Phase | Contents | Commit / branch |
|---|---|---|
| 1 MVP | Floating panel, draggable numbered markers, tap / long press / swipe, in order or all at once, repeat count, stop timer, random delay and offset, save/load (JSON), Play disclosure | `a1fe729` on `feature/record-and-schedule` |
| 2 | Record & replay (curved swipes kept), scheduled start time, image steps (OpenCV, several images per step) | same commit |
| 3 Release-ready | R8 + resource shrinking, App Bundle with per-ABI native code, signing from `keystore.properties`, crash-safe storage, versioned re-consent, privacy policy, backup rules, app icon, `docs/release.md` | `6357686` on `feature/release-ready` |
| 4 Reliability + customization | Pause on a call, screen off or user touch; keep screen on; switched-off card; phone-maker setup guides; panel kept on screen and aware of rotation; panel modes (Single / Multi / Sync / Record / Find image / Everything); pause/resume/start over; compact panel while running; panel and marker size/opacity/orientation; script details; Controls/Steps tabs with rename and remove; hide markers; traffic-light marker rings; draggable capture bar; 100 ms default delay | `750732a` on `feature/reliability` |

All three branches stack on each other (`feature/reliability` contains everything). There is **no `main` branch and no remote yet**.

## Code map (`app/src/main/kotlin/dev/tapflow/autoclicker/`)
- `service/ClickerService.kt`: the accessibility service. Owns the runner, overlay, stores, screen-off receiver and touch watcher.
- `engine/ScriptRunner.kt`: plays scripts, with pause/resume progress, call check, image waits and current-step tracking.
- `engine/Gestures.kt`: builds strokes and runs `dispatchAndAwait`; sets `ClickerState.gestureActive`.
- `engine/ScreenCapture.kt`, `engine/TemplateMatcher.kt`: screenshots through the accessibility API (Android 11+) and OpenCV matching.
- `overlay/OverlayManager.kt`: panel (button sets per mode), markers, all forms including the tabbed settings.
- `overlay/GestureRecorder.kt`, `RegionSelectOverlay.kt`, `TouchWatcher.kt`, `MarkerView.kt`.
- `model/Script.kt` (data and modes), `model/Describe.kt` (plain-language step text).
- `data/ScriptStore.kt` (atomic JSON, saves every field), `data/TemplateStore.kt` (PNG images, excluded from backups).
- `ClickerState.kt`: shared state flows, plus `Prefs` (consent version, appearance, run options).
- `ui/HomeScreen.kt`, `ui/HomeParts.kt` (mode picker, appearance, details), `ui/OemGuide.kt`, `ui/PrivacyPolicy.kt`.
- Tests: `app/src/test/...`: `ScheduleTest`, `ScriptStoreTest`, `DescribeTest` (15 tests).

## Lessons learned (don't relearn these)
- **Own gestures vs real touches:** gestures injected by the accessibility service arrive with `TOOL_TYPE_UNKNOWN`; real fingers don't. Don't use timing windows to tell them apart.
- **Markers must be untouchable before the first gesture:** the run waits at least 200 ms before starting so the window flags have applied.
- **Edge drags:** the drag handles call `systemGestureExclusionRects`, otherwise Android's back gesture takes the drag over.
- **`ACTION_OUTSIDE` coordinates** are zeroed for other apps' windows and real for our own windows (that's how the panel check works).
- **Panel visibility:** the panel only opens when the user asks, from the app; it doesn't open on service connect.
- **Saving scripts:** use `encodeDefaults = true`, so changing a default never changes saved scripts.
- **Native library size:** OpenCV is large per CPU type. Debug builds are arm64-only; add `-Pemulator` for x86_64.
- **Force-stopping the app disables the accessibility service.** Never `am force-stop dev.tapflow.autoclicker` while testing.
- **Testing on the phone (Samsung S24 Ultra, adb over Wi-Fi/USB):**
  - Use `android layout --flat` to find overlay buttons by content description.
  - Use Settings (`am start -a android.settings.SETTINGS`) as a harmless app to tap on.
  - Push test scripts with `run-as dev.tapflow.autoclicker` into `files/scripts/`, and delete them afterwards.
  - The user also uses the phone, so expect concurrent input.

## Next steps

### A. Publishing prep (recommended first: the 14-day closed test runs in the background)
Can be done in code:
- [ ] Create `main` from `feature/reliability` (all three branches are linear, so a fast-forward works) and add a remote once one exists.
- [ ] Strip leftover debug-only details. `Log.d` is already removed by R8; check for anything else.
- [ ] Localization, for example Arabic: move UI strings into `strings.xml` and add `values-ar`; check RTL layout of the panel and forms.
- [ ] Prepare a release build for the closed testing track (`./gradlew bundleRelease`) and bump `versionCode`.
- [ ] Sliders in Customize: confirm that dragging with a real finger works well. adb drags were unreliable; consider tap-to-set.
- [ ] Confirm on the phone that a marker ring is green while a long press is held (built, not caught on screenshot).
- [ ] Untested on the phone: pausing when a call starts and when the screen turns off.

Needs the user:
- [ ] Personal Google Play developer account and identity verification.
- [ ] Upload key plus `keystore.properties` (see `docs/release.md`).
- [ ] Host `docs/privacy-policy.md` publicly and add a support email (TODO in that file).
- [ ] Record the accessibility declaration video (script in `docs/release.md`).
- [ ] 12 closed testers opted in for 14 days in a row (rule for personal accounts created after 13 Nov 2023), then apply for production.
- [ ] Store screenshots on a clean device, with no personal notifications or accounts showing.

### B. Phase 5: smarter automation
- [ ] **Text triggers:** wait for text on screen, then tap it. Use on-device OCR (ML Kit text recognition, bundled model) on the same accessibility screenshots. New `ActionType.TEXT`, editor fields: text, match mode, timeout, if not found.
- [ ] **Conditions and flow:** "if image/text present → do A else B", repeat blocks ("repeat steps 3–5 ×N"), "go to step N". Needs a model change, probably a `next`/`branch` field per step, and editor UI in the Steps tab.
- [ ] **Search area:** limit image/text search to a box (reuse `RegionSelectOverlay`), which is faster and gives fewer false matches.
- [ ] **Two-finger recording:** record up to 2+ pointers as simultaneous strokes (`GestureRecorder` currently records only the first finger).
- [ ] **Share scripts:** export as one file (JSON plus images, zipped) and import it; Android share sheet and file picker.
- [ ] Policy check: keep everything deterministic and user-defined. AI "agent" behaviour is banned for apps that aren't accessibility tools. If text triggers change what the service does, bump `Prefs.DISCLOSURE_VERSION` and update the disclosure, `strings.xml`, the privacy policy and the Play declaration.

### C. Phase 6: monetization
- [ ] No ads, plus a one-time **Pro** in-app purchase (Google Play Billing).
- [ ] Decide what goes in Pro, e.g. image/text triggers, conditions, unlimited saved scripts, scheduling.
- [ ] Note: charging makes the account a merchant; Google may then show the developer's address on the listing. Verify before launching paid features.

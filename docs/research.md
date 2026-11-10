# Auto Clicker: Research Brief (2026-11-10)

## 1. AG Auto Clicker (the reference app)
- Package `com.tapassistant.autoclicker`, developer Bright Prospect. Play title is now "Auto Clicker-Auto Tap & Swipe".
- Play shows 10M+ downloads; AppGoblin estimates ~21M. Rating ~4.2 from ~69K reviews. Last updated Oct 2026.
- Money: heavy ads (11 ad SDKs, ads at launch and during use) plus a recurring subscription to remove them. In-app purchases range $0.99–$69.99.
- Features:
  - Multi-point taps and swipes, run in order or all at once ("sync").
  - Record and replay of taps, swipes and long presses.
  - Scheduled start and custom click intervals.
  - Floating start/stop panel with adjustable transparency.
  - Selectable target-marker styles.
  - Save and load scripts. Users say saved scripts are hard to find.
  - An old "Game Mode" claimed anti-detection; that claim was removed from the listing later.
  - No image or text recognition.
- Permissions:
  - Accessibility Service: off by default, with a formal declaration in the listing.
  - Display over other apps.
  - Root not needed.
- Not confirmed: exact panel layout, whether markers are numbered, random offsets, stop conditions.

## 2. Competitors
| App | Installs | Rating | Notes |
|---|---|---|---|
| Auto Clicker (True Developers Studio) | ~207M | 4.43 | Market leader: ads + IAP, est. >$1M/mo, script import/export |
| AG Auto Clicker | ~21M | 4.25 | Sync multi-touch, record/replay |
| Click Assistant (Y.C. Studio) | ~30M | — | Curved swipes, pinch |
| Klick'r / Smart AutoClicker | ~0.5M | ~4.1 | Open source (GPL-3): image detection, OCR, conditions; weak UX |
| Macrorify, Mobizen | — | — | Image/text recognition, randomized taps |

Basic tap/swipe/record clicking is a commodity, and the big apps win on store search ranking (ASO). Image, OCR and condition features exist only in small apps.

## 3. User pain points
1. **Ads**: intrusive, unskippable, audio that keeps playing after leaving the app.
2. **Service gets killed**: battery optimization switches the accessibility service off, especially on Xiaomi (MIUI/HyperOS).
3. **Wanted**: random timing and positions, image/text triggers, scheduling, ad-free with a one-time purchase.

## 4. Technical approach (no root)
- **Gestures**: `AccessibilityService.dispatchGesture()` (API 24+).
  - Limits: max 20 strokes and under 60 s per gesture.
  - `willContinue` / `continueStroke` (API 26+) chain long holds and drags.
  - Schedule the next gesture from `onCompleted`. Realistic speed is about one tap per frame (10–16 ms).
- **Service config**: `canPerformGestures="true"`. The `<service>` needs `BIND_ACCESSIBILITY_SERVICE`, the intent filter and the meta-data entry.
- **Overlays**: use `TYPE_ACCESSIBILITY_OVERLAY`, created from the service context.
  - It needs no `SYSTEM_ALERT_WINDOW` grant and avoids Android 12's block on touches passing through overlays.
  - Draggable markers are small windows moved with `updateViewLayout`.
  - Set `FLAG_NOT_TOUCHABLE` on the markers (or hide them) while a tap is dispatched, so the tap reaches the app underneath.
- **Staying alive**: the system binds accessibility services, so a plain clicker needs no foreground service.
  - A foreground service is needed only for MediaProjection (image detection); Android 14+ requires the `mediaProjection` FGS type.
  - Ask for a battery-optimization exemption and add OEM-specific setup guides.
- **Recording**: a full-screen overlay captures MotionEvents per pointer and converts them to Path strokes.
  - The app underneath gets no input while recording.
  - The 20-stroke and 60 s limits apply.
- **User touches**: a real touch cancels a held injected pointer. Detect it with a 1-px `ACTION_OUTSIDE` watcher window, as Klick'r does.
- **Platform restrictions**:
  - Android 13+ "Restricted settings": sideloaded apps must be allowed manually before Accessibility can be turned on.
  - Android 16/17 Advanced Protection Mode blocks accessibility for non-tool apps, and users cannot override it while the mode is on.
- **Reference code**: Nain57/Smart-AutoClicker (Kotlin, Hilt, Room, OpenCV, multi-module). It is GPL-3, so read it but don't copy it unless your app is GPL too.

## 5. Google Play policy (critical)
- You cannot set `isAccessibilityTool="true"`. Google explicitly lists automation apps as not being accessibility tools.
- Required:
  - The Accessibility declaration in Play Console.
  - A separate in-app prominent disclosure with affirmative consent.
  - A video showing the disclosure, the accept and decline flows, and a core feature using the API.
- Fixed, user-defined scripts are allowed. Autonomous "AI agent" actions are banned for non-tool apps.
- What gets approved: local-only processing, no data collection, accessibility off by default, clear consent.

## 6. Anti-cheat
Games can detect running accessibility services and regular timing or tap patterns. Pitch randomization as "human-like pacing", not as evasion.

## 7. Opportunities for a new app
1. Ad-light or ad-free, with a one-time Pro purchase.
2. Polished image/OCR/condition triggers (Klick'r has the tech but a weak UX).
3. Reliability: OEM-specific setup guides, a watchdog that notices when the service is killed, one-tap re-enable.
4. Trust: offline, no data collected. This also eases Play review.
5. Randomized timing and positions, scheduling, script sharing and backup, easy-to-find saved scripts.

## Suggested MVP
Kotlin with Jetpack Compose for in-app screens and Views for the overlays.
1. Floating panel: add a target, play/stop, settings.
2. Numbered draggable targets.
3. Per-target tap, long press or swipe, with interval, duration and random offset.
4. Run modes: in order or all at once (sync).
5. Stop after N loops or T time.
6. Save and load scripts with Room.
7. Prominent-disclosure onboarding and OEM battery guides.

Phase 2: record and replay, scheduling, image triggers (OpenCV + MediaProjection).

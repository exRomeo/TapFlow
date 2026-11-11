# TapFlow

Android auto clicker built on an `AccessibilityService`, with no root needed. Kotlin, Compose for app screens, plain Views for overlays. Package `dev.tapflow.autoclicker`.

## Start here
**Read `docs/NEXT_STEPS.md` before doing anything.** It records what is built, the code map, lessons learned and the open checklists (publishing prep, phase 5, phase 6). Also see:
- `docs/release.md`: publishing checklist.
- `docs/research.md`: market and policy research.

## Keep the docs true to the current branch
`docs/NEXT_STEPS.md` describes the code on the branch it is committed on. Whenever work changes that picture, update it in the same commit as the code:
- tick checklist items you finish, and add new ones you discover;
- update the "Done so far" table, including the commit and branch, when a phase or feature lands;
- add to "Lessons learned" when something cost time to figure out;
- if you switch, create or merge branches, check that the file still matches that branch. Never describe work that only exists on another branch as done.

Keep this CLAUDE.md accurate the same way. If a command, path or convention below changes, fix it here too.

## Branches
- **`development` always holds the latest approved work.** Never commit unreviewed work to it directly.
- Start every piece of work on a new `feature/<name>` branch cut from the current `development`.
- When the user approves the work (and it is committed), merge it into `development`. Use a fast-forward when possible, otherwise `git merge --no-ff`. Then start the next feature from the updated `development`.
- Older `feature/*` branches are history; don't build on them.

## Commands
```bash
./gradlew assembleDebug           # debug APK (arm64 only; add -Pemulator for x86_64)
./gradlew testDebugUnitTest       # JVM unit tests
./gradlew bundleRelease           # release AAB (R8; signed from keystore.properties, else debug key)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Rules
- Commit only when asked. There is no `main` and no remote yet.
- Never commit `keystore.properties`, `*.jks` or `local.properties` (already git-ignored).
- Never `am force-stop dev.tapflow.autoclicker` while testing on a device: it switches the accessibility service off, and only the user can turn it back on.
- If the app starts using the AccessibilityService for something new, bump `Prefs.DISCLOSURE_VERSION` and update:
  - the in-app disclosure;
  - `strings.xml`;
  - the privacy policy, both in the app and in `docs/privacy-policy.md`;
  - the declaration text in `docs/release.md`.
- Keep automation deterministic and user-defined. Autonomous "agent" behaviour breaks Google Play's accessibility policy.
- Scripts are saved with `encodeDefaults = true`; changing a model default must not change saved scripts.

# DECISIONS

Architecture, stack, plan, and every call made on an OPEN item. Newest
decisions are appended at the bottom of each section with the layer that
made them.

## 1. Stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin 2.3 | Given. |
| Build | Gradle 9.7 wrapper, AGP 9.3 (built-in Kotlin), JDK 17+ | Current stable line. AGP 9 compiles Kotlin itself, so there is no `kotlin-android` plugin. |
| UI | Android framework Views and a few custom `Canvas` views. **No Jetpack Compose, no AndroidX.** | See 1.1. |
| Persistence | Framework SQLite (`SQLiteOpenHelper`) for logs and plan, small atomic files for safety-critical state | "Room or similar". Safety state must be readable from a second process (watchdog), and Room gives nothing there. |
| Background work | `AlarmManager` exact alarms for takeovers; `JobScheduler` for plan generation (Layer 3) | WorkManager is a wrapper over JobScheduler on API 23+. Same guarantees (persisted across reboot, network constraint) without the dependency. |
| Foreground app detection | Accessibility service (primary), `UsageStatsManager` (fallback during sieges only, Layer 2) | Accessibility events are instant. UsageStats polling lags 1-5 s and costs battery. |
| Driving detection | Google Play Services Activity Recognition (transition API), plus Android car mode | Android has no framework activity-recognition API. This is the single non-framework dependency. |
| JSON / AI | `kotlinx.serialization` (Maven Central) and a thin `HttpURLConnection` client to the Anthropic Messages API, behind an interface | Keeps the AI layer swappable and dependency-light. |
| Min / target SDK | minSdk 29 (Android 10), target/compile 36 | 29 is the oldest version with the emergency-dialer intent and the `ACTIVITY_RECOGNITION` permission. |

### 1.1 Why no Compose / AndroidX

1. **Overlay and system windows.** Takeovers, siege bounce screens and (Layer 5) the red night filter live partly outside a normal Activity. Plain Views work in any window. Compose needs lifecycle and saved-state owners wired by hand there.
2. **Pixel art.** The whole visual language is hard-edged pixels: nearest-neighbour scaling, dithering, chunky frames. That is a few lines of `Canvas` with `isFilterBitmap = false`. Compose adds nothing here.
3. **Verifiability.** The build container used to write this code cannot reach Google's Maven repository. Framework-only code can still be type-checked against `android.jar` there, so nothing ships uncompiled. CI (GitHub Actions) does the real AGP build.
4. **Fewer moving parts** in an app whose failure mode is "user trapped on a red screen".

Cost: setup screens take more code. The setup flow is one linear, terminal-style conversation, so this is acceptable.

## 2. Module layout

```
core/   pure Kotlin/JVM. No Android imports. Everything that must be
        reliable lives here and is unit-tested on the JVM:
        safety rules (lease, limits, escape sequence, safe-mode policy),
        window parsing and random day scheduling, deferral rules,
        task catalog, escalation (L2), plan schema + validators (L3),
        compact state (L3), stage logic (L4), pixel-sprite generator,
        metronome tempo curves and PCM synthesis.
app/    Android shell: services, receivers, activities, audio/haptics
        output, storage, permissions walkthrough. Thin over core.
```

App packages:

- `safety`: lease store, crash ledger, safe mode, escape hatch, call guard, driving detector, watchdog (separate process).
- `spell`: foreground service, scheduler and alarms, takeover activity and view, cue engine.
- `guard`: accessibility service (foreground-app events, global volume-key escape, bounce).
- `ui`: palette, pixel font, terminal-style screens.
- `data`: config and storage.

## 3. Takeover surface (OPEN, decided)

A takeover is a full-screen Activity (`TakeoverActivity`: own task, excluded from recents, `showWhenLocked`, `turnScreenOn`). It is launched two ways:

1. **Screen off or locked:** a full-screen-intent notification (the alarm-clock path). The system turns the screen on and shows the takeover over the keyguard.
2. **Screen on, phone in use** (the important "stop scrolling" case): a full-screen intent would only show a heads-up banner here. So the service starts the Activity directly. That needs a background-activity-start exemption. The reliable one on Android 10-16 is *"the app has an AccessibilityService bound by the system"*. `SYSTEM_ALERT_WINDOW` is a weaker fallback: from Android 15 it only exempts apps that already show a visible overlay. The full-screen-intent notification is always posted as well, so a blocked launch still shows a heads-up.

While a takeover is active, the accessibility service brings it back if the user switches to another app (Home, recents, another app). Exceptions: a call, the dialer or emergency dialer, Settings, and system UI. The lease and the hard limits (section 6) apply throughout.

Required permissions are listed in the README and checked by the in-app walkthrough.

## 4. Audio and haptics (Section 5 of brief)

- One `AudioTrack` in streaming mode with `USAGE_ALARM`. Alarm usage plays in vibrate/silent ringer mode and passes default Do Not Disturb settings. All sound is **synthesised PCM**: buzz, metronome click, release tone. There are no audio files, and the click positions are sample-accurate, so the metronome never drifts or jitters.
- Cue = signature vibration waveform, then buzz, then the metronome starts. Release = metronome stops, ~600 ms of silence, then one soft low tone.
- The vibration, buzz and click are **never** used anywhere else. Every notification channel in the app is silent with vibration disabled, so the system never produces a competing cue.
- If the alarm stream volume is below a floor, it is raised to that floor for the takeover and restored afterwards. Volume is never touched during a call.
- Audio focus is requested transiently. Losing it to a call yields the takeover.

## 5. Visuals

- Palette: black `#0A0A0A`, off-white `#E8E4DA`, red `#B3001B`. Red means a spell is active, and only then.
- Font: **Departure Mono** (SIL OFL, bundled with its licence). It is a monospaced pixel font, drawn at integer multiples of its 11 px grid with antialiasing off.
- The eye is **generated procedurally** in `core` as a low-resolution pixel grid with ordered (Bayer) dithering. It is scaled up nearest-neighbour and animated in chunky frames (4-6 fps). No vector art, no smoothing.
- Photosensitivity: rotation period ≥ 5 s per revolution. The red flood is a dithered dissolve over ~1 s. The cursor blinks at 1 Hz. Nothing in the app changes luminance faster than 2 Hz.
- "Vary the visuals, protect the core cue": each takeover randomises rotation speed (±20%), the eyelid-opening animation (a horizontal pixel line that opens at a varied speed) and the opening text line. The cue never varies.

## 6. Safety design (Layer 0)

| Requirement | Mechanism |
|---|---|
| Hard time limit | Every takeover or siege holds a **lock lease** `{id, kind, startedAt, deadline}`. `deadline` is clamped in code to `min(requested, configured max, absolute ceiling)`. Defaults: pulse 4 min (ceiling 10), siege 120 min (ceiling 180). Every guard (activity, accessibility bounce, service) checks the lease before acting. An expired lease is equivalent to no lease. |
| Crash-safe release | The lease is a small file written atomically. A crash handler clears it before the process dies. On restart, the activity refuses to show without a valid lease. A **watchdog receiver runs in a separate process (`:watchdog`)**. It is woken by an exact alarm at the deadline and every 60 s during a lease. If the lease is past its deadline, or the main process's heartbeat is stale (hung UI thread), it clears the lease and kills the main process. The screen then returns to normal. |
| Force stop / reboot | Force stop kills everything and cancels alarms, so nothing can show. Boot clears any lease. Reboot always releases. |
| Safe mode | Crash and abnormal-termination ledger: 3 within 6 h puts the app in safe mode. Takeovers are disabled, alarms cancelled, and one silent notification is posted. Re-arming is manual. |
| Escape hatch | (a) Volume keys **up, down, up, down, up, down, up, down** within 6 s, detected globally by the accessibility service and also inside the app's own screens. (b) **Two fingers held still on any Kotoamatsukami screen for 6 s.** Either one instantly stops audio, clears the lease, cancels every alarm, stops the service and persists `disabled`, which survives reboot. Re-arm only from the main screen. The detectors are pure code in `core` and are unit-tested. |
| Calls | Before any takeover, and twice a second during one: audio mode (`RINGTONE` / `IN_CALL` / `IN_COMMUNICATION`, no permission needed), plus `TelecomManager.isInCall()` if phone-state permission is granted, plus audio-focus loss. Any of these yields the takeover: audio stops, the bounce is suspended, the screen closes, and the task returns later at no cost. Every takeover screen has an always-visible `emergency` word that releases the lease and opens the system emergency dialer. The dialer, in-call and emergency packages are never bounced. |
| Driving / protected windows | Takeovers are only scheduled inside allowed windows, never inside protected blocks or quiet hours. At fire time: if Activity Recognition reports `IN_VEHICLE`, or car mode is on, the takeover is deferred in 10-minute steps and dropped if it leaves the window. |
| Local-only data | No network code at all until Layer 3. Then only the compact state document is sent (section 8). No analytics, no cloud backup (`allowBackup=false`). |

Universal last resorts (documented in the README): Android Safe Mode (long-press "Power off", then "Safe mode") disables all third-party apps. Settings → Apps → Kotoamatsukami → Force stop. `adb shell am force-stop`.

## 7. Scheduling

- Day plan: at the start of each waking window, a deterministic function in `core` picks N takeover times. The times are uniform random inside allowed windows, with a minimum gap, and never in the first 20 minutes after waking (the wake bookend owns that time). They are stored on disk and only the next one is armed as an exact alarm. Re-armed on boot, time change, timezone change and app update.
- Exact alarms use `setExactAndAllowWhileIdle`, **not** `setAlarmClock`: `setAlarmClock` shows the next alarm time in the status bar, which would reveal when the takeover comes. Permission is `USE_EXACT_ALARM` on API 33+ (allowed for a sideloaded app, cannot be revoked) and `SCHEDULE_EXACT_ALARM` on 31-32.
- Foreground service type `specialUse` (no 6 h timeout; allowed to start from boot). A battery-optimisation exemption is requested; it also lifts background foreground-service start limits.

## 8. AI layer (Layer 3, planned now so earlier layers don't block it)

- `Planner` interface in `core`. The Anthropic implementation lives in `app` and is swappable. Prompts live in versioned resource files, not inline.
- The model IDs and API key come from a **git-ignored** `koto.local.properties` at the repo root, read by Gradle into `BuildConfig`. `koto.local.properties.example` is committed. The repo is public, so nothing secret is ever committed and CI never bakes in a key.
- Pipeline exactly as the brief describes: strict JSON schema → hard validators (pure `core` code with adversarial tests) → fresh-context critic → multiple drafts → best passing draft. Rolling 2-day task window, nightly generation, weekly re-plan through the same validators.

## 9. Decisions on OPEN items

| Item | Decision |
|---|---|
| Stack | Kotlin + framework Views (section 1). |
| Takeover surface | Activity, launched by full-screen intent or direct start with accessibility-service exemption (section 3). |
| UsageStats vs Accessibility | Accessibility primary; UsageStats fallback during sieges only. |
| Camera rep counting / notebook photo | **Not in v1.** A notebook photo is cheap (Layer 5 candidate). Rep counting needs ML Kit pose detection, which is heavy and fragile. Deferred, listed under suggestions. |
| "Opening line" variation | Interpreted as both the eyelid-line opening animation and the first text line of a takeover. Both vary; the cue never does. |
| Response latency definition | Seconds from takeover start (first frame shown) to the `begin` tap. For tasks without a begin step, to the first touch. |
| Layer 1 configuration | Layer 1 needs waking hours and protected blocks before the setup flow exists. A minimal terminal-style `windows` screen is built in Layer 1, and Layer 3's setup flow reuses it. The format is one line per rule: `waking 07:30-23:00`, `protect mon-fri 09:00-13:00 classes`. |
| Test trigger | Layer 1 includes a `test spell` command on the main screen so the mechanics can be checked without waiting for a random takeover. |

## 10. Brief items that are infeasible or need adjustment on Android

1. **Red-shifting the whole screen at night.** Apps cannot change system colour. Closest alternative (Layer 5): our own screens go dark red, and a dim red `SYSTEM_ALERT_WINDOW` overlay is drawn over other apps during wind-down. Android 12+ blocks touches through overlays more than 80% opaque, so the overlay stays at ≤ 80% opacity.
2. **"Cannot be snoozed away" wake spell.** Nothing can stop the user powering off or force-stopping the phone, and the safety rules forbid trying. Inside the app there is no snooze. Release requires standing, water and a detected walk (step detector), or the hard limit.
3. **Cue "even face down or on vibrate."** Alarm usage covers vibrate and silent mode and default DND. A user-customised DND that blocks alarms (including Pixel "Flip to Shhh" set to total silence) will still mute the sound. The vibration still fires.
4. **Detecting driving without Google Play Services** isn't possible. On a phone without Play Services, deferral falls back to car mode and protected windows only.
5. **Sideloaded accessibility services on Android 13+** are blocked until the user taps *App info → ⋮ → Allow restricted settings*. This is a one-time manual step and the walkthrough points to it.
6. **OEM battery killers** (Xiaomi, Samsung, Huawei, OnePlus) can kill the service despite the exemption. The walkthrough links the OEM's auto-start / "never sleeping apps" page where one exists. The README links dontkillmyapp.com.
7. **Background activity start rules change between Android versions.** If a launch is blocked anyway, the user still gets the heads-up full-screen-intent notification. This is the honest worst case.

## 11. Build, CI, and install

- `.github/workflows/build.yml` runs on every push. It runs the `core` unit tests, lint, and `assembleDebug`, and uploads the APK as a workflow artifact. The phone can download it from the Actions run page.
- **Signing.** CI signs with a keystore from repository secrets if they exist (`KOTO_KEYSTORE_BASE64`, `KOTO_KEYSTORE_PASSWORD`, `KOTO_KEY_ALIAS`, `KOTO_KEY_PASSWORD`). Otherwise it uses a throwaway debug key. With a throwaway key, each CI build has a different signature, so updating means uninstalling first. Local Android Studio builds use the developer's own debug key. No keystore is committed (public repo).
- `./gradlew -Pkoto.coreOnly=true :core:test` runs every pure-logic test without an Android SDK.

## 12. Build order and status

| Layer | Content | Status |
|---|---|---|
| 0 | Lease + hard limits, crash handler, watchdog, safe mode, escape hatch, call yield, driving/protected deferral | in progress |
| 1 | Service, random scheduling, takeover screen, eye sprite, red flood, cue, release cue, done, hardcoded tasks, windows screen, consent, permissions walkthrough | planned |
| 2 | Logging, latency, feedback, skip/escalation, sieges + blocking, reactive spells | planned |
| 3 | Setup flow, planner pipeline, validators, critic, compact state, nightly and weekly jobs, deload | planned |
| 4 | Sharingan stages, weekly report | planned |
| 5 | Wake/night/mercy spells, variation, health data, optional photo verification | planned |

## 13. Suggestions (not built)

- **Notebook-photo verification** for study sieges: a single photo kept on the device and never sent anywhere. Cheap; Layer 5 candidate.
- **Rep counting via pose detection.** Heavy and fragile; revisit after ten weeks of data.
- **A "lockdown" mode** in which the escape hatch requires a 60 s hold instead of 6 s. Not recommended before Layer 2 has proven stable.

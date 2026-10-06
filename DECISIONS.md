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
| Persistence | Framework SQLite (`SQLiteOpenHelper`) for logs, small atomic JSON files for the profile, plan and day books (L3), and for safety-critical state | "Room or similar". Safety state must be readable from a second process (watchdog), and Room gives nothing there. |
| Background work | `AlarmManager` exact alarms for takeovers and for the planner; planning runs on a background thread kept alive by the foreground service (L3) | Planned as `JobScheduler` (what WorkManager wraps). Changed in Layer 3: jobs are cut off after about 10 minutes, and a local model on a laptop GPU needs far longer. See section 8. |
| Foreground app detection | Accessibility service only | Accessibility events are instant. A `UsageStatsManager` fallback was planned for sieges and dropped in Layer 2: without the guard, Android 14+ won't let the app open the siege screen over another app, so the fallback could see a distraction app but not block it. |
| Driving detection | Google Play Services Activity Recognition (transition API), plus Android car mode | Android has no framework activity-recognition API. This is the single non-framework dependency. |
| JSON / AI | `kotlinx.serialization` (Maven Central) and a thin `HttpURLConnection` client to **Ollama on the owner's laptop** (L3), behind the `Llm` interface | Keeps the AI layer swappable and dependency-light. The brief said Anthropic API; the owner chose a local model (section 8). |
| Min / target SDK | minSdk 29 (Android 10), target/compile 36 | 29 is the oldest version with the `ACTIVITY_RECOGNITION` runtime permission that driving detection needs. API 37 exists, but targeting it changes platform behaviour that has to be re-tested on the phone first. |

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
        task catalog, escalation (L2), plan model, builder and
        validators, day composition and placement, history and signals,
        compact state, the AI pipeline against an `Llm` interface (L3),
        stage logic (L4), pixel-sprite generator, metronome tempo curves
        and PCM synthesis.
app/    Android shell: services, receivers, activities, audio/haptics
        output, storage, permissions walkthrough. Thin over core.
```

App packages:

- `safety`: lease store, crash ledger, safe mode, escape hatch, call guard, driving detector, watchdog (separate process).
- `spell`: foreground service, scheduler and alarms, takeover activity and view, cue engine.
- `guard`: accessibility service (foreground-app events, global volume-key escape, bounce).
- `ui`: palette, pixel font, terminal-style screens.
- `data`: config and storage.
- `ai` (L3): the Ollama client, the planner runner (night work) and the plan keeper (everything about the plan that never needs the laptop).

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
| Calls | Before any takeover, and twice a second during one: audio mode (`RINGTONE` / `IN_CALL` / `IN_COMMUNICATION`, no permission needed), plus `TelecomManager.isInCall()` if phone-state permission is granted, plus audio-focus loss. Any of these yields the takeover: audio stops, the bounce is suspended, the screen closes, and the task returns later at no cost. Every takeover screen has an always-visible `emergency` word that releases the lease and opens the dialer. On a locked phone it asks for the system emergency dialer; Android has no public API for this, so if the phone refuses, closing the takeover leaves the lock screen's own emergency button in front. The dialer, in-call and emergency packages are never bounced. |
| Driving / protected windows | Takeovers are only scheduled inside allowed windows, never inside protected blocks or quiet hours. At fire time: if Activity Recognition reports `IN_VEHICLE`, or car mode is on, the takeover is deferred in 10-minute steps and dropped if it leaves the window. |
| Local-only data | No network code at all until Layer 3. From Layer 3 only the compact state document (and the day's slot list) is sent, and only to the laptop on the home network: the client refuses every non-private address (section 8). No analytics, no cloud backup (`allowBackup=false`). |

Universal last resorts (documented in the README): Android Safe Mode (long-press "Power off", then "Safe mode") disables all third-party apps. Settings → Apps → Kotoamatsukami → Force stop. `adb shell am force-stop`.

## 7. Scheduling

- Day plan: at the start of each waking window, a deterministic function in `core` picks N takeover times. The times are uniform random inside allowed windows, with a minimum gap, and never in the first 20 minutes after waking (the wake bookend owns that time). They are stored on disk and only the next one is armed as an exact alarm. Re-armed on boot, time change, timezone change and app update.
- Exact alarms use `setExactAndAllowWhileIdle`, **not** `setAlarmClock`: `setAlarmClock` shows the next alarm time in the status bar, which would reveal when the takeover comes. Permission is `USE_EXACT_ALARM` on API 33+ (allowed for a sideloaded app, cannot be revoked) and `SCHEDULE_EXACT_ALARM` on 31-32.
- Foreground service type `specialUse` (no 6 h timeout; allowed to start from boot). A battery-optimisation exemption is requested; it also lifts background foreground-service start limits.

## 8. AI layer (Layer 3)

**The model runs on the owner's laptop, not on the Anthropic API.** The brief said "call the Anthropic API directly from the app; keep the model name and API key in a local config file". The owner asked for a local model so planning costs nothing. That changes where the model runs and nothing else:

- **Where.** Ollama on the laptop (RTX 2060 6 GB, 16 GB RAM, left on every night), reached over the home network. The phone runs no model. The laptop address and model names are entered in the app and kept in a file in app storage (`planner.json`); nothing is in the repository and there is no key.
- **Swappable.** The planner knows only `koto.core.ai.Llm` (one call: messages + JSON schema in, text out). `OllamaClient` implements it. A Claude client can implement the same interface later without touching the planner, the validators or the app. Prompts are resource files (`core/src/main/resources/koto/prompts`).
- **Models.** Default `qwen3:14b` (about 9 GB at 4-bit: partly offloaded from a 6 GB GPU, so a few tokens a second, which is fine overnight). A separate "night model" setting covers the brief's "cheaper model for nightly task generation", e.g. `qwen3:8b`. Context is set to 16k tokens per request.
- **Who does what.** Code does every number: ramps from the real baseline, the 15-20% cap, light days and light weeks, sleep, capacity, priorities, the day's sieges and pulses, their timing. The model does judgment and wording: what each goal means (domain, a verbatim quote, topics, which deadlines it covers), the pace per domain (0.5-1.33), when goal mode starts (week 3-5), the light day, workout days, scouting tasks, the critic's review, and each night the topics (weakest first) and micro-learning questions. A 14B local model is reliable at that; it is not reliable at arithmetic over 16 weeks, so it never does any.
- **Pipeline** (`MasterPlanner`), as the brief describes. 4 drafts, each in a fresh conversation. Every answer is checked against the JSON schema it was given (required keys, types, closed lists of domains and deadline ids, no extra keys); malformed answers go back with the parse error. A draft's intent is checked (`IntentValidator`: quotes appear word for word in the user's own text, deadline ids are real, every future deadline is covered, ranges, voice), turned into weekly numbers (`PlanBuilder`) and checked again (`PlanValidator`: capacity, sleep, dates, ramp, baseline, priority, deload, conditioning, prep cap). Every broken rule goes back as the exact error, up to 2 repair rounds. Each draft that passes everything gets a critic call in a fresh context with no drafting history, scored 1-10. The best score wins. Nothing failing a validator reaches the critic or the plan.
- **Weekly re-plan.** When a week turns, code first summarises the week, reads the signals and rebuilds the plan from what was actually done (works with the laptop off). The laptop then gets 2 drafts to refine the intent; same validators.
- **Nightly.** From 20 minutes after waking hours end, retried every 30 minutes until 30 minutes before the next day: re-plan if a week turns, then the next 2 days of tasks. Never more. If the laptop is off, the phone composes the day in code at day start (templates), so takeovers never stop.
- **What is sent.** The compact state document (`CompactState`): profile, goals, where the plan stands, last week's summary, older weeks one line each (up to 8). For nightly tasks also yesterday's few lines and the list of slots to word. Raw logs never.
- **Home network only.** The client resolves the address and refuses anything that isn't loopback, private (10/8, 172.16/12, 192.168/16), link-local, 100.64/10 (Tailscale) or IPv6 ULA. Ollama speaks plain HTTP and Android can't allow cleartext per IP range, so cleartext is allowed app-wide; nothing else in the app uses the network.
- **Process.** A background thread in the main process, kept alive by the existing `specialUse` foreground service and a partial wake lock (at most 6 h), woken by an exact alarm. The battery-optimisation exemption from Layer 1 keeps the network up in Doze.

## 9. Decisions on OPEN items

| Item | Decision |
|---|---|
| Stack | Kotlin + framework Views (section 1). |
| Takeover surface | Activity, launched by full-screen intent or direct start with accessibility-service exemption (section 3). |
| UsageStats vs Accessibility | Accessibility only (see section 1). |
| Camera rep counting / notebook photo | **Not in v1.** A notebook photo is cheap (Layer 5 candidate). Rep counting needs ML Kit pose detection, which is heavy and fragile. Deferred, listed under suggestions. |
| "Opening line" variation | Interpreted as both the eyelid-line opening animation and the first text line of a takeover. Both vary; the cue never does. |
| Response latency definition | Seconds from takeover start (first frame shown) to the `begin` tap. For tasks without a begin step, to the first touch. |
| Layer 1 configuration | Layer 1 needs waking hours and protected blocks before the setup flow exists. A minimal terminal-style `windows` screen is built in Layer 1, and Layer 3's setup flow reuses it. The format is one line per rule: `waking 07:30-23:00`, `protect mon-fri 09:00-13:00 classes`. |
| Test trigger | Layer 1 includes a `test spell` command on the main screen so the mechanics can be checked without waiting for a random takeover. |
| "Worse moment" for a first skip (L2) | The task returns at a random time 45-120 min later, or earlier as an ambush: the first time a distraction app is opened after 20 min. Being caught in the act is the worse moment. |
| Skip cost (L2) | A mark in the local log, shown by the weekly report (Layer 4). On screen: "Later. Marked." Nothing more: no streak loss, no shaming. Unanswered or interrupted takeovers never cost a mark. |
| Escalation ladder (L2) | Level 0 normal → skip or no answer → level 1 normal, still skippable → skip or no answer → level 2 floor, no skip. Nothing escalates past the floor; an unanswered floor is dropped. A call returns the task 10 min later at the same level. Tests and reactive spells never return. Pure code in `core` (`Escalation`), unit-tested. |
| Sieges before the planner (L2) | `sieges N` in the windows config marks N of the day's takeovers as sieges. Two generic sieges (focus 25 min, deep work 50 min; floors 10 and 15) until Layer 3 generates real ones. A siege only fires if it fits the window and the siege limit; otherwise that slot gets a pulse. |
| Siege mechanics (L2) | A siege calls like a pulse under a pulse lease. On `begin` it takes a siege lease (timer + 1 min) and the screen drains to black with a dim eye. Only distraction apps are held: opening one sends it home and shows the siege screen ("Not now."). The tick is quiet and slow and speeds up over the last 3 minutes; audio focus is released so music can play. A call mutes the tick instead of ending the siege, because the lock never touches calls. `stop` ends it early (counts like a skip); `emergency` ends it at no cost. |
| Reactive spells (L2) | Counts opens of distraction apps (an app coming to the front from a different app; system UI, keyboard and this app don't count), all apps together. The third open within 60 minutes fires "Stop scrolling." immediately, then 20 min of quiet. Configurable with `distract` and `reactive` rules. Same safety gate as every takeover. |
| Feedback (L2) | After every finished task the words `easy  fine  too much` stay for 6 s. One tap, or nothing. |
| Spacing (L2) | At least 3 minutes between the end of one takeover and the start of the next (tests excepted). |
| Log screen (L2) | A plain `log` screen lists recent takeovers (results only, never the plan) so Layer 2 can be checked on the phone. The weekly report (Layer 4) replaces it. |
| Setup flow (L3) | One linear flow: goals as free text, deadlines as lines (`2027-01-18 Linear algebra exam`, strictly parsed), 12 sliders for where he is now, priorities ranked by tapping in order, the laptop's address with a `test`, the windows (the Layer 1 editor), then consent. Answers are a typed `Profile`. Setup can't be reopened while armed (precommitment: the escape hatch disarms first). New answers mean a new plan. |
| Before the plan exists (L3) | After setup the main screen says `plan: being prepared.` and nothing else. Takeovers continue as easy catalog pulses, no sieges: the same thing conditioning asks for. The plan starts on the next waking day after it is accepted. |
| Units (L3) | Load is minutes per day, averaged over the week, for study, exercise, walking, Italian, career and cleaning. Steps become extra walking at 100 steps a minute (at most 90 minutes). Brief targets: 3 workouts × 45 min, 10k steps, 6 h study, 30 min cleaning, 20 min Italian, 5 h a week on business or job. Nutrition is counted in pulses (2 → 3 a day). |
| Phases (L3) | Weeks 1-2 conditioning: pulses only, all 10-20 s, one scouting task a day. Foundation: workouts, walks and cleaning ramp as sieges; goals get a scouting task and one micro pulse a day. Goal mode from the week the model picks (3-5), forced by week 5: study, Italian and career sieges ramp too. |
| Deload (L3) | Light day each week at 60%, the other six days carry the difference so the week still averages the plan. Light week every 4th week at 70%, moved up to two weeks later so it never sits on or right before a deadline week; the validator requires one in every 6 weeks. After the last exam study drops to 60 min a day. |
| Day composition (L3) | Study blocks grow with the load (25 → 90 min). Walks up to 30 min, workouts on the workout days (up to 60), career up to 60, cleaning up to 45, Italian a siege from 10 min a day and words and verbs below that. If the day's sieges don't fit its windows, the lowest priorities drop first. Pulses: scouting or micro-learning, nutrition, mercy, movement and focus, at least 6 and at most 12. |
| Timing (L3) | The day's tasks are written the night before; their times are picked on the phone when the day starts, inside the windows as they are then. Sieges go into stretches long enough to hold them, 10 min apart; pulses at least 25 min apart and 5 min clear of sieges. A siege that no longer fits when it fires (a deferral, a new limit) is cut short, never stretched into a protected block. |
| Floors (L3) | Siege floor: a third of it, at least 5 min. Workout floor: a 5-minute walk (the brief's example). Pulse floor: the same command, half the time. |
| Sleep (L3) | Sleep can't be measured until health data (Layer 5). It is protected structurally: waking hours must leave at least 8 hours (setup refuses otherwise, and the validator rejects any plan below target). "Sleep keeps slipping" is read from distraction-app opens 30+ minutes after waking hours end, on 3 or more nights a week; the re-plan then cuts goal work by 10%. |
| Dread watch (L3) | Any of: 3 "too much" in a row; 3 or more "too much" and at least 30% of answers; 40% of 10+ takeovers dodged; median response time up 50% and 10 s on the week before. Then the re-plan cuts all load by 15%, starts the ramp again from what was actually done, and adds 2 mercy spells a day. |
| Weakest topic (L3) | Code ranks each goal's topics: skips and "too much" per task, minus half the "easy", then least practised. The night writer must pick from that list and prefers the weakest. |
| After the plan ends (L3) | Plans run at most 26 weeks. After the last week its numbers hold; a new setup starts a new plan. |


## 10. Brief items that are infeasible or need adjustment on Android

1. **Red-shifting the whole screen at night.** Apps cannot change system colour. Closest alternative (Layer 5): our own screens go dark red, and a dim red `SYSTEM_ALERT_WINDOW` overlay is drawn over other apps during wind-down. Android 12+ blocks touches through overlays more than 80% opaque, so the overlay stays at ≤ 80% opacity.
2. **"Cannot be snoozed away" wake spell.** Nothing can stop the user powering off or force-stopping the phone, and the safety rules forbid trying. Inside the app there is no snooze. Release requires standing, water and a detected walk (step detector), or the hard limit.
3. **Cue "even face down or on vibrate."** Alarm usage covers vibrate and silent mode and default DND. A user-customised DND that blocks alarms (including Pixel "Flip to Shhh" set to total silence) will still mute the sound. The vibration still fires.
4. **Detecting driving without Google Play Services** isn't possible. On a phone without Play Services, deferral falls back to car mode and protected windows only.
5. **Sideloaded accessibility services on Android 13+** are blocked until the user taps *App info → ⋮ → Allow restricted settings*. This is a one-time manual step and the walkthrough points to it.
6. **OEM battery killers** (Xiaomi, Samsung, Huawei, OnePlus) can kill the service despite the exemption. The walkthrough links the OEM's auto-start / "never sleeping apps" page where one exists. The README links dontkillmyapp.com.
7. **Opening the emergency dialer over the lock screen** has no public API (`TelecomManager.createLaunchEmergencyDialerIntent` is system-only). The takeover requests the system emergency dialer by its intent action, which most phones answer. If a phone refuses, the takeover closes and the lock screen's own emergency button is in front.
8. **Background activity start rules change between Android versions.** If a launch is blocked anyway, the user still gets the heads-up full-screen-intent notification. This is the honest worst case.

## 11. Build, CI, and install

- `.github/workflows/build.yml` runs on every push. It runs the `core` unit tests, lint, and `assembleDebug`, and uploads the APK as a workflow artifact. The phone can download it from the Actions run page.
- **Signing.** CI signs with a keystore from repository secrets if they exist (`KOTO_KEYSTORE_BASE64`, `KOTO_KEYSTORE_PASSWORD`, `KOTO_KEY_ALIAS`, `KOTO_KEY_PASSWORD`). Otherwise it uses a throwaway debug key. With a throwaway key, each CI build has a different signature, so updating means uninstalling first. Local Android Studio builds use the developer's own debug key. No keystore is committed (public repo).
- `./gradlew -Pkoto.coreOnly=true :core:test` runs every pure-logic test without an Android SDK.
- **Android's regex engine is ICU, not the JVM's.** It rejects a bare `}` or `]` that the JVM reads as a literal. A placeholder check in the prompt code did exactly that and stopped every planner run on the phone before the first request, while every JVM test passed. `AndroidRegexTest` now checks every `Regex(...)` literal in `core` and `app` against ICU's rule, and CI fails if the prompt files are missing from the APK.
- **Planner log (L3).** The planner writes what it tried to a small log shown on the `log` screen: attempts, the exception or HTTP status of a failure, which drafts passed and the names of the rules the others broke. Never the plan's content (the plan stays hidden); full details go to logcat. The main screen shows one short line with the reason.

## 12. Build order and status

| Layer | Content | Status |
|---|---|---|
| 0 | Lease + hard limits, crash handler, watchdog, safe mode, escape hatch, call yield, driving/protected deferral | done, tested on the phone |
| 1 | Service, random scheduling, takeover screen, eye sprite, red flood, cue, release cue, done, hardcoded tasks, windows screen, consent, permissions walkthrough | done, tested on the phone |
| 2 | Logging, latency, feedback, skip/escalation, sieges + blocking, reactive spells | done, tested on the phone |
| 3 | Setup flow, planner pipeline (local model), validators, critic, compact state, nightly and weekly jobs, deload | built, waiting for the phone test |
| 4 | Sharingan stages, weekly report | planned |
| 5 | Wake/night/mercy spells, variation, health data, optional photo verification | planned |

## 13. Suggestions (not built)

- **Notebook-photo verification** for study sieges: a single photo kept on the device and never sent anywhere. Cheap; Layer 5 candidate.
- **Rep counting via pose detection.** Heavy and fragile; revisit after ten weeks of data.
- **A "lockdown" mode** in which the escape hatch requires a 60 s hold instead of 6 s. Not recommended before Layer 2 has proven stable.
- **Claude as the planner** (L3). An `Llm` implementation over the Anthropic Messages API, for the master plan and the critic only (a few calls a week), with the laptop kept for the nightly words. Better judgment for little money; not built because the owner chose local-only.
- **Sleep from Health Connect** (Layer 5) to replace the late-night-phone-use proxy.

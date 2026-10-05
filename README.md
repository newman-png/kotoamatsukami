# kotoamatsukami

A genjutsu for your goals. Surprise takeovers, hidden AI plan, zero decisions. Android goal system that controls your phone one tiny command at a time. Don't plan your life. Get commanded into it.

Single user, sideloaded, no accounts, no backend. Architecture and every design call: [DECISIONS.md](DECISIONS.md).

**Built so far: Layer 0 (safety) and Layer 1 (takeover and cue).** Takeovers happen at random moments inside your windows and come from a fixed list of easy 10-20 second tasks. Nothing is logged yet and there is no AI yet.

---

## The way out (read this first)

Each of these turns **everything** off: it stops the sound, releases the screen, cancels every scheduled takeover and stops the service. It stays off, across reboots, until you open the app and tap `arm`.

1. **Volume keys:** up, down, up, down, up, down, up, down (eight presses, alternating) within 6 seconds. With the guard enabled this works from any app. Without it, it works on Kotoamatsukami's own screens.
2. **Two fingers:** hold two fingers still on any Kotoamatsukami screen, including a takeover, for 6 seconds.

If those somehow fail, any of these also works:

- **Reboot.** A reboot always releases any lock, and nothing runs again until you unlock the phone.
- **Settings → Apps → Kotoamatsukami → Force stop.** Settings is never blocked.
- **Android Safe Mode:** long-press *Power off* in the power menu, then *Safe mode*. No third-party app runs there.
- `adb shell am force-stop io.github.newmanpng.kotoamatsukami`

Hard guarantees, enforced in code:

- **Time limits.** No takeover holds the screen longer than its limit (default 4 min). The absolute ceilings are 10 min for a takeover and 180 min for a siege, and no config can exceed them.
- **Calls.** An incoming or active call (including VoIP) makes the takeover yield at once: the sound stops and the screen closes. Every takeover shows an `emergency` word that opens the dialer. When the phone is locked it opens the emergency dialer.
- **Crashes.** A crash releases the screen. A separate watchdog process kills the app if it hangs while holding the screen. Three failures within 6 hours put the app in **safe mode**: takeovers stop until you re-arm.
- **Driving.** No takeover while driving (activity recognition or car mode), in a protected block, or in quiet hours.

---

## Install

### Option A: download the APK from GitHub Actions

1. On GitHub open **Actions → build**, pick the latest green run of your branch, and download the `kotoamatsukami-debug-apk` artifact (a zip).
2. Unzip it and copy `app-debug.apk` to the phone (or open the artifact link on the phone directly).
3. Open the APK on the phone. Allow your browser or file manager to *install unknown apps* when asked.

CI builds are signed with a throwaway key unless you add a signing key (see [Stable signing](#stable-signing)). With a throwaway key, installing a newer build means uninstalling the old one first.

### Option B: build it yourself

Requirements: JDK 17+ and Android SDK 36 (Android Studio installs both).

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## First run

Open **Kotoamatsukami**.

1. **`permissions`.** Tap each line that isn't `[ok]` and allow it.
   - *notifications*: takeovers arrive through a silent notification.
   - *full-screen takeovers* (Android 14+): lets a takeover wake the screen over the lock screen.
   - *guard (accessibility)*: takes over while you're using the phone and hears the volume escape from anywhere. **Android 13+ blocks this for sideloaded apps.** If the switch is greyed out: *app info* (the line below the list) → ⋮ menu → **Allow restricted settings**, then go back and switch the guard on.
   - *no battery limits*: keeps the timing exact.
   - *exact alarms* (Android 12 only; automatic on 13+).
   - *physical activity* (recommended): driving detection.
   - *phone state* (recommended): a second call detector.
   - *alarm volume*, *display over apps* (optional).
   - On Xiaomi, Samsung, Huawei, OnePlus and similar phones, also allow auto-start / "never sleeping" for the app: see [dontkillmyapp.com](https://dontkillmyapp.com).
2. **`windows`.** Edit the rules and tap `save`. One rule per line:

   ```
   waking 07:30-23:00
   quiet 22:00-23:00
   protect mon-fri 09:00-13:00 classes
   protect daily 17:30-18:15 commute
   limit pulse 4
   limit siege 120
   spells 6
   ```

   - `waking`: hours when takeovers may happen. They may cross midnight.
   - `quiet`: never take over during these hours, every day.
   - `protect <days> <from-to> <label>`: never take over during these blocks. Days are `mon`…`sun`, ranges like `mon-fri`, lists like `sat,sun`, or `daily` / `weekdays` / `weekends`.
   - `limit`: hard lock limits in minutes. Pulse is 1-10, siege is 1-180.
   - `spells`: takeovers per day (Layer 1 only; the AI planner takes this over in Layer 3).

   Invalid rules are listed with line numbers, and nothing is saved until every rule is valid.
3. **`arm`.** This leads to the consent screen, which explains what will happen and how to get out.

Once armed there is no "off" button. The escape hatch is the way off. The main screen shows `armed.` and nothing about what is coming.

---

## What to test now (Layers 0 + 1)

Do these in order. Stop and report anything that doesn't behave exactly as described.

**Escape hatch first**

1. Arm the app. On the main screen hold two fingers still for 6 s. The status should change to `off.` Tap `arm`.
2. Tap `test spell`, wait 20 s for the takeover, then do the volume sequence (up, down × 4). Everything should go silent and close, with a "Released." notification. Re-arm.
3. Repeat step 2, but hold two fingers on the takeover screen instead.

**The takeover and the cue**

4. Tap `test spell`, then leave the app and open something you scroll (Instagram, YouTube). After about 20 s you should get:
   - a strong vibration pattern (long, long, longer), then
   - a low buzz, then
   - a steady metronome, and
   - the screen dissolving to red with the eye opening from a single line and turning slowly, an opening word typed out, and the command.
5. Tap `> begin`. The beat jumps to the task's tempo and a countdown appears. Let it run out, or tap `> done`. The beat should **stop dead**, then silence, then one soft low tone, and the red should dissolve back to black.
6. Run another test and tap `skip`. You should hear no tone, and the screen should show "Later.".
7. Run another test and press Home during the takeover. With the guard on, the takeover should come back within a second or two.
8. Run another test and ignore it. After 4 minutes at most (your `limit pulse`) it must release by itself, silently.

**Safety**

9. Have someone call you during a takeover. The sound should stop and the screen close immediately, and the call should ring normally.
10. Run a test with the phone locked and the screen off. The screen should turn on and show the takeover over the lock screen. Tap `emergency`: the emergency dialer should open.
11. Set the phone to silent, then vibrate, and run a test in each mode. The cue should still sound (it uses the alarm channel).
12. Reboot during a takeover. After the reboot nothing should be held. The app should stay armed.
13. Put a protected block around the current time (`protect daily HH:MM-HH:MM test`), save, and wait: no random takeover should come during it. `test spell` deliberately ignores windows, so use real waiting time for this check.

**Random takeovers.** Leave it armed for a day. About `spells` takeovers should arrive at moments you can't predict, never in a protected block, quiet hours, or while driving.

Report: did the cue get your attention; was the takeover readable; how fast did you start (roughly); anything confusing, annoying or frightening.

---

## Stable signing

To make CI builds install over each other, create a key once and store it as repository secrets.

```sh
keytool -genkeypair -keystore koto.keystore -alias koto -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 koto.keystore   # copy the output
```

Add these repository secrets (*Settings → Secrets and variables → Actions*): `KOTO_KEYSTORE_BASE64` (the base64 output), `KOTO_KEYSTORE_PASSWORD`, `KOTO_KEY_ALIAS` (`koto`), and `KOTO_KEY_PASSWORD`. Never commit the keystore: this repository is public.

---

## Development

```
core/   pure Kotlin. Safety rules, scheduling, takeover state machine, eye sprite, sound synthesis. Unit-tested.
app/    Android framework code (no AndroidX). Services, receivers, screens, audio output.
```

- `./gradlew :core:test` runs the unit tests: leases and limits, escape detectors, safe-mode policy, window parsing, random scheduling, fire/defer rules, the takeover state machine, metronome timing and visual-safety bounds.
- `./gradlew :app:assembleDebug` builds the APK, and `./gradlew :app:lintDebug` runs lint.

The pixel font is Departure Mono, © Helena Zhang, under the SIL Open Font License; see `app/src/main/assets/fonts/DepartureMono-OFL.txt`.

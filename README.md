# kotoamatsukami

A genjutsu for your goals. Surprise takeovers, hidden AI plan, zero decisions. Android goal system that controls your phone one tiny command at a time. Don't plan your life. Get commanded into it.

Single user, sideloaded, no accounts, no backend. Architecture and every design call: [DECISIONS.md](DECISIONS.md).

**Built so far: Layers 0-3.** Safety (0), the takeover and its cue (1), logging, feedback, skip escalation, sieges with app blocking and reactive spells (2), and the setup flow with the hidden AI plan (3). The plan is made by a local model (Ollama) on your laptop, at night, over your home network.

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
- **Calls.** An incoming or active call (including VoIP) makes the takeover yield at once: the sound stops and the screen closes. Every takeover shows an `emergency` word that opens the dialer. When the phone is locked it opens the emergency dialer, or, on phones that don't allow that, closes the takeover so the lock screen's own emergency button is right there.
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
2. **`setup`.** Answer once: what you want, your deadlines, where you are now (sliders), which goal wins, and where the laptop is (see [The laptop](#the-laptop-layer-3)). The last steps are the windows and the consent screen.
3. **`windows`** (also part of setup). Edit the rules and tap `save`. One rule per line:

   ```
   waking 07:30-23:00
   quiet 22:00-23:00
   protect mon-fri 09:00-13:00 classes
   protect daily 17:30-18:15 commute
   limit pulse 4
   limit siege 120
   spells 6
   sieges 1
   distract instagram threads tiktok youtube reddit x facebook snapchat
   reactive 3 60
   ```

   - `waking`: hours when takeovers may happen. They may cross midnight.
   - `quiet`: never take over during these hours, every day.
   - `protect <days> <from-to> <label>`: never take over during these blocks. Days are `mon`…`sun`, ranges like `mon-fri`, lists like `sat,sun`, or `daily` / `weekdays` / `weekends`.
   - `limit`: hard lock limits in minutes. Pulse is 1-10, siege is 1-180.
   - `spells`: takeovers per day. `sieges`: how many of them are sieges (long locked blocks). Only used until the plan is ready: after setup the takeovers are easy pulses (no sieges), and once the plan exists it decides everything.
   - `distract`: apps that are locked during a siege and watched for scrolling. Use names (`instagram`, `tiktok`, `youtube`, `reddit`, `x`, `facebook`, `snapchat`, `threads`, `pinterest`, `netflix`, `twitch`, `tumblr`, `9gag`, `discord`) or package names (`com.example.app`). Without this rule the list above is used.
   - `reactive 3 60`: the 3rd open of those apps within 60 minutes triggers a takeover on the spot. `reactive off` turns it off.

   Invalid rules are listed with line numbers, and nothing is saved until every rule is valid.
4. **`arm`.** This leads to the consent screen, which explains what will happen and how to get out.

Once armed there is no "off" button and no way back into setup. The escape hatch is the way off. The main screen shows `armed.`, whether the plan is ready, and nothing about what is coming.

---

## The laptop (Layer 3)

The plan is made by [Ollama](https://ollama.com) on your laptop. The phone sends it a short summary (your setup answers and last week's results, never raw logs) over your home Wi-Fi, and only to a home-network address. Do this once on the Windows laptop:

1. **Install Ollama** from ollama.com (Windows installer). It runs in the tray.
2. **Download the model.** Open PowerShell and run:

   ```
   ollama pull qwen3:14b
   ```

   About 9 GB. On an RTX 2060 it runs partly on the CPU, slowly, which is fine at night. Optional, for faster nightly tasks: `ollama pull qwen3:8b`, then enter it as the night model in the app.
3. **Let the phone reach it.** By default Ollama only listens to the laptop itself.
   - Windows search → *Edit environment variables for your account* → *New*: name `OLLAMA_HOST`, value `0.0.0.0:11434`.
   - Quit Ollama from the tray icon and start it again.
4. **Firewall.** When Windows asks whether Ollama may use the network, allow **Private networks** only. If it never asked, run this in PowerShell *as administrator*:

   ```
   New-NetFirewallRule -DisplayName "Ollama (home network)" -Direction Inbound -Protocol TCP -LocalPort 11434 -Action Allow -Profile Private
   ```

   Your home Wi-Fi must be set to *Private* (Settings → Network & internet → Wi-Fi → your network → Private network).
5. **Find the laptop's address.** PowerShell: `ipconfig`, then the *IPv4 Address* under your Wi-Fi adapter, e.g. `192.168.1.20`. In your router's settings, reserve that address for the laptop (often called *DHCP reservation* or *static lease*) so it doesn't change.
6. **Keep it awake at night.** Settings → System → Power → *When plugged in, put my device to sleep after*: **Never**. Leave it plugged in.
7. **Check from the phone.** Open `http://192.168.1.20:11434` (your address) in the phone's browser. It should say *Ollama is running*.

In the app, the setup step `The laptop` (or `laptop` on the main screen) takes the address, the port (`11434`) and the model names, and `test the laptop` should answer *Reached. qwen3:14b ready.*

When the laptop is off, nothing breaks: the phone builds the day itself from the plan, with simpler wording.

---

## What to test now (Layer 3)

Install the new build over the old one (or uninstall first if Android refuses: see [Option A](#option-a-download-the-apk-from-github-actions)). Your windows and log stay. Set up the laptop first ([The laptop](#the-laptop-layer-3)).

**Setup**

1. The main screen now offers `setup` (until you have done it once, even while armed). Go through it: what you want, deadlines (one per line, `2027-01-18 Linear algebra exam`), the sliders, tap the priorities in order, then the laptop: `test the laptop` should answer *Reached. qwen3:14b ready.* Then windows, then `done`.
2. Try to break it: a deadline like `18/01/2027 exam`, a date in the past, waking hours that leave under 8 hours of sleep (`waking 06:00-23:30`). Each should be refused with the reason, and nothing saved.
3. The main screen says `plan: being prepared.` While the laptop is working it adds *the laptop is working.* On the laptop, `ollama ps` in PowerShell shows the model loaded.

**The plan**

4. The first plan takes a while: 4 drafts, each checked by code and reviewed by a critic. Expect 30 minutes to a few hours with a 14B model on that GPU. Until it's ready, takeovers are short easy pulses and never sieges.
5. When it's done the main screen says `plan: ready.` and nothing more: you never see the plan.
6. If something goes wrong, the line under it says what (for example *Laptop not reached: ...*). `laptop` → `try now` starts again at once.

**The days**

7. The plan starts on the next waking day. **Weeks 1-2 are conditioning**: every takeover is a 10-20 second task. Among them, one scouting task a day about your actual goals (e.g. *Open the linear algebra notes.*), and Italian words. After `done` the translation shows (*stanco: tired.*).
8. From week 3 the foundation starts: walks, workouts on the workout days, cleaning, as sieges (*Walk. 10 minutes. Go.*). From goal mode (week 3-5) also study, Italian and career blocks (*Linear algebra. 35 minutes. Go.*), growing slowly. One day a week is lighter. About every 4th week is lighter, but never the week of an exam or the week before.
9. `log` names planned tasks by what they were (*study siege linear algebra*), still only results.

**Laptop off**

10. Turn the laptop off for a night. The next day still has takeovers (the phone builds the day from the plan). The main screen notes *Laptop not reached*. Turn it back on; the next night is back to normal with nothing for you to do.

**Privacy**

11. In `laptop`, enter a public address such as `8.8.8.8` and `test the laptop`. It must refuse: *8.8.8.8 is not on the home network.* Put your address back and `save`.

Report: did setup feel like a short conversation; how long the first plan took; whether week-1 takeovers felt easy; any task that sounded wrong, invented or cheerful.

### Layer 2 checks (passed on the phone; rerun after big updates)

Use `test spell` and `test siege`: once a plan exists, real sieges only come from the plan (from week 3).

**Feedback and the log**

1. `test spell` → `> begin` → let it finish. After the beat stops, the words `easy  fine  too much` appear for a few seconds. Tap one. Open `log` on the main screen: the takeover is there with its latency (seconds from the cue to `begin`) and your answer.

**Sieges and app blocking**

2. `test siege`, wait 20 s, then `> begin`. The red drains to black, a dim eye turns slowly, a 3-minute countdown runs, and a quiet tick plays. It speeds up as the end gets close.
3. During the siege, open Instagram (or any `distract` app). You should be sent to the home screen and the siege screen should come back saying "Not now.". Any other app should open normally.
4. Leave the siege screen with Home or back. The main screen should say `siege.` and offer `return to the siege`.
5. Let it finish: the tick stops dead, then silence, then the low tone, then the feedback words. Run another and tap `stop`: it should end with "Stopped." and no tone (a real siege would say "Stopped. Marked.").
6. Get a call during a running siege. The tick should go quiet for the call and come back afterwards, and the siege should keep running.

**Reactive spells**

7. Open a `distract` app, go home, open it again, go home, and open it a third time, all within an hour. The third open should bring an immediate "Stop scrolling." takeover. After that, nothing reactive for 20 minutes.

**Skip escalation** (needs real scheduled takeovers; tests never escalate)

8. When a random takeover comes, tap `skip`. It should say "Later. Marked.".
9. Wait at least 20 minutes, then open a `distract` app. The same task should come back right then (or by itself 45-120 minutes after the skip). `skip` it again.
10. The next time it comes back it should be the smaller floor version **with no `skip`**. Its time limit and the escape hatch still apply.

### Layers 0 + 1 checks (passed on the phone; rerun after big updates)

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
6. Run another test and tap `skip`. You should hear no tone, and the screen should show "Later." (tests never cost a mark).
7. Run another test and press Home during the takeover. With the guard on, the takeover should come back within a second or two.
8. Run another test and ignore it. After 4 minutes at most (your `limit pulse`) it must release by itself, silently.

**Safety**

9. Have someone call you during a takeover. The sound should stop and the screen close immediately, and the call should ring normally.
10. Run a test with the phone locked and the screen off. The screen should turn on and show the takeover over the lock screen. Tap `emergency`: the emergency dialer should open, or the takeover should close and leave the lock screen (with its emergency button) in front.
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
core/   pure Kotlin. Safety rules, scheduling, takeover state machine, eye sprite, sound synthesis,
        the plan (builder, validators, day composition, history) and the AI pipeline. Unit-tested.
app/    Android framework code (no AndroidX). Services, receivers, screens, audio output.
```

- `./gradlew :core:test` runs the unit tests: leases and limits, escape detectors, safe-mode policy, window parsing, random scheduling, fire/defer rules, the takeover state machine (pulses and sieges), escalation, reactive detection, metronome timing and visual-safety bounds. Layer 3 adds the plan validators against deliberately bad plans (impossible schedules, jumps, missing deload, wrong dates, sleep, starved priorities), the intent checks against invented quotes and deadlines, and the planner and night writer against a scripted fake model that sends malformed JSON, schema breaks, praise and exclamation marks, and pace that is too fast.
- Prompts live in `core/src/main/resources/koto/prompts`. The model is behind `koto.core.ai.Llm`; `app/.../ai/OllamaClient.kt` is the only implementation.
- `./gradlew :app:assembleDebug` builds the APK, and `./gradlew :app:lintDebug` runs lint.

The pixel font is Departure Mono, © Helena Zhang, under the SIL Open Font License; see `app/src/main/assets/fonts/DepartureMono-OFL.txt`.

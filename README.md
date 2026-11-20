# Aura Ring

Control the camera-ring backlight on Xiaomi HyperOS phones from a modern
Material 3 app. Built for the Redmi Turbo 4 Pro (`onyx`, AW21024, 8 RGB
pixels) but the backends are generic enough for other HyperOS ring devices.

Aura is a from-scratch implementation. It does **not** reuse DashDazz's root
driver, because that driver's init sequence (`0x00=0x11`) silences the newer
AW21024 chip used here.

## Two backends

| | Root (preferred) | Shizuku (fallback) |
|---|---|---|
| Pixels | 8 independent RGB pixels | one blended colour |
| Effects | solid, rainbow, breathe, comet, spectrum, battery arc | same modes, whole-ring approximation |
| Setup | KernelSU / Magisk `su` | Shizuku running + permission |
| HyperOS switch | temporarily sets `settings_strip_light_enable=0`, restores on stop | keeps it at `2`, re-asserts colour |

Root mode talks to `/sys/class/leds/aw21024_led` through one persistent `su`
shell, so a 30 fps effect costs no process spawns. The chip is never sent
`0x00` (GCR): writing it clears APSE and the chip stops ACKing I²C until a
`hwen 1`.

Shizuku mode uses `miui.lights.ILightsManager` transaction 2
(`setColorCommon`) and transaction 4 (`setCustomLight`) through
`ShizukuBinderWrapper`. It always runs with the system strip enabled.

## Features

- Live 8-pixel preview rendered by the same code that drives the hardware
- Solid, rainbow, breathe, comet, spectrum and battery-arc modes
- Colour picker with hue strip and presets, per-mode accent colour
- Brightness, speed, breath length
- Charging arc with low/mid/high battery colours
- Music spectrum: 8 log-spaced bands from the system audio mix (Visualizer)
- Per-app notification rhythms: colour + pulse / sweep / blink / breathe,
  rate-limited to one flash per app every 1.8 s
- Screen-off dimming, start on boot
- Quick Settings tile
- Automation API (Tasker, MacroDroid, adb)

## Automation

```sh
# solid cyan at 60 % brightness
adb shell am broadcast -a app.aura.ring.SET \
  -n app.aura.ring/.tasker.AuraCommandReceiver \
  --es hex "#33E1FF" --es mode SOLID --ei brightness 150

# five-second green sweep
adb shell am broadcast -a app.aura.ring.PULSE \
  -n app.aura.ring/.tasker.AuraCommandReceiver \
  --es hex "#4ADE80" --es pattern SWEEP --ei duration 5

# off / toggle / brightness only
adb shell am broadcast -a app.aura.ring.OFF -n app.aura.ring/.tasker.AuraCommandReceiver
adb shell am broadcast -a app.aura.ring.TOGGLE -n app.aura.ring/.tasker.AuraCommandReceiver
adb shell am broadcast -a app.aura.ring.BRIGHTNESS \
  -n app.aura.ring/.tasker.AuraCommandReceiver --ei brightness 200
```

`mode` accepts `OFF`, `SOLID`, `RAINBOW`, `BREATHE`, `COMET`, `SPECTRUM`,
`BATTERY`. `pattern` accepts `PULSE`, `SWEEP`, `BLINK`, `BREATHE`. Colour can
also be passed as a signed int in `--ei color`. Boolean extras `music` and
`notifications` accept `--ez music true`.

Android blocks foreground-service starts from the background. If Tasker should
be able to turn the ring on while Aura is closed, use **Settings → Automation
reliability → Allow background starts**. Otherwise the ring must already be
running; the broadcast still updates its configuration.

## Permissions

- **Root** (optional but recommended): per-pixel effects.
- **Shizuku** (optional): whole-ring effects without root.
- **Notification access**: per-app rhythms.
- **Microphone**: only for the audio spectrum (Visualizer needs it).
- **Notifications**: foreground service notice.
- **Battery optimisation exemption** (optional): lets Tasker start the ring
  while the app is in the background.

## Build

```sh
./gradlew assembleDebug
```

CI builds both debug and release APKs on every push via
`.github/workflows/build.yml`. The release build is signed with the debug key
so artifacts install directly.

## Hardware notes

- AW21024 at `i2c-6`, address `0x30`, driver `leds_awrgb21024.ko`
- 24 channels = 8 RGB pixels, 8 groups
- sysfs: `/sys/class/leds/aw21024_led/{hwen,brightness,rgbcolor,reg,effect}`
- solid: `0xA8/0xA9/0xAA` + `0xAB=0xFF`
- per-pixel: `echo "N RRGGBB" > rgbcolor` for N = 0..7
- global brightness: `0x6E`
- `lamp_effect_brightness` maps 1:1 to `0x6E` but shows a 5 s white preview,
  so Aura scales brightness in software instead

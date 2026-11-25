# Aura Ring

Aura Ring drives the camera-ring light on Xiaomi HyperOS phones. It was built
for the Redmi Turbo 4 Pro (`onyx`, AW21024 controller, eight RGB pixels), but
the backends are generic enough for other HyperOS ring devices.

The app has two ways to talk to the light:

- **Root** (recommended). One long-lived `su` shell writes to
  `/sys/class/leds/aw21024_led`, so all eight pixels can show different
  colours.
- **Shizuku**. Calls `miui.lights.ILightsManager` through the Shizuku binder.
  This path can only set one blended colour for the whole ring.

If neither is available the app still runs, but nothing lights up. Root gives
the best results because the chip is never sent the `0x00` register value that
silences the newer AW21024 controllers.

## What it can do

### Effects

Thirteen modes, all rendered by the same code that drives the hardware:

- **Solid** – one steady colour.
- **Rainbow** – palette colours flowing around the ring.
- **Breathe** – a slow fade in and out.
- **Comet** – a bright head with a short tail.
- **Spectrum** – the eight audio bands, one per pixel.
- **Split** – two colours on opposite halves.
- **Spin** – two spots orbiting the ring.
- **Police** – red and blue emergency strobe.
- **Fire** – warm, flickering embers.
- **Aurora** – a slow wave of colour.
- **Battery** – battery level drawn as an arc.
- **Events only** – the ring stays dark and only reacts to notifications,
  automations or music.
- **Off** – dark.

Brightness, speed, breath length and smoothing are adjustable. Smoothing
cross-fades mode changes, notification pops and brightness steps; the strobing
modes skip the fade so they stay crisp.

### Palettes

Six palettes plus a custom mode that fades between your two chosen colours:
Spectrum, Cyberpunk, Sunset, Ocean, Pastel and Fire. The palette applies to
Rainbow, Spectrum, Fire, Aurora, Comet and the music view of Events-only.

### Music and beat detection

Audio reactivity works in every mode, including **Events only**. When music is
on, the ring follows the audio level, bass, mids and treble, and the beat
envelope. Spectrum mode maps the eight frequency bands directly to the eight
pixels; the other modes use the beat to drive brightness and motion.

Two sources:

- **System audio** uses Android's `Visualizer` on the output mix. It follows
  every player, but some apps block capture.
- **Microphone** listens to the room. It works with any player and is useful
  when the output mix is not available.

Both sources share the same smoothing, peak normalisation, automatic gain and
beat tracker, so switching source does not change how the effects behave. Auto
sensitivity can be turned off and set by hand. The audio engine reports its
state in Settings and retries on its own if capture fails or stalls.

### Notifications

Per-app rhythms are configured on the Apps tab. For each app you can choose:

- a colour,
- a pattern (Pulse, Sweep, Blink or Breathe),
- a length, or the global default.

Notification access is required. Ongoing, group-summary and media
notifications are ignored, because they fire constantly while music plays and
would fight the audio effect. Notifications only decorate a ring that is
already running; they do not start it on their own.

The global style is **Off**, **Flash** or **Effect**. Flash uses the app colour
and pattern. Effect runs the current ring effect for the notification length.

### Screen and power behaviour

- Screen policy: Always, Screen on only, or Screen off only.
- Dim the ring when the screen is off.
- Charging arc, with battery-level colours.
- Frame rate: Smooth, Balanced or Eco.
- Automatic Eco while Android battery saver is active.
- Start on boot.

The frame loop slows to a crawl when the ring is static or dark, and sleeps
until something changes instead of waking up for nothing. Static colours do not
rewrite the hardware every frame.

### Automation

Broadcasts can drive the ring from Tasker, MacroDroid or adb:

```sh
# solid cyan at 60% brightness
adb shell am broadcast -a app.aura.ring.SET \
  -n app.aura.ring/.tasker.AuraCommandReceiver \
  --es hex "#33E1FF" --es mode SOLID --ei brightness 150

# five-second green sweep
adb shell am broadcast -a app.aura.ring.PULSE \
  -n app.aura.ring/.tasker.AuraCommandReceiver \
  --es hex "#4ADE80" --es pattern SWEEP --ei duration 5

# dark ring that still answers events
adb shell am broadcast -a app.aura.ring.SET \
  -n app.aura.ring/.tasker.AuraCommandReceiver \
  --es mode NOTIFICATIONS_ONLY

# off / toggle / brightness / reconnect
adb shell am broadcast -a app.aura.ring.OFF -n app.aura.ring/.tasker.AuraCommandReceiver
adb shell am broadcast -a app.aura.ring.TOGGLE -n app.aura.ring/.tasker.AuraCommandReceiver
adb shell am broadcast -a app.aura.ring.BRIGHTNESS \
  -n app.aura.ring/.tasker.AuraCommandReceiver --ei brightness 200
adb shell am broadcast -a app.aura.ring.RECONNECT -n app.aura.ring/.tasker.AuraCommandReceiver
```

`mode` accepts `OFF`, `SOLID`, `RAINBOW`, `BREATHE`, `COMET`, `SPECTRUM`,
`SPLIT`, `SPIN`, `POLICE`, `FIRE`, `AURORA`, `BATTERY` and
`NOTIFICATIONS_ONLY`. `pattern` accepts `PULSE`, `SWEEP`, `BLINK` and
`BREATHE`. Colours can be passed as hex (`hex`, `hex2`) or as a signed integer.
Other extras: `brightness`, `speed`, `sensitivity`, `music`, `notifications`.

Android blocks foreground-service starts from the background. If Tasker should
be able to start the ring while Aura is closed, allow background starts in
Settings.

## Permissions

- **Root** (optional): per-pixel effects through sysfs.
- **Shizuku** (optional): whole-ring effects without root.
- **Notification access**: per-app rhythms.
- **Microphone**: audio capture. The system-audio source also needs this
  permission, because Android treats output capture as recording.
- **Notifications**: the foreground-service notice.
- **Battery optimisation exemption** (optional): lets Tasker start the ring in
  the background.

## Build

```sh
./gradlew assembleDebug
```

The GitHub Actions workflow builds debug and release APKs on every push. The
release build is signed with the debug key so the artifact installs directly.

## Hardware notes

- AW21024 at `i2c-6`, address `0x30`, driver `leds_awrgb21024.ko`.
- 24 channels: eight RGB pixels.
- sysfs: `/sys/class/leds/aw21024_led/{hwen,brightness,rgbcolor,reg,effect}`.
- Per-pixel colour: `echo "N RRGGBB" > rgbcolor` for `N = 0..7`.
- Global brightness: register `0x6E`. Aura scales colour values in software
  instead of using `lamp_effect_brightness`, because that node triggers a
  five-second white preview on every change.
- Writing `0x00` to the global current register silences APSE and the chip
  stops responding until `hwen 1` is written again. Aura never writes it.

The ring is only eight pixels, so arcs, comets and sweeps are quantised to
those eight positions. Brightness and colour blending are smooth.

Aura Ring is an independent implementation. It borrows ideas from DashDazz and
HaloLight but uses its own driver path, tuned for this controller.

# HomeSecure

An old Android phone guards the flat. It scans the home Wi-Fi, and when none of
my own devices is on it, it turns on the camera with motion detection and sends
the photos by e-mail. If I connect to the Wi-Fi within a short window after the
photos were taken, they are thrown away — because that was me coming home.

The phone can still play music in the meantime: the app runs as a background
service, not as a kiosk, and it only holds the camera while it is actually
guarding.

Android · Kotlin · CameraX · minSdk 26

---

## How it works

```
network scan every 15 s
        │
        ├─ one of the trusted devices answers  ──►  HOME (camera off)
        │
        └─ nobody answers 4 rounds in a row     ──►  GUARDING
                                                        │
                                          motion detected│
                                                        ▼
                                              3 photos to quarantine
                                                        │
                                  ┌─────────────────────┴──────────────────┐
                                  │                                        │
                      you join the Wi-Fi within 30 s              window expires
                                  │                                        │
                                  ▼                                        ▼
                         photos are deleted,                      photos go by e-mail
                         guarding stops
```

The asymmetry is on purpose. **Leaving** is declared only after four failed
rounds, because a sleeping phone sometimes drops a ping, and a false alarm is
worse than a minute of delay. **Coming back** needs a single answer — at that
point there is a quarantine window running over photos that were already taken,
so it has to be fast. While guarding, the scan runs every 3 s instead of 15 s.

## Things that took the longest

**Is my phone on the network?** Android does not let an app read the ARP table
without root, so the app has to ask actively. It tries three independent ways —
ICMP ping, a TCP knock, and the system `ping` binary — because each of them
fails somewhere on its own. The log shows which method answered, and that turned
out to matter: some routers answer a TCP knock on behalf of an address that does
not exist, so "TCP refused" can be switched off as a sign of presence.

**Camera after a reboot.** Android 11+ refuses camera access to services started
from the background, which is exactly the situation after the phone restarts.
The workaround is an invisible `TrampolineActivity` that brings the process to
the foreground for a fraction of a second and starts the service from there.

**Staying alive 24/7.** A watchdog alarm revives the service if the system kills
it, and the phone is meant to sit on the charger (a charging phone does not
enter Doze).

**Motion detection** compares brightness between frames and subtracts the
global change first, so switching on a light or a cloud over the sun does not
count as motion.

**Nothing gets lost when the internet is down.** Photos wait in a queue on disk
and are sent when the connection comes back (capped at 60 events / 250 MB, the
oldest go first). The sending password lives in `EncryptedSharedPreferences`
and app backup is disabled.

## What it does not solve

- A cut internet line — photos are sent later, not at that moment.
- Somebody switching the phone off.
- A friend with keys coming in without me. It cannot tell them from a burglar,
  so it photographs both.

## Structure

| File | What it does |
|---|---|
| `MonitorService.kt` | Orchestrator — four loops: network scan, quarantine, sending, updates |
| `PresenceMonitor.kt` | Turns ping results into "home / away", including smoothing |
| `Probe.kt` | Three independent ways to find out whether an IP is alive |
| `MotionDetector.kt` | Frame brightness comparison with global change removed |
| `CameraController.kt` | CameraX — analysis and capture |
| `EventStore.kt` | Photos on disk, quarantine → outbox via atomic rename |
| `MailSender.kt` | SMTP |
| `SmbUpdater.kt` / `ApkInstaller.kt` | Self-update from my NAS (see [docs/shared-standard.md](docs/shared-standard.md)) |
| `TrampolineActivity.kt` | The camera-after-reboot workaround |
| `WatchdogReceiver.kt` | Alarm that revives a killed service |

This one has no unit tests — it was tested on the real phone in the real flat,
which for this kind of app is the part that matters, but it is the weak spot of
the project and I know it.

## Build

```
./gradlew :app:assembleDebug
```

The UI is in Slovak. The original Slovak README with the full setup guide
(permissions, DHCP reservation, MAC randomisation, troubleshooting table) is in
[README.sk.md](README.sk.md).

# SteamOS Lite

A very small Android launcher for SteamOS on Snapdragon handhelds (target devices: AYN Thor, KONKR Pocket FIT).
It installs a Linux runtime, then runs Valve's native arm64 Steam client in Big Picture under gamescope,
either on its own or straight into an installed game.

The app itself has two screens:

- **Home**: install the runtime once, **Launch SteamOS**, and a grid of the games Steam has installed
  (read from Steam's own `appmanifest_*.acf` files and library art cache, with no login of our own).
- **Session**: fullscreen SteamOS, with the handheld's controls, touch (as the mouse) and keyboard.

Everything else (signing in, installing games, settings) happens inside SteamOS.

## How a session runs

```
SessionActivity (own process, SurfaceView)
 └─ libbannerwayland.so: in-process Wayland compositor, drawing with the bundled Turnip (adrenotools)
     └─ proot (app uid, rootfs = files/linuxfs)
         └─ bannerlator-session steam [steam://rungameid/<id>]
             └─ gamescope --backend wayland -e
                 └─ Steam -gamepadui   (fetched from Valve's CDN on first run)
                     └─ games through Valve's ARM64 Proton + FEX
```

Controllers: each physical pad gets a slot whose state the app writes to an mmap'd ring
(`FakeInputWriter`); `libfakeinput.so`, preloaded into the session, serves it to Steam as
`/dev/input/eventN` (an Xbox 360 pad). Rumble comes back over an abstract socket.
Audio: a bionic PulseAudio daemon with an AAudio sink, reached over a unix socket.

## Requirements

- Snapdragon (Adreno) device. The runtime's Turnip talks to the GPU through KGSL; other GPUs are not supported.
- About 3 GB free for the runtime, plus room for Steam and games.
- Sideload only: `targetSdk` must stay 28 because the runtime executes binaries from app storage.

## Where the pieces come from

Lifted from [Bannerlator](https://github.com/phobos665/Bannerlator), which ported the gamescope runtime
from WinNative:

| Here | Bannerlator |
|---|---|
| `runtime/LinuxRuntime.java`, `RuntimeInstaller.java`, `SessionProcess.java`, `NetworkLink.kt` | `linux/` |
| `runtime/Session.kt` | `XServerDisplayActivity.setupLinuxSession` (Steam mode only) |
| `runtime/PulseAudio.java` | `PulseAudioComponent` (trimmed) |
| `input/FakeInputWriter.java`, `GamepadState.java` | `inputcontrols/` |
| `input/Controllers.kt` | `WinHandler` + `ExternalController` (pared down) |
| `cpp/waylandcomp`, `cpp/adrenotools` | same paths (frame generation stubbed out) |
| `linux/preload`, `linux/fakeinput` | `tools/linuxfs/preload`, `cpp/winlator/fakeinput_steam.cpp` |
| `assets/linuxfs/usr/local/bin/*` | `tools/linuxfs/overlay/usr/local/bin/*` |

The runtime image itself (`linuxfs.tar.zst`, ~790 MB) is built and published by Bannerlator
(`tools/linuxfs`); `RuntimeInstaller.CATALOG_URL` points at its catalog row. No Valve software is
bundled: the Steam client downloads itself from Valve at first launch.

## Building

CI (`.github/workflows/build.yml`) builds the debug APK. The two glibc libraries the session
preloads are cross-compiled there with `aarch64-linux-gnu-gcc` into `app/src/main/assets/linuxfs/`
before Gradle runs; to build locally, run the same step first.

## Logs

Each session writes a folder to `Download/SteamOS-Lite/<timestamp>/` (or the app's own external
files dir without storage permission): `session.log` (gamescope and Steam) and `proot.log`.

## License

GPL-3.0, as Bannerlator and WinNative are.

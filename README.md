# BerryBot

BerryBot is a lightweight, hardware-optimized SSH terminal client designed specifically for the **BlackBerry Classic (Q20)** running **Android 4.3 Jelly Bean (API Level 18 Dalvik)** on BlackBerry 10 OS.

It connects over SSH to a remote terminal/tmux session running Antigravity CLI (`agy`) or any Linux terminal session, tailored for 720×720 square screens and the physical QWERTY keyboard.

---

## Features

- **Hardware Keyboard Optimization:**
  - `Alt + M` $\rightarrow$ Submit prompt (`\r`)
  - `Alt + DEL` $\rightarrow$ Kill prompt line / delete backward word (`\025` / `\033\u007f`)
  - `Shift + DEL` $\rightarrow$ Forward delete (`\033[3~`)
  - `Alt + U` / `Ctrl + U` $\rightarrow$ Discard entire prompt line (`\025`)
  - `Alt + C` / `Ctrl + C` $\rightarrow$ SIGINT / abort prompt (`\003`)
  - `Alt + X` / `Alt + E` $\rightarrow$ Escape (`\033`)
  - `Ctrl + V` $\rightarrow$ Paste from clipboard
- **Optical Trackpad Gestures:**
  - Swiping UP/DOWN on the optical trackpad smoothly scrolls terminal history/scrollback (`Page Up` / `Page Down`), preventing synthetic arrow-key history recall.
  - Trackpad center click submits (`\r`) with haptic feedback.
- **Physical Feedback & Status:**
  - Tactile haptic tick on Enter, Paste, and Clear commands.
  - Top multicolor RGB LED notifications for background output streaming and completion.
- **Low-End Dalvik Performance:**
  - Zero heap allocations during `onDraw()`.
  - Skia run-length background batching (~95% reduction in JNI draw calls).
  - 33ms frame coalescing with 0ms interactive keystroke fast-path.
- **Battery & Cellular Radio Tuning:**
  - Non-reference-counted WakeLock held only during active network operations.
  - 45s keep-alive interval with traffic-aware suppression, allowing mobile radios to enter deep low-power sleep states (PCH/IDLE).
- **In-App Over-The-Air (OTA) Updates:**
  - Polls GitHub Releases directly from the app.
  - Downloads and launches the native BlackBerry 10 package installer.

---

## Building from Source

### Prerequisites
- JDK 17
- Android SDK (API 33 build-tools, API 18 platform)

### Build Commands
```bash
./gradlew test
./gradlew assembleDebug
```
The resulting APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## License

Apache License 2.0

# Universal Remote (Samsung TV)

Android app (Kotlin + Jetpack Compose) that controls Samsung Smart TVs (Tizen, ~2016 and newer) over Wi-Fi.

## Features
- Full remote: power, source, menu, D-pad/OK, back/home/exit/info, volume, mute, channel, media keys, colour keys, guide/tools
- Digit pad (0-9) plus a channel/PIN entry box
- **Keyboard tab**: type any characters (letters, symbols, other languages) and send them into a focused text field on the TV
- App/input shortcuts (Netflix, Prime Video, HDMI 1-3, ...)
- TV discovery (SSDP) or manual IP entry; pairing token is remembered

## Build
Open in Android Studio and run, or `./gradlew assembleDebug` (needs Android SDK).
Every push also builds the APK in GitHub Actions (artifact: `universal-remote-debug-apk`).

## Use
1. Phone and TV on the same Wi-Fi. TV: Settings > General > External Device Manager > Device Connection Manager > Access Notification = *First time only* (or Off).
2. Tap **Find TV** (or type the IP), tap **Connect**, and press **Allow** on the TV the first time.
3. Open a search box on the TV, go to the **Keyboard** tab, type, press **Send**.

Notes: text injection depends on TV model/app support; if a field ignores it, use the on-screen keyboard with the D-pad. Newer models (2014-2015 "H" series and older) use a different protocol and are not supported.

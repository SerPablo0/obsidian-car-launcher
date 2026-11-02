# Obsidian Car Launcher

A lightweight home screen for an NWD Allwinner A100 car head unit
(Android 10, 2 GB RAM, 768×1024 portrait).

- Clock, date and a now-playing card that follows Spotify (or any app playing media)
- Five quick shortcuts and an app drawer
- Built on platform views only — no AppCompat, no wallpaper — to stay smooth on the PowerVR GE8300

- Re-applies speed tweaks on every boot (ksmd off, deadline I/O scheduler) through the
  head unit's own root adbd on loopback, and offers switches to disable unused NWD apps

## Install or update on the head unit (from Termux)

```sh
curl -L -o ~/obsidian.apk https://github.com/SerPablo0/obsidian-car-launcher/releases/latest/download/ObsidianLauncher.apk
adb install -r ~/obsidian.apk
```

On first start the system asks "Allow USB debugging?" for the launcher's own key:
tick "Always allow" so the speed tweaks can run at every boot.

First install only: allow media access and make it the home app.

```sh
adb shell cmd notification allow_listener com.obsidian.carlauncher/com.obsidian.carlauncher.media.MediaListenerService
adb shell cmd role add-role-holder android.app.role.HOME com.obsidian.carlauncher
adb shell pm disable com.android.launcher/com.android.launcher2.Launcher
```

Restore the stock launcher:

```sh
adb shell pm enable com.android.launcher/com.android.launcher2.Launcher
adb shell cmd role add-role-holder android.app.role.HOME com.android.launcher
```

## Build

```sh
./gradlew assembleRelease
```

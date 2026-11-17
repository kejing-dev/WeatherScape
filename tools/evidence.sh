#!/usr/bin/env bash
# Re-shoots the ui_gates evidence after a build (all later than the APK), with real window clicks (pico-cli interact):
# first run (s00 / first-screen), the city "full -> replace?" ask (newgame-confirm: this app has no game; the replace ask
# is its "lose something?" confirmation), settings first screen, quit -> recents, English cold start (default-view),
# hit replay of the five volume targets with the three input sources.
#   DEV=emulator-5564 tools/evidence.sh "<factory>/apps/weather-scape/build"
set -u
B="$1"; DEV="${DEV:-emulator-5564}"; export DEV
ADB="$HOME/Library/Android/sdk/platform-tools/adb"; K="$(dirname "$0")/ws.sh"; PKG=io.embodit.weatherscape
P() { pico-cli interact act --package $PKG --device "$DEV" --action click --format json --text "$1" >/dev/null 2>&1; echo "click $1"; }
L() { "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
mkdir -p "$B"
"$ADB" -s "$DEV" shell am force-stop $PKG; "$ADB" -s "$DEV" shell pm clear $PKG >/dev/null; "$ADB" -s "$DEV" logcat -c
# v0.1.6 (D56 amended): the first start asks ACCESS_COARSE_LOCATION; evidence runs grant it and put the emulator in San Jose
# (the permission dialog itself is shot by hand: pm revoke + cold start, shots-v0.1.6/perm-*)
"$ADB" -s "$DEV" shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION; "$ADB" -s "$DEV" emu geo fix -121.8811 37.3352 >/dev/null
L; sleep 1.5; "$K" shot "$B/s00-loading.png"; sleep 8; "$K" shot "$B/first-screen.png"
P "知道了"; sleep 2
"$K" cmd drag "0.12 hand-ray 700"; sleep 3; "$K" cmd drag "0.03 eye-hand 500"; sleep 3; "$K" shot "$B/after-drag.png"
P "回到现在"; sleep 2
P "城市"; sleep 2; P "纽约"; sleep 3; P "城市"; sleep 2; P "芝加哥"; sleep 3; P "城市"; sleep 2
"$K" cmd search 杭州; sleep 4; "$K" cmd pick 0; sleep 2; "$K" shot "$B/newgame-confirm.png"; P "取消"; sleep 1
P "设置"; sleep 2; "$K" shot "$B/settings-firstscreen.png"
P "退出应用"; sleep 2; "$K" shot "$B/exit-confirm.png"; P "退出"; sleep 5
"$ADB" -s "$DEV" shell dumpsys activity recents > "$B/exit-recents.txt"
echo "recents lines with package: $(grep 'Recent #' "$B/exit-recents.txt" | grep -c $PKG)"
L; sleep 9; "$K" cmd lang en; sleep 2
"$ADB" -s "$DEV" shell am force-stop $PKG; sleep 2; L; sleep 10; "$K" shot "$B/default-view.png"
for src in hand-ray eye-hand controller; do
  for t in ring knob sky tree house stepBack stepFwd; do "$K" cmd replay "$t $src"; sleep 0.8; done
done
sleep 1
"$ADB" -s "$DEV" shell run-as $PKG cat files/input-log.txt | grep -E "REPLAY|INPUT|RINGMOVE|DRAG" > "$B/input-replay.txt"
echo "replay lines $(grep -c REPLAY "$B/input-replay.txt"), target=null $(grep REPLAY "$B/input-replay.txt" | grep -c 'target=null')"
"$K" cmd lang zh
"$ADB" -s "$DEV" logcat -d | grep -c OVERFLOW

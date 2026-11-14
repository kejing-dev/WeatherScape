#!/usr/bin/env bash
# WeatherScape dev harness (macOS). Pinned to one emulator: DEV (borrowed with the factory's emu_borrow.py).
#   tools/ws.sh cmd NAME [ARG]     debug broadcast:  am broadcast -a io.embodit.weatherscape.DEBUG --es cmd NAME --es a ARG
#   tools/ws.sh shot OUT.png       still capture of the device (pico-cli capture screenshot)
#   tools/ws.sh log [REGEX]        logcat lines of tag WeatherScape (optionally filtered)
#   tools/ws.sh install            install the debug APK and start the app
set -u
DEV="${DEV:-emulator-5560}"
PKG=io.embodit.weatherscape
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  # v0.1.2: the 760 dp window no longer fits the current-view frame; a 5120 x 2880 spatial screencap of the whole scene,
  # saved as OUT.png (2560 x 1440) + OUT-panel.png (the control window + TabBar crop; v0.1.3 volume x 1.42 moves it right)
  shot) T="${2%.png}-5k.png"; pico-cli capture screenshot --device "$DEV" --spatial --size 5120x2880 --out "$T" >/dev/null && \
        python3 -c "import sys;from PIL import Image;im=Image.open(sys.argv[1]);im.resize((2560,1440),Image.LANCZOS).save(sys.argv[2]);im.crop((3320,1080,4960,1900)).save(sys.argv[2][:-4]+'-panel.png')" "$T" "$2" && rm -f "$T" ;;
  log) "$ADB" -s "$DEV" logcat -d -s WeatherScape:V | grep -E "${2:-.}" ;;
  install) "$ADB" -s "$DEV" install -r -d "$HERE/../app/build/outputs/apk/debug/app-debug.apk" && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 ;;
  *) sed -n 2,7p "$0" ;;
esac

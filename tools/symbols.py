"""Material Symbols (Apache-2.0, third_party/material-symbols/*.svg, viewBox 0 -960 960 960) -> Android vector drawables
res/drawable/ic_<name>.xml (white fill, tinted by the Icon composable). Profile D23: standard icons, never hand-drawn paths.

  python3 tools/symbols.py
"""
import os, re
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "third_party", "material-symbols")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
NAMES = {"partly_cloudy_day": "ic_tab_weather", "location_on": "ic_tab_cities", "settings": "ic_tab_settings",
         "search": "ic_search", "history": "ic_back_now", "volume_off": "ic_mute", "volume_up": "ic_sound", "logout": "ic_exit",
         "chevron_left": "ic_chev_left", "chevron_right": "ic_chev_right"}
for src, name in NAMES.items():
    d = re.search(r'\bd="([^"]+)"', open(os.path.join(SRC, src + ".svg")).read()).group(1)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Material Symbols Rounded "%s" (Apache-2.0), tools/symbols.py -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
           'android:viewportWidth="960" android:viewportHeight="960">\n    <group android:translateY="960">\n'
           '        <path android:fillColor="#FFFFFFFF" android:pathData="%s" />\n    </group>\n</vector>\n') % (src, d)
    open(os.path.join(OUT, name + ".xml"), "w").write(xml)
    print("wrote", name)

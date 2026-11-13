"""CC0 recordings -> assets/sounds/<event>[_2|_3].wav (16-bit mono 44.1 kHz, trimmed, faded, peak-normalised; design sound.list).
Sources (design / build.md D51: real CC0 recordings for physical sounds):
  ~/Downloads/cc0-sounds/kenney_impact-sounds (CC0)      tick (impactMetal_light), clack (impactMetal_medium)
  ~/Downloads/cc0-sounds/kenney_interface-sounds (CC0)   paper (scratch)
  ~/Downloads/cc0-sounds/freesound-weather (CC0, see its LICENSE.txt)  rain / thunder / wind

Peaks keep the design's order (thunder loudest, paper quietest) inside the factory loudness gate (sounding RMS >= -20 dBFS,
peak <= -1 dBFS). Run with the factory venv (numpy):  ~/.factory-venv/bin/python tools/import_sounds.py
"""
import os, subprocess, wave
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
CC0 = os.path.expanduser("~/Downloads/cc0-sounds")
SR = 44100


def load(path):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", path, "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0


def loudest(x, dur):
    """Start (s) of the [dur] s window with the most energy."""
    n = int(dur * SR); hop = SR // 20
    best, at = -1.0, 0
    for i in range(0, max(1, len(x) - n), hop):
        e = float(np.mean(x[i:i + n] ** 2))
        if e > best:
            best, at = e, i
    return at / SR


def cut(x, start, dur, fin=0.01, fout=0.05):
    a = int(start * SR); y = x[a:a + int(dur * SR)].copy()
    fi = int(fin * SR); fo = int(fout * SR)
    if fi: y[:fi] *= np.linspace(0, 1, fi)
    if fo: y[-fo:] *= np.linspace(1, 0, fo)
    return y


def trim(x, thresh=0.02):
    idx = np.where(np.abs(x) > thresh * np.max(np.abs(x)))[0]
    return x[max(0, idx[0] - 50): idx[-1] + 200] if len(idx) else x


def save(name, y, peak_db):
    y = y / (np.max(np.abs(y)) + 1e-9) * (10 ** (peak_db / 20))
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(y, -1, 1) * 32767).astype(np.int16).tobytes())
    rms = 20 * np.log10(np.sqrt(np.mean(y ** 2)) + 1e-9)
    print("%-10s %.2f s peak %.1f dBFS rms %.1f dBFS" % (name, len(y) / SR, peak_db, rms))


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    imp = os.path.join(CC0, "kenney_impact-sounds", "Audio")
    ui = os.path.join(CC0, "kenney_interface-sounds", "Audio")
    fw = os.path.join(CC0, "freesound-weather")
    # brass ring tick each hour: the softest light metal taps (design: -24 dB relative, short and dry)
    for i, n in enumerate(["impactMetal_light_000", "impactMetal_light_002", "impactMetal_light_004"]):
        save("tick" + ("" if i == 0 else "_%d" % (i + 1)), trim(load(os.path.join(imp, n + ".ogg")))[: int(0.22 * SR)], -0.8)
    # back-to-now brass clack
    for i, n in enumerate(["impactMetal_medium_001", "impactMetal_medium_003"]):
        save("clack" + ("" if i == 0 else "_%d" % (i + 1)), trim(load(os.path.join(imp, n + ".ogg"))), -0.8)
    # tag paper rustle
    for i, n in enumerate(["scratch_002", "scratch_004"]):
        save("paper" + ("" if i == 0 else "_%d" % (i + 1)), trim(load(os.path.join(ui, n + ".ogg"))), -0.8)
    # rain on the roof: 1.5 s, swelling in and out (never a loop, B24)
    rain = load(os.path.join(fw, "rain_roof_333509.mp3"))
    for i, st in enumerate([3.0, 11.0]):
        save("rain" + ("" if i == 0 else "_%d" % (i + 1)), cut(rain, st, 1.5, 0.45, 0.6), -0.8)
    # far thunder, 2.8 s around the loudest roll
    th = load(os.path.join(fw, "thunder_238743.mp3"))
    s0 = loudest(th, 2.8)
    save("thunder", cut(th, max(0.0, s0 - 0.2), 2.8, 0.15, 0.9), -0.8)
    # a gust of wind, 1.2 s
    wd = load(os.path.join(fw, "wind_726319.mp3"))
    s1 = loudest(wd, 1.2)
    for i, st in enumerate([s1, max(0.0, s1 - 2.5)]):
        save("wind" + ("" if i == 0 else "_%d" % (i + 1)), cut(wd, st, 1.2, 0.3, 0.45), -0.8)


if __name__ == "__main__":
    main()

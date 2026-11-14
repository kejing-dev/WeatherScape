"""Copies the Tripo LOD models (design assetsTripo, gate B: stone house A + birch + island A + lamp A) into
app/src/main/assets/models/ and writes domain/ModelCalib.kt (GENERATED, never edit by hand): per model the raw bounds,
the calibration (uniform scale to the design size, bottom-centre to the origin, +Z = front), the island surface height
under every placement point (raycast on the squashed island, like the sample's adopt()), and the pond found from the
island texture (water-coloured vertices). Profile D25: calibration lives in one place.

  ~/.factory-venv/bin/python tools/calibrate_models.py
"""
import json, os, shutil, struct
import numpy as np
import trimesh

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.expanduser("~/Downloads/weather-scape-tripo/lod")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")
KT = os.path.join(ROOT, "app", "src", "main", "java", "io", "embodit", "weatherscape", "domain", "ModelCalib.kt")

MODELS = {  # id: (file, design size, axis)
    "island": ("island-A.glb", 0.38, "w"),
    "house": ("house-A-stone.glb", 0.09, "h"),
    "tree": ("tree-B-birch.glb", 0.14, "h"),  # 0.15 -> 0.14: the hill lifts the root 2 cm; the tree tag must stay under the clouds (deviation)
    "lamp": ("lamp-A.glb", 0.08, "h"),
}
ISLAND_H = 0.05          # squashed thickness (sample adopt(): min(1, 0.05 / h))
ISLAND_BOTTOM = -0.202   # plinth top -0.19, sunk 1.2 cm
# v0.1.4 (B12, user 11-14 "太阳和月亮 云，烟都要有自己的模型"): the sky's own Tripo models (lod/ = glb_decimate.py output).
# id: (file, fit box (x, y, z) in metres - uniform scale to the largest that fits, texture look, emissive factor or None)
SKY = {
    "sun": ("sun.glb", (0.036, 0.036, 0.036), "keep", 1.0),        # glowing disc + rays facing +Z, self-lit texture
    "moon": ("moon.glb", (0.026, 0.026, 0.026), "moon", 0.85),     # craters; the phase shell darkens the night side
    "cloud_a": ("cloud_round.glb", (0.14, 0.07, 0.10), "cloud", 1.0),   # emissive = the shaded texture, so the folds
    "cloud_b": ("cloud_long.glb", (0.14, 0.07, 0.10), "cloud", 1.0),    # still read when the emissive floor (B24) lights
    "cloud_c": ("cloud_puffy.glb", (0.14, 0.07, 0.10), "cloud", 1.0),   # the cloud (WhiteLook colours it per sky)
    "smoke_curl": ("smoke_cut.glb", (0.03, 0.024, 0.03), "cloud", 1.0),
}
PLACE = {"house": (-0.085, -0.075), "tree": (0.100, -0.055), "lamp": (-0.130, 0.095)}


def restyle(path, look):
    """Texture looks: clouds / smoke -> neutral near-white (the Tripo cream tint read yellow next to the WhiteLook greys),
    moon -> lifted 35 % so the craters read at night; sun kept."""
    if look == "keep":
        return
    from PIL import Image
    sc = trimesh.load(path)
    g = list(sc.geometry.values())[0]
    im = np.asarray(g.visual.material.baseColorTexture.convert("RGB")).astype(float)
    lum = im @ [0.2126, 0.7152, 0.0722]
    if look == "cloud":
        # stretch the folds' shading (x 2.4 around 228) so the puffs read, a hint of cool
        v = np.clip(228 + (lum - lum.mean()) * 2.4, 120, 255)
        out = np.stack([v * 0.985, v * 0.992, v], -1)
    else:
        out = np.clip(im * 1.35 + 10, 0, 255)
    g.visual.material.baseColorTexture = Image.fromarray(out.astype(np.uint8))
    sc.export(path)


def rename_node(path, name, emissive=None):
    b = bytearray(open(path, "rb").read())
    n = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + n])
    for nd in j["nodes"]:
        nd["name"] = name
    for m in j.get("materials", []):
        m["alphaMode"] = "OPAQUE"
        if emissive is not None:  # self-lit: the base texture also drives the emissive
            tex = m.get("pbrMetallicRoughness", {}).get("baseColorTexture")
            if tex:
                m["emissiveTexture"] = {"index": tex["index"]}
                m["emissiveFactor"] = [emissive] * 3
    js = json.dumps(j, separators=(",", ":")).encode()
    while len(js) % 4:
        js += b" "
    rest = b[20 + n:]
    total = 12 + 8 + len(js) + len(rest)
    open(path, "wb").write(struct.pack("<III", 0x46546C67, 2, total) + struct.pack("<II", len(js), 0x4E4F534A) + js + rest)


def main():
    os.makedirs(OUT, exist_ok=True)
    rows, extra = [], {}
    meshes = {}
    for mid, (f, size, axis) in MODELS.items():
        dst = os.path.join(OUT, mid + ".glb")
        shutil.copyfile(os.path.join(SRC, f), dst)
        rename_node(dst, mid)
        sc = trimesh.load(dst)
        g = list(sc.geometry.values())[0]
        lo, hi = sc.bounds
        ext = hi - lo
        k = size / (ext[1] if axis == "h" else max(ext[0], ext[2]))
        ky = k
        if mid == "island":
            ky = min(k, ISLAND_H / ext[1])
        cx, cz = (lo[0] + hi[0]) / 2, (lo[2] + hi[2]) / 2
        rows.append((mid, f, lo, hi, k, ky, cx, lo[1], cz, len(g.faces)))
        meshes[mid] = (g, k, ky, cx, lo[1], cz)
    # island surface heights at the placement points (scene coordinates)
    g, k, ky, cx, by, cz = meshes["island"]
    v = (g.vertices - [cx, by, cz]) * [k, ky, k] + [0, ISLAND_BOTTOM, 0]
    tri = v[g.faces]  # (n, 3, 3)
    def height(x, z):
        # vertical ray: triangles whose xz projection holds (x, z), barycentric y, highest hit (no rtree here)
        a, b_, c_ = tri[:, 0], tri[:, 1], tri[:, 2]
        d = (b_[:, 2] - c_[:, 2]) * (a[:, 0] - c_[:, 0]) + (c_[:, 0] - b_[:, 0]) * (a[:, 2] - c_[:, 2])
        ok = np.abs(d) > 1e-12
        d = np.where(ok, d, 1)
        l1 = ((b_[:, 2] - c_[:, 2]) * (x - c_[:, 0]) + (c_[:, 0] - b_[:, 0]) * (z - c_[:, 2])) / d
        l2 = ((c_[:, 2] - a[:, 2]) * (x - c_[:, 0]) + (a[:, 0] - c_[:, 0]) * (z - c_[:, 2])) / d
        l3 = 1 - l1 - l2
        inside = ok & (l1 >= 0) & (l2 >= 0) & (l3 >= 0)
        if not inside.any():
            return ISLAND_BOTTOM
        y = l1 * a[:, 1] + l2 * b_[:, 1] + l3 * c_[:, 1]
        return float(y[inside].max())
    for mid, (x, z) in PLACE.items():
        # lowest of five samples under the footprint centre +-1 cm, so the model never floats
        extra[mid] = (x, min(height(x + dx, z + dz) for dx, dz in [(0, 0), (0.01, 0), (-0.01, 0), (0, 0.01), (0, -0.01)]) - 0.002, z)
    # pond: water-coloured vertices of the island texture
    uv = g.visual.uv
    im = np.asarray(g.visual.material.baseColorTexture.convert("RGB")).astype(float)
    H, W = im.shape[:2]
    c = im[((1 - np.clip(uv[:, 1], 0, 1)) * (H - 1)).astype(int), (np.clip(uv[:, 0], 0, 1) * (W - 1)).astype(int)]
    r, gg, b = c.T
    water = (b > gg * 0.95) & (b > r * 1.3) & (gg > r)
    wv = v[water]
    pc = np.median(wv, axis=0)
    pr = float(np.percentile(np.hypot(wv[:, 0] - pc[0], wv[:, 2] - pc[2]), 80))
    py = float(np.percentile(wv[:, 1], 70))
    top = float(v[:, 1].max())
    out = ["// GENERATED by tools/calibrate_models.py (design assets + D25 calibration). Do not edit by hand.",
           "package io.embodit.weatherscape.domain", "",
           "/** One Tripo model: raw GLB bounds, the uniform scale to the design size and the offset that puts its bottom centre on the origin. */",
           "data class ModelCal(val id: String, val file: String, val rawMin: V3, val rawMax: V3, val scale: Float, val scaleY: Float, val pivot: V3, val triangles: Int) {",
           "    /** Calibrated size in metres (x, y, z). */",
           "    val size: V3 get() = V3((rawMax.x - rawMin.x) * scale, (rawMax.y - rawMin.y) * scaleY, (rawMax.z - rawMin.z) * scale)",
           "    /** The calibration node's local position: -pivot scaled, so the model's bottom centre lands on its parent's origin. */",
           "    val offset: V3 get() = V3(-pivot.x * scale, -pivot.y * scaleY, -pivot.z * scale)",
           "}", "", "object ModelCalib {"]
    def v3(a):
        return "V3(%.5ff, %.5ff, %.5ff)" % tuple(a)
    for mid, f, lo, hi, k, ky, cx, by_, cz, tris in rows:
        out.append('    val %s = ModelCal("%s", "%s", %s, %s, %.6ff, %.6ff, %s, %d)' % (mid.upper(), mid, f, v3(lo), v3(hi), k, ky, v3((cx, by_, cz)), tris))
    out.append("    val ALL = listOf(ISLAND, HOUSE, TREE, LAMP)")
    for mid, (f, box, look, em) in SKY.items():
        dst = os.path.join(OUT, mid + ".glb")
        shutil.copyfile(os.path.join(SRC, f), dst)
        restyle(dst, look)
        rename_node(dst, mid, em)
        sc = trimesh.load(dst)
        g = list(sc.geometry.values())[0]
        lo, hi = sc.bounds
        ext = hi - lo
        k = min(box[0] / ext[0], box[1] / ext[1], box[2] / ext[2])
        cx, cz = (lo[0] + hi[0]) / 2, (lo[2] + hi[2]) / 2
        out.append("    /** v0.1.4 sky model %s (Tripo %s, fit %s m, %d triangles). */" % (mid, f, box, len(g.faces)))
        out.append('    val %s = ModelCal("%s", "%s", %s, %s, %.6ff, %.6ff, %s, %d)' % (mid.upper(), mid, f, v3(lo), v3(hi), k, k, v3((cx, lo[1], cz)), len(g.faces)))
        print("%-10s scale %.4f size %s tris %d" % (mid, k, (ext * k).round(4), len(g.faces)))
    out.append("    val CLOUDS = listOf(CLOUD_A, CLOUD_B, CLOUD_C)")
    out.append("    val SKY = listOf(SUN, MOON, CLOUD_A, CLOUD_B, CLOUD_C, SMOKE_CURL)")
    out.append("    /** Island bottom (scene y): the plinth top -0.19, sunk 1.2 cm. */")
    out.append("    const val ISLAND_BOTTOM = %.4ff" % ISLAND_BOTTOM)
    out.append("    /** Highest island point (scene y), design -0.155. */")
    out.append("    const val ISLAND_TOP = %.4ff" % top)
    for mid in PLACE:
        out.append("    /** %s root on the island surface (scene), raycast. */" % mid)
        out.append("    val %s_AT = %s" % (mid.upper(), v3(extra[mid])))
    out.append("    /** The pond of the island model (scene): centre on the water surface and radius (design: pond position follows the model). */")
    out.append("    val POND_C = %s" % v3((pc[0], py, pc[2])))
    out.append("    const val POND_R = %.4ff" % pr)
    out.append("}")
    open(KT, "w").write("\n".join(out) + "\n")
    for r_ in rows:
        print("%-7s scale %.4f / %.4f tris %d size %s" % (r_[0], r_[4], r_[5], r_[9], ((r_[3] - r_[2]) * [r_[4], r_[5], r_[4]]).round(3)))
    print("places", {k_: tuple(round(x, 4) for x in v_) for k_, v_ in extra.items()}, "pond", pc.round(4), round(pr, 4), "top", round(top, 4))


def snow_shell():
    """The snow shell repeats the island at 5 k triangles (design budget: 62 k for the whole scene)."""
    import subprocess
    dst = os.path.join(OUT, "island_snow.glb")
    factory = os.path.expanduser("~/Desktop/Spatial Apps/spatial-app-factory/bin/glb_decimate.py")
    subprocess.run([os.path.expanduser("~/.factory-venv/bin/python"), factory, os.path.join(SRC, "island-A.glb"), dst, "--faces", "5000", "--tex", "256"], check=True)
    rename_node(dst, "island_snow")


if __name__ == "__main__":
    main()
    snow_shell()

"""Contrast table (D13, ui-audit): every text colour x its background (WCAG ratio), window and 3D texts."""
def lin(c):
    c = c / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
def hexrgb(h): h = h.lstrip('#'); return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
def Y(h): r, g, b = (lin(v) for v in hexrgb(h)); return 0.2126 * r + 0.7152 * g + 0.0722 * b
def ratio(a, b): ya, yb = sorted((Y(a), Y(b)), reverse=True); return (ya + 0.05) / (yb + 0.05)
GLASS = "#B4B3B1"  # darkest measured window glass (factory measure, puzzle-box / royal-ur)
pairs = [
    ("正文 / 大温度 Ink / 窗口玻璃", "#1B1F23", GLASS, 4.5), ("次要字（体感 / 最高最低 / 来源 / 关于）InkSecondary / 玻璃", "#3A3F44", GLASS, 4.5),
    ("城市行 Ink / CardFace", "#1B1F23", "#F4F1EA", 4.5), ("城市行次要字 / CardFace", "#3A3F44", "#F4F1EA", 4.5),
    ("当前城市行 Ink / PrimaryContainer", "#1B1F23", "#DCE6F0", 4.5), ("当前城市行次要字 / PrimaryContainer", "#3A3F44", "#DCE6F0", 4.5),
    ("48h 条日名 InkSecondary / CardFace", "#3A3F44", "#F4F1EA", 4.5), ("48h 条温度线 / CardFace（图形）", "#B4561E", "#F4F1EA", 3.0),
    ("48h 条降水柱 / CardFace（图形）", "#3B6FA8", "#F4F1EA", 3.0), ("48h 光标 Ink / CardFace（图形）", "#1B1F23", "#F4F1EA", 3.0),
    ("回到现在（高亮）白 / Primary", "#FFFFFF", "#3B5674", 4.5), ("知道了 / 换 白 / Primary", "#FFFFFF", "#3B5674", 4.5),
    ("怎么玩序号 白 / Primary", "#FFFFFF", "#3B5674", 4.5), ("退出 白 / Error", "#FFFFFF", "#B3261E", 4.5),
    ("读数牌 墨字 / 奶油底（3D）", "#2B1D0E", "#F3EAD6", 4.5), ("小牌 墨字 / 白底（3D）", "#1B1F23", "#FCFBF7", 4.5),
    ("旋钮刻字 浅 / 深黄铜（3D）", "#F8EDCF", "#7A5B22", 4.5), ("环刻度 / 刻字 墨 / 黄铜（3D，亮处）", "#2B1D0C", "#C09A4C", 4.5),
]
print("| 文字 | 前景 | 底色 | 对比度 | 门槛 | 结果 |\n|---|---|---|---|---|---|")
for n, f, b, t in pairs:
    r = ratio(f, b); print(f"| {n} | {f} | {b} | {r:.2f}:1 | {t} | {'过' if r >= t else '不过'} |")

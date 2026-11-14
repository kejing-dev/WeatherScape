# 更新记录 · Changelog

## v0.1.4 · 2026-11-14

**中文**
- 太阳、月亮、云、烟都换成了自己的模型：带光芒的发光太阳（慢慢转、带光晕），有环形山的月亮（按当天月相亮一部分），三种蓬松积云（阴天、雨天变浅灰），烟囱上一团烟，一缕一缕往上飘、变大、变淡
- 雪花换成看得出六个角的结晶，正对你、慢慢转着飘下来；屋顶积雪照旧
- 设置里加了 [怎么玩]，随时再看一遍操作说明

**English**
- The sun, moon, clouds and smoke have their own models now: a glowing sun with rays (slowly turning, soft halo), a cratered moon showing the real phase, three fluffy cumulus shapes (light grey when overcast or raining), and smoke that rises from the chimney in wisps that grow and fade
- Snowflakes are six-armed crystals that face you and turn as they fall; roof snow stays
- Settings has [How it works] to see the how-to card again

## v0.1.3 · 2026-11-13

**中文**
- 搜城市：中文界面搜 Paris / London 第一条就是巴黎（法国）/ 伦敦（英国）；中文名也认繁体和常用英文别名（东京、北京等 8 组中英首条都对）
- 下雨、下雪时点树或房子，小牌前不再有雨丝、雪片挡读数
- 小景整体放大 1.42 倍（体积 0.94 × 0.77 × 0.97 m），默认距离下铜环约 26°、连两侧 3D 钮约 35°
- 3D ◀ ▶ 上的「1 小时」字加大到能读（≥ 0.5°）
- 零下温度用正规负号「−1°」；控制窗 ◀ ▶ 的无障碍名不再重复

**English**
- City search: in the Chinese UI, "Paris" / "London" now list Paris (France) / London (UK) first; Chinese queries also match traditional spellings and common English aliases.
- Rain and snow no longer pass in front of the tree / house tags.
- The whole diorama is 1.42× larger (volume 0.94 × 0.77 × 0.97 m): the ring spans about 26° at the default distance, about 35° with the 3D buttons.
- The "1 hr" word on the 3D ◀ ▶ buttons is now readable (≥ 0.5°).
- Proper minus sign for sub-zero temperatures; window ◀ ▶ accessibility names no longer repeat.

**已知问题 / Known issues**：环面刻度近乎平视时不好读（读数牌和控制窗有同样时间）；控制窗 ▶ 长按 2 秒松手多走 1 小时。Ring tick labels are hard to read at a flat angle; holding the window's ▶ for 2 s adds one extra hour on release.

## v0.1.2 · 2026-11-13

- 云、烟、雾、雪在头显里是白的（之前在体积窗口里发黑）/ Clouds, smoke, fog and snow stay white in the volume (they rendered near black on the headset).
- 环两侧加 3D ◀ ▶ 铜钮：点一下 1 小时，按住连跳，到头变暗 / 3D ◀ ▶ brass buttons beside the ring: tap for one hour, hold to repeat, greyed at the ends.
- 控制窗加大到 760 × 660 dp：8 个 6 小时时间块，刻度标在可点条下沿，[◀ 1 小时] [回到现在] [1 小时 ▶] / Larger control window with eight 6-hour blocks and hour steps.

## v0.1.1 · 2026-11-12

- 点房子时烟囱的烟让开小牌；主按钮间距 16 dp；怎么玩加「点 48 小时条」；雾、雪的样子重做；城市页留白收紧 / Smoke yields to the house tag, 16 dp button gaps, how-to mentions the strip, new fog / snow look, tighter cities page.

## v0.1.0 · 2026-11-11

- 第一版：桌上天气小景 + 黄铜 48 小时时间环 + 控制窗（天气 / 城市 / 设置），Open-Meteo 真实预报，中英双语 / First version: tabletop weather diorama, brass 48-hour ring, control window, Open-Meteo forecast, Chinese / English.

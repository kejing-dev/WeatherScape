# 更新记录 · Changelog

## v0.1.6 · 2026-11-17

**中文**
- 位置先问系统：第一次打开会问一次粗略位置权限，允许就按系统定位找最近的城市；拒绝或没定到才按网络 IP 猜，IP 属于云服务 / 机房 / VPN 的直接丢掉，不再把你放到几百公里外的机房城市，这时会请你去「城市」页选一个，并给 [去设置]
- 「现在」按气象服务器的时间算，不读头显的时区：表头写「<城市> · 观测 hh:mm」，是那座城市的当地时间；头显时区设错了，城市、气温、时间都不受影响
- 设置里加 [用我现在的位置]，随时重新定位
- 点树、点房子、点天空时，云让开小牌，读数不再被挡
- 声音改成 4 档滑条，点或拖都会换档

**English**
- Location asks the system first: on first launch the app asks once for approximate location and, if allowed, picks the nearest city. Only when that is denied or fails does it guess from your IP, and IPs that belong to cloud / data-centre / VPN providers are discarded, so you no longer land in a far-away server town. You are then asked to pick a city, with a [Settings] shortcut.
- "Now" follows the weather server's clock, not the headset's time zone: the header reads "<city> · as of hh:mm" in that city's local time, so a wrong headset time zone no longer shifts the city, temperature or hour.
- Settings has [Use my location] to locate again at any time.
- Clouds move out of the way of the tree / house / sky tags.
- Sound is a 4-step slider; tapping or dragging both change the step.

**已知问题 / Known issues**
- 权限框 60 秒没回答会按「不允许」处理，之后再点 [允许] 不会当场定位，要到设置里点 [用我现在的位置] / If the permission dialog goes unanswered for 60 s it counts as denied; tapping [Allow] afterwards does not locate right away — use [Use my location] in Settings.
- 离线时已经在「现在」，[回到现在] 仍是高亮 / Offline, [Back to now] stays highlighted even though you are already at "now".
- 冷启动加载模型较慢 / Models load slowly on a cold start.

## v0.1.5 · 2026-11-14

**中文**
- 拨铜环跟手了：之前拖动只认最后一帧的那一点位移，环几乎拨不动；现在从按下起把每一帧的位移加起来，手挪多远环就转多远

**English**
- Turning the brass ring follows your hand now: the drag used only the last frame's tiny movement, so the ring barely moved; it now adds up the movement since you grabbed it

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

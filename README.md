# 天气境 WeatherScape

**中文** · [English](#english)

桌上一座天气小景：拨一拨黄铜时间环，未来 48 小时的天就在小石屋上空演一遍。停下来，读数牌写着「明早 8 点 · 小雨 · 12°」，控制窗给一句：带不带伞、要不要加衣。

PICO OS 6 空间应用（Shared Space，Spatial SDK 6.1.9）。一个体积窗口放小景，右边一块控制窗（天气 / 城市 / 设置）。

- 捏住或扣扳机按住铜环左右拨，往右 = 更晚；松手带惯性，停在整点
- 云、雨、雪、雾、雷、风（树摆、烟斜、风向标）、昼夜、积雪都跟着那一小时变
- 点云、点树、点房子，小牌告诉你降水、风、体感
- 任意城市可搜，最多存 3 个；离线用缓存或内置示例天
- 中英双语，°C / °F，静音常显

天气数据：Weather data by [Open-Meteo.com](https://open-meteo.com)（CC BY 4.0，免费层限非商业）。声音：CC0 录音（Kenney、freesound）。

## 构建

```bash
export JAVA_HOME=<JDK 17+>
./gradlew assembleDebug :app:testDebugUnitTest
```

## English

A weather diorama for your desk. Turn the brass ring and the next 48 hours play out over a little stone cottage: clouds,
rain, snow, fog, storms, wind and night. Stop anywhere and the readout says "Tomorrow 8 AM · Light rain · 54°" while the
control window tells you whether to grab an umbrella. Search any city (keep up to three); offline it falls back to the
cached forecast or a built-in sample day. Weather data by Open-Meteo.com (CC BY 4.0). Sounds: CC0 recordings.

# 第三方组件

本项目自身代码以 GPL-3.0 发布，同时包含或依赖以下第三方组件，它们各自保留原有许可。

| 组件 | 版本 | 许可 | 用途 | 是否随仓库分发 |
| --- | --- | --- | --- | --- |
| [jsoup](https://jsoup.org/) | 1.14.3 | MIT | HTML 解析 | 是，`libs/jsoup-1.14.3.jar` |
| [OpenCC](https://github.com/BYVoid/OpenCC) `TSCharacters` | master | Apache-2.0 | 繁转简字表 | 是，`assets/t2s.tsv`（由该字表转换而来） |
| Android SDK（`aapt2`/`d8`/`zipalign`/`apksigner`、`android.jar`） | API 33 | Android SDK License | 构建工具链 | 否，需自行安装 |

## 为什么锁定 jsoup 1.14.3

`minSdkVersion 21` 意味着要能在 Android 5.0/6.0 上运行。jsoup 1.15 起内部使用了
`java.util.stream`、`java.util.function`、`java.util.Optional`，而 d8 在不启用
core-library desugaring 时**不会脱糖这些 API，也不会给出任何警告**——构建正常通过，
但到 API 21–23 设备上会 `NoClassDefFoundError`。

实测常量池引用：

| 版本 | `java.util.stream` | `java.util.function` | `java.util.Optional` |
| --- | --- | --- | --- |
| jsoup 1.17.2 | 4 个类 | 9 个类 | 1 个类 ❌ |
| jsoup 1.14.3 | 0 | 0 | 0 ✅ |

因此锁定 1.14.3。

## 参考项目

站点结构与抓取流程参考了
[FelixTennouji/ESJZone-epub-novel-mirror](https://github.com/FelixTennouji/ESJZone-epub-novel-mirror)
（GPL-3.0，上游为 [ZALin/ESJZone-novel-mirror](https://github.com/ZALin/ESJZone-novel-mirror)）中的
`esj.py` / `esj.push.py` / `esjbackup.py`。

这些是 Python 脚本，本项目未复制其代码，而是用 Java 重新实现了客户端；不过站点解析规则
（选择器、路径、翻页约定）来自对它们的阅读，并针对当前站点做了修正。仓库中不存在任何
上述脚本或其副本。
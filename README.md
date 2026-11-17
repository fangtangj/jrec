# JRec

用 Java 写的跨平台屏幕录制工具。全屏或框选一块区域，录下画面与声音，输出标准 MP4。

桌面端已实现并实测通过；Android 端在路线图里（见[已知限制](#已知限制)）。

**产品页：https://fangtangj.github.io/jrec/**

![主界面](docs/screenshot-main.png)

## 特性

| 能力 | 状态 | 说明 |
|---|---|---|
| 实时取景 | 已实现 · 已实测 | 窗口内实时预览即将录制的画面，选区时只预览选中区域 |
| 全屏录制 | 已实现 · 已实测 | 覆盖全部显示器 |
| 自选区域录制 | 已实现 · 已实测 | 拖框选择，采集时就裁剪，不做二次重编码 |
| 麦克风录制 | 已实现 · 已实测 | DirectShow 设备枚举 |
| 系统声音录制 | 已实现 | Windows 需系统存在回环设备，见[说明](#windows-上录不到系统声音) |
| 麦克风 + 系统声音混音 | 已实现 | 单条 AAC 音轨，`amix` 混流 |
| 硬件编码 | 已实现 | 自动探测 NVENC / QSV / AMF，可选 |
| 录制历史 | 已实现 · 已实测 | 时长、分辨率、音轨、体积、播放、打开目录 |
| 首次启动自动装 FFmpeg | 已实现 | 下载到 `~/.jrec/bin`，仓库不带二进制 |
| 暂停 / 继续 | 未实现 | 见路线图 |
| Android 端 | 未实现 | 见路线图 |

录制中：

![录制中](docs/screenshot-recording.png)

框选区域（离屏渲染，背景的桌面内容不在快照里，所以是灰的）：

![框选区域](docs/screenshot-region.png)

## 快速开始

需要 **JDK 17+** 和 **Maven 3.8+**，不需要预装 FFmpeg。

```bash
git clone <this-repo>
cd jrec
mvn install -DskipTests

cd jrec-desktop
mvn javafx:run
```

首次启动若本机没有 FFmpeg，界面会提示并提供一键下载（约 80 MB，装到 `~/.jrec/bin/ffmpeg.exe`，不污染系统 PATH）。

想用自己的 FFmpeg：

```bash
mvn javafx:run -Djrec.ffmpeg=/path/to/ffmpeg
# 或设置环境变量 JREC_FFMPEG
```

## 为什么这样设计

这几个决定是这个项目里真正花时间的部分，也是面试时能讲的东西。

**不用 JavaCV，直接调 FFmpeg 命令行。** JavaCV 会把 200MB+ 的原生库塞进依赖，而它封装的恰恰是一条命令行。直接 `ProcessBuilder` 调二进制的好处：区域录制只是 `-offset_x/-offset_y/-video_size` 三个参数；出错时 FFmpeg 的 stderr 能逐行接住并展示给用户；`jrec-core` 的运行时依赖是**零**。

**不用 `java.awt.Robot` 逐帧抓屏。** 实测 1080p 下 `Robot` 只有 10~15 帧，且抓不到系统声音，音视频同步要自己写。这条路只在导师禁止调用 FFmpeg 时才值得走。

**停止录制发 `q`，不是 `kill`。** 直接杀进程会让 MP4 缺少 `moov` 索引，文件打不开。FFmpeg 接受 stdin 上的 `q` 来正常收尾，所以停止走这条路径，只有 15 秒不响应才降级为强制结束。（这个行为在本项目的 Windows + FFmpeg 6.0 上实测过，见 `RecorderLiveTest`。）

**区域录制在采集端裁剪。** `gdigrab` 支持只读一块矩形，所以编码器从头到尾只看到用户选的那块，不做「录全屏再裁」的浪费。macOS 的 `avfoundation` 不支持采集时裁剪，只能退回 `crop` 滤镜——这也是 macOS 区域录制更费 CPU 的原因，代码里写明了。

**录制核心不依赖任何 UI。** `jrec-core` 里没有一行 JavaFX，所以录制引擎可以在无界面环境下被测试驱动。

## 模块

```
jrec/
├── jrec-core/       录制引擎：FFmpeg 定位与下载、音频设备探测、命令行拼装、进程生命周期
└── jrec-desktop/    JavaFX 客户端：主界面、区域框选遮罩、录制历史
```

核心类：

| 类 | 职责 |
|---|---|
| `Recorder` | 一次录制的状态机：start → recording → stopping → finished/failed |
| `CommandBuilder` | 纯函数，把配置 + 平台后端拼成参数数组，可单测 |
| `CaptureBackend` | 平台差异的唯一出口（Windows / Linux / macOS 三个实现） |
| `FfmpegLocator` / `FfmpegDownloader` | 找 FFmpeg，找不到就装 |
| `AudioDevices` | 枚举音频设备，并按名字判断哪个是系统声音回环 |
| `MediaProbe` | 从一个成品文件里读出时长、分辨率、有无音轨 |

## 实测情况

Windows 11 + JDK 17 + FFmpeg 6.0（gyan.dev essentials 构建）：

| 验证项 | 结果 |
|---|---|
| 全屏录制 1920×1080 | 通过，产物 3.00s / H.264 |
| 区域录制 800×600 @ (100,100) | 通过，产物精确 **800×600**，非缩放 |
| 视频 + 麦克风混录 | 通过，AAC 48kHz 立体声，音轨存在 |
| 中文/括号设备名（`麦克风 (ToDesk Virtual Audio)`） | 通过 |
| stdin 发 `q` 优雅停止 | 通过，exit code 0，文件可播放 |
| 框选遮罩拖动选区 | 通过，200,150 → 900,600 得到 `700x450@200,150` |
| 实时取景预览 | 通过，2 fps 刷新，录制时自动暂停 |
| UI 全流程（点开始→录→点停止→保存→列表出现） | 通过，产物 1.3 MB |
| 单元测试 + 实拍测试 | `mvn test` 共 15 项全绿 |

复现测试：

```bash
cd jrec
mvn test -Djrec.ffmpeg=/path/to/ffmpeg
```

没有 FFmpeg 的机器上，实拍测试会自动跳过而不是失败。

## 已知限制

**macOS / Linux 已实现但未实测。** 代码路径写完了（`x11grab` + `pulse`，`avfoundation`），但这台开发机是 Windows，没有真机验证。用这两个平台的人需要自己承担风险。

**Windows 上录不到系统声音。**
Windows 不默认开放系统声音的录制通道。需要满足其中一条：

1. 「声音设置 → 更多声音设置 → 录制」里右键空白处，勾选「显示已禁用的设备」，再启用「立体声混音 / Stereo Mix」；
2. 装虚拟声卡（VB-Audio Virtual Cable、VoiceMeeter 等），把系统播放输出指向它。

界面检测不到回环设备时，会显示一行橙字并可点击查看这段说明。

**混合 DPI 多显示器会裁歪。** 区域框选用光标所在屏幕的缩放比换算物理像素，一台 150% 一台 100% 的配置下无法同时正确。系统声音、区域录制在单屏或同缩放多屏上是准的。

**长录制没有崩溃保护。** 录制中进程若被强杀，MP4 没有 `moov` 索引，文件作废。专业做法是先写 MKV 再转封装，已在路线图里。

**暂停/继续未做。** FFmpeg 没有原生暂停，正确做法是分段录制再 `concat`，工作量不小，还没排上。

## 路线图

- [ ] Android 客户端（`MediaProjection` + `MediaRecorder`，Java）
- [ ] 暂停 / 继续（分段录制 + concat 无损拼接）
- [ ] 崩溃安全录制（先落 MKV，停止时无损转 MP4）
- [ ] 录制后自动生成缩略图与分享页

## 开发工具

`jrec-desktop/src/main/java/com/rzx/jrec/desktop/dev/` 下有三个不参与产品逻辑的工具：

- `UiSnapshot`：把窗口离屏渲染成 PNG，用来生成 README 里的截图，不依赖桌面状态。
- `UiFlowProbe`：驱动真实界面走完一次「开始→录 4 秒→停止→保存」，用来验证 UI 接线（监听器、视图切换、计时器、历史刷新），并顺带产出录制态截图。
- `UiRegionProbe`：向框选遮罩合成鼠标事件，验证拖动选区能产出正确的坐标与尺寸（实测 200,150 → 900,600 得到 `700x450@200,150`）。

运行方式（需 JavaFX 在 module path 上）：

```bash
java --module-path <javafx jars> --add-modules javafx.controls \
     -cp "jrec-desktop/target/classes;jrec-core/target/classes" \
     com.rzx.jrec.desktop.dev.UiFlowProbe <输出目录>
```

## License

MIT

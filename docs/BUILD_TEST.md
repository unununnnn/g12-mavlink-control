# 构建、测试与复现

## 核心离线回归

要求 JDK17+。PowerShell 设置 JAVA_HOME 后运行 `./scripts/test.ps1`；Linux/macOS 运行 `bash scripts/test.sh`。脚本用 `--release 17 -Xlint:all -Werror` 编译实际 core 和测试源码。输出 `artifacts/vectors.json`。不使用 Android、Gradle、串口或网络。

如本机已安装 pymavlink，运行 `python scripts/check_vectors.py`；否则此额外交叉验证被依赖阻塞，不应自动安装。它使用独立实现编码并解析全部4类 Java 向量，逐字节核对。

回归覆盖：每个分片位置、粘包/噪声、CRC 单 bit 损坏、签名拒绝、半帧超时、500组确定种子轴值、非广播目标、油门量程、默认只读、硬件适配禁用、输入过期边界、心跳超时、deadman、源有效性、重复时间戳/旧会话回调、时钟回退、高油门接管拒绝、进程内重复接管、写入故障、交还前后帧类型、过期/先前主控确认和后台停止对应核心入口。

## Android 模拟 APK

固定版本：JDK17（本机 JDK21 可离线编译 core）、Gradle8.11.1、AGP8.9.2、Platform35、Build Tools35.0.0。设置 ANDROID_HOME，或在忽略的 local.properties 指定 sdk.dir。仓库未附 Gradle wrapper JAR，避免假装有可运行 wrapper；使用明确版本 Gradle：

```text
gradle --no-daemon :core:check :app:assembleDebug :app:lintDebug
```

GitHub Actions `Verify simulation` 使用 runner 预装 Android 工具并下载固定 Gradle/AGP 依赖；不自动接受 SDK 许可证。缺预装 Platform35/BuildTools35 时明确失败。本机缺 Android SDK，未安装新工具。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，CI artifact `g12-simulation-debug`；只上传 APK、lint 报告和协议向量，绝不上传 debug.keystore。debug APK 只供模拟界面检查，不是硬件版本。

不自动连接设备，不调用 adb、不安装、不解锁、不发送真实控制。当前没有 Android 仪器化测试或 G12 UI/SDK 运行验证，编译/lint通过也不等于运行验收。

## 后续隔离模拟/SITL

本阶段仅进程内模拟，没有运行 SITL。后续可在无真实飞控/遥测路由的隔离网络运行固定4.7.1 SITL；须先增加仅限显式 loopback 的模拟适配，不能在现版本假称已经支持。记录原 failsafe/围栏参数，禁止通过关闭安全功能让测试通过。验证输入停止后 override 超时、GCS failsafe、不健康 SBUS 交还拒绝、两端同时请求和进程崩溃。当前互斥只限单进程，跨设备测试必须先设计仲裁。

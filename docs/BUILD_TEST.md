# 构建、测试与复现

当前0.5全通道直传版本使用 `x9` / `x9-core`，构建命令及回归入口见 [CHANNELS](CHANNELS.md)。[X9_DIRECT](X9_DIRECT.md)记录历史0.4绑定版。以下 `sender` 特指保留的电脑收包台架应用；其10秒窗口不适用于新直连应用。

单链路真实SDK发送版是独立 `sender` APK；电脑收包/回显工具、固定依赖、配置、构建和未验收边界见 [SERIAL_BENCH](SERIAL_BENCH.md)。`scripts/test.*`现同时运行原core、只读输入会话和sender-core断言。真实SDK发送版与原模拟/只读包分开检查，未执行安装或硬件发送。

真正集成官方SDK的Android13 G12台架包使用独立 `bench` 模块；构建命令、固定AAR哈希、只读边界、兼容性和用户验收步骤见 [SDK_BENCH](SDK_BENCH.md)。下文 `app` / `g12-simulation-debug` 仍仅指旧模拟包。

## 核心离线回归

要求 JDK17+。PowerShell 设置 JAVA_HOME 后运行 `./scripts/test.ps1`；Linux/macOS 运行 `bash scripts/test.sh`。脚本用 `--release 17 -Xlint:all -Werror` 编译实际 core 和测试源码。输出 `artifacts/vectors.json`。不使用 Android、Gradle、串口或网络。

如本机已安装 pymavlink，运行 `python scripts/check_vectors.py`；否则此额外交叉验证被依赖阻塞，不应自动安装。它使用独立实现编码并解析全部4类 Java 向量，逐字节核对。

回归覆盖：每个分片位置、粘包/噪声、CRC 单 bit 损坏、签名拒绝、半帧超时、500组确定种子轴值、非广播目标、油门量程、默认只读、硬件适配禁用、输入过期边界、心跳超时、deadman、源有效性、重复时间戳/旧会话回调、时钟回退、高油门接管拒绝、进程内重复接管、写入故障、交还前后帧类型、过期/先前主控确认和后台停止对应核心入口。

## Android 模拟 APK

固定版本：JDK17（本机 JDK21 可离线编译 core）、Gradle8.11.1、AGP8.9.2、Platform35、Build Tools35.0.0。设置 ANDROID_HOME，或在忽略的 local.properties 指定 sdk.dir。仓库未附 Gradle wrapper JAR，避免假装有可运行 wrapper；使用明确版本 Gradle：

```text
gradle --no-daemon :core:check :app:assembleDebug :app:lintDebug
```

GitHub Actions `Verify Android and isolated SITL` 使用 runner 预装 Android 工具并下载固定 Gradle/AGP 依赖；不自动接受 SDK 许可证。缺预装 Platform35/BuildTools35 时明确失败。本机缺 Android SDK，未安装新工具。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，CI artifact `g12-simulation-debug`；该artifact只上传 APK、lint 报告和协议向量，绝不上传 debug.keystore。debug APK 只供模拟界面检查，不是硬件版本。

不自动连接设备，不调用 adb、不安装、不解锁、不发送真实控制。当前没有 Android 仪器化测试或 G12 UI/SDK 运行验证，编译/lint通过也不等于运行验收。

## 可选SDK只读签名核对

`sdk-probe` 不在 settings.gradle 中，普通构建不包含厂商类。将官方RCSDK保存在仓库外后运行 `./scripts/check_sdk_signatures.ps1 -SdkAar <rcsdk-v1.9.2.aar绝对路径>`。脚本先校验已核实SHA256，再提取class文件供javac编译原创只读类；不会执行厂商SDK、初始化设备或把SDK打进APK。实际编译已通过；这是API链接检查，不是SDK运行或故障注入验收。

## 隔离SITL和跨进程仲裁

`python -m unittest lab.test_arbiter -v` 使用标准库和临时内存密钥验证多进程排他、重放/过期、故障与交还。固定SITL构建、严格隔离运行和证据范围见 [SITL.md](SITL.md)。CI单独job安装固定Python测试依赖，保存 `g12-stock-sitl-evidence`，不分发飞控或厂商SDK二进制。实际结果见验收/验证记录；不能把脚本存在当成运行通过。

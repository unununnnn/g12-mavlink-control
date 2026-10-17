# G12 官方 SDK 只读台架 APK

当前候选版0.2.1新增“停止采集”保留前台SDK连接，另设“断开SDK / 释放端口”；后台和当前采集错误仍释放。见[启停修复与真机复测](RESTART_FIX.md)。下文保留0.2.0首次交付背景；用户后续报告首轮读取成功、停止再读超时，候选版尚未真机验收。

此包是第一阶段真机采集交付，**不是完整 MAVLink 遥控版**。用户确认目标 G12 运行 Android 13；尚无具体系统构建号、CPU ABI 实测或真机运行结果。独立包名 `com.g12.bench.readonly`，名称「G12 台架只读」，版本 `0.2.0-sdk-readonly`，不会与旧模拟包混淆。

## 来源与打包依据

官方来源为 [Skydroid RCSDK-Demo](https://gitee.com/skydroid/rcsdk-demo)，固定 commit `d9c6bcab8bb9993cd16f117c01a7601dce10ea6a`；AAR `rcsdk-v1.9.2.aar` SHA256 `e5977ba3292246bcee026defdb8563a18adc9d5573fdfb20a6880ee469f0fde2`。

官方 README 的「空白项目集成 SDK」明确指导将随 Demo 提供的 AAR 加入用户 Android 项目，并列出 G12。本次按该集成用途和用户授权制作私有项目台架 APK，没有遇到账户、API 密钥、点击接受或签署协议步骤。源码仓库不收录 AAR、原生库或签名私钥，CI 从固定官方提交获取并校验。此前把“未声明许可证”笼统列为所有 APK 开发的硬阻塞过于宽泛：集成指引已提供本次开发依据；Gitee 仍未声明通用开源/再分发许可证，不能据此声称可公开转售或单独分发厂商 SDK。APK 只交付此私有项目使用；公开/商业分发范围仍需另行核实。本说明不代签厂商条款。

## 已实现

- 手动开始后才初始化 SDK（每进程一次）、连接遥控器。使用 application context，回调归一到主线程。
- SDK 识别 G12 后，通过真实 `KeyManager.get(KeyChannels)` 读取数组；不使用 MotionEvent、模拟输入或预设摇杆数值。显示 CH01 起的原始数组顺序，不假定通道映射、量程或物理单位。
- 监听 `AirLinkKey.KeyRawSignalQuality` 和 `KeySignalQuality`；原始报告中 `dev_connect` 只有布尔值才解释为接收机连接状态。显示原始报告和回调年龄；缺失或过期标为未知。此信号不能证明 GR00 至 X9 数据路径可用。
- 单请求在途，成功后至少100ms再请求；500ms未更新标过期，1000ms请求超时或SDK断开则停止并清空；5s连接超时停止。后台、锁屏/暂停时停止并释放端口，恢复前台不自动重启。旧回调、重复回调不能恢复会话。
- UI 显示手机厂商/型号、Android API、系统构建号和支持 ABI，便于用户提交兼容性证据；不自动上传数据。

## 运行时隔离

应用仅调用 SDK 的初始化、连接、断开、GET、LISTEN 和取消监听。未创建任何数据管道；不引用控制 core，不提供 KeyManager SET/ACTION、对频、校准、解锁、刷写或 MAVLink 发送入口。完整厂商 SDK 本身包含未调用的写入/升级接口，不能声称二进制里不存在这些功能；CI 检查的是本项目编译类的调用边界。

SDK 正常连接和 GET 会产生其自身设备通信，不能称为全无线静默。台架首次测试应断开接收机与飞控连线，避免把 SDK 内部通信行为等同于已审计的被动抓包。现阶段所有 callback 时刻都是本地接收时刻；厂商源端缓存、新鲜度、重连旧回调语义未验证，因此无论显示多新都不能用于控制授权。

## Android 13 兼容边界

编译 API35，target API33（Android13），min API23。AAR 声明 min21/target32；本项目提高 target 后必须由实际设备验证。仅 INTERNET 普通权限，无运行时权限对话框需求；不调用 Wi-Fi 配网/扫描、蓝牙、相机、存储、通知或后台服务。唯一 launcher Activity 显式 exported=true，其余无导出服务/接收器/provider。见 [Android13 行为变化](https://developer.android.com/about/versions/13/behavior-changes-13) 和 [组件导出要求](https://developer.android.com/about/versions/12/behavior-changes-12#exported)。

官方 AAR 含 `armeabi-v7a`、`arm64-v8a` 两种 ABI，各7个原生库，APK保留两种及原始库字节；不含 x86 库。实际 G12 ABI、厂商系统服务、设备识别所需系统属性/非公开接口权限、SELinux/端口占用仍需真机验证。Android13 版本号不足以证明上述条件。README要求20250110+系统的是新版 G12/G20 **数传 UDP 管道**；本包未创建该管道，仍记录具体构建号待后续适配。

## 构建与证据

现有 JDK17 / Gradle8.11.1 / AGP8.9.2 / Android35工具链。不自动接受新的 SDK 协议，不向本机安装工具。

```bash
bash scripts/fetch_skydroid.sh
bash scripts/test.sh
gradle --no-daemon -PwithSkydroid=true :bench-core:check :bench:assembleDebug :bench:lintDebug
python3 scripts/verify_bench.py bench/build/outputs/apk/debug/bench-debug.apk vendor/rcsdk-v1.9.2.aar
```

也可通过 `-PskydroidAar=/absolute/path/rcsdk-v1.9.2.aar` 指定合法取得的原包；错误哈希拒绝构建。不加 `-PwithSkydroid=true` 时保持原模拟项目可独立构建。

GitHub Actions `bench.yml` 手动触发，产物名 `g12-sdk-readonly-android13`，保存 APK、SHA256、manifest badging、签名校验、原生库哈希对照、字节码边界审查和 lint 报告。debug APK 用临时 runner 的测试签名，**不具备稳定生产签名**，未来不同构建可能需要先卸载此测试包（不会影响独立模拟包）。不导出签名私钥。ARM硬件未到场，不能以构建/契约测试代替运行测试。

## 用户台架验收记录

1. 用户自行安装 APK；首次仅 G12 供电、接收机与飞控断线。关闭占用 SDK 端口的助手/地面站。打开应用应显示未启动，无模拟值。
2. 点击开始，记录 SDK 识别类型、系统构建、ABI。摇动每根轴/开关，记录 CH 数量、最小/中心/最大值和方向。不执行校准、对频或固件更新。
3. 只接 GR00 供电且飞控仍断开，观察 SDK 接收机链路/原始报告；关闭 GR00 后验证状态变化或报告过期。无飞控遥测是本包预期行为。
4. 点击停止、切到后台、返回前台，确认数值清空且必须手动开始。若超时/异常，记录错误码和系统构建，不反复自动重连。

完整控制交付仍阻塞于源端输入新鲜度、跨设备强制互斥的部署落点、目标身份与GR00双向通信、失联及受控交还真机验证。已通过的隔离 SITL 和仲裁测试保持独立；本 APK 不打开这些能力。

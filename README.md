# G12 MAVLink Control

在云卓 G12（Android 13）上读取摇杆通道，经 GR00 数传向 X9 / ArduCopter 发送有限的 MAVLink 控制帧。当前主线是 **G12 通道直传 0.5.0**：点「连接」后自动识别飞控，把 SDK 返回的通道按原值、原顺序发出。真机端到端和飞行验收都还没做。

## 当前状态

**实验。** 软件主线停在预发布 `v0.5.0-x9-direct`，只有 `main` 一个分支。22 个提交全部落在 2026-10-17，最新提交 `1ffa5b8`（记录 0.5.0 的 APK 与隔离验证证据）。之后没有新提交。

仓库现在是 **public**（2026-10-23 用 `gh repo view` 核对）。旧文档里写的「本项目私有」已经过时。GitHub 仓库 description 仍是 “simulation-only until vendor SDK…”，也和当前代码不符：`bench` / `sender` / `x9` 已经接入官方 RCSDK，只是硬件验收没完成。

| 版本 | 模块 | applicationId | versionName | 角色 |
|---|---|---|---|---|
| 当前主线 | `x9` | `com.g12.x9` | `0.5.0-x9-direct`（versionCode 5） | 全通道原值直传。界面名「G12 通道直传」 |
| 仍在树里、当前界面不用 | `x9-core` 的 `DirectSession` | 同上包名的 0.4 逻辑 | `0.4.0-x9-direct` 预发布 | 校准、档案、物理开关绑定。见 [docs/X9_DIRECT.md](docs/X9_DIRECT.md) |
| 台架发送 | `sender` | `com.g12.bench.sender` | `0.3.1-serial-bench`（versionCode 4） | GR00 只接电脑、逐帧回显。见 [docs/SERIAL_BENCH.md](docs/SERIAL_BENCH.md) |
| 只读台架 | `bench` | `com.g12.bench.readonly` | `0.2.1-sdk-readonly`（versionCode 3） | 只读通道和链路。见 [docs/SDK_BENCH.md](docs/SDK_BENCH.md) |
| 模拟 | `app` | `com.g12.simulator` | `0.1.0-simulation`（versionCode 1） | 进程内模拟，无网络/串口权限 |

0.5.0 预发布（指向提交 `66ee81c`，不是今天的 HEAD）：

- [Release 页面](https://github.com/unununnnn/g12-mavlink-control/releases/tag/v0.5.0-x9-direct)
- [APK](https://github.com/unununnnn/g12-mavlink-control/releases/download/v0.5.0-x9-direct/g12-x9-direct-0.5.0.apk)

2026-10-23 核对过该 Release 为 pre-release，附件 `g12-x9-direct-0.5.0.apk` 存在，大小 5504021 字节，SHA256 `ef7fd4e6a90e5e1ea839a56b9787c26c50077a536b40ec9ff27d4c86a7c31cd4`，与 [docs/evidence/channels-20261017.json](docs/evidence/channels-20261017.json) 一致。这是 CI debug 签名包。证据写明 0.5 的证书和 0.4 不同，安装前要先卸掉旧的 `com.g12.x9`。本次没有下载安装这个 APK。

更早的预发布仍在 Releases：`v0.4.0-x9-direct`、`v0.3.1-serial-bench`、`v0.3.0-serial-bench`、`v0.2.0-sdk-readonly`。

## 功能

目标拓扑保持为：主遥控经主接收机 SBUS 进 X9；G12 经云卓 SDK 和 GR00 透传，走备用串口 MAVLink。备用链路不改成第二路 SBUS。本仓库不改 G12 射频固件，不改飞控固件，也不导入独立的 X9 激活或围栏工程。

**0.5.0 通道直传（当前 App 实际走的路径）**

- 打开后不自动发送。点「连接」才读 SDK 通道，并开始识别链路上的 ArduPilot 心跳。
- SDK 数组第 0 项对应 CH1，按原值、原顺序发送 `RC_CHANNELS_OVERRIDE`（#70）。不做四轴重映射、归一化、反向或端点缩放。PWM 只接受 800–2200；0 和 65535 不会当成普通通道值发出。
- 辅助开关一并发送，可能触发飞控里已经配置好的模式、解锁或其他 RC 功能。App 自己不发解锁、切模式或参数写入。
- 取消首次设置、校准向导、UID 绑定和本地档案。`x9/src/main/java/com/g12/x9/MainActivity.java` 只使用 `ChannelSession`。
- 每次连接重新读心跳。出现第二个飞控就停发。只读查询 `MAV_GCS_SYSID`、`RC_OPTIONS`、`RC_OVERRIDE_TIME`，不写回参数。源 system id 用飞控读到的 `MAV_GCS_SYSID`，源 component 固定为 191。`RC_OPTIONS` 低两位非 0，或覆盖超时不是有限正数时，拒绝开始发送。
- MAVLink 2 最多 18 通道，MAVLink 1 最多 8 通道。超出就报错，不截断。同一次连接里 SDK 通道数变化也会停止。
- 固定的 ArduCopter 4.7.1 只处理覆盖帧的前 16 通道。App 仍按 SDK 数量编码到最多 18 通道；反馈比对只核对前 16。17/18 是否被该固件采用，界面会标明未确认。
- 最高 10 Hz（代码里相邻控制帧间隔至少 100 ms），每个新 SDK 样本最多发一次。本地输入 250 ms、SDK 写调用 200 ms、心跳和 RC 遥测 3 s、反馈未跟随，都会停发。恢复要再点连接。
- 断开只停发，不自动发 release。最后一次覆盖值留给飞控现有的 `RC_OVERRIDE_TIME`。前台断开保留 SDK 读连接；进后台会释放 SDK。
- 无总时长限制，也不用解锁状态当门槛。进程内同时只允许一个 `ChannelSession`。这挡不住另一台地面站或主 SBUS。

**仍保留、但不是当前主界面的能力**

- `app`：`ControlSession` 模拟接管、冻结输入、交还和生命周期停止。输出只进进程内存。`VendorAdapter.hardware()` 固定抛异常。
- `bench`：官方 SDK 只读。状态为 IDLE / CONNECTING / READING / STOPPED。0.2.1 的「停止采集」保留前台 SDK 连接，另有「断开 SDK / 释放端口」。用户自报 0.2.0 只读读通过；101 超时修复还没有真机复测。见 [docs/RESTART_FIX.md](docs/RESTART_FIX.md)。
- `sender`：真实 SDK 管道把帧送到 GR00，电脑用 `lab/serial_echo.py` 逐帧回显。默认停止，要手填映射和 ID，最多一帧在途，窗口最多 10 秒。飞控必须断开。这不是双链路遥控，也不是飞行版本。
- `x9-core` 里的 `DirectSession`、`Calibration`、`Profile`、`ProfileCodec` 仍参与 `DirectTests`。0.5 的 `x9` 源码不再引用它们，也不读旧 SharedPreferences。
- `lab/arbiter.py`：单写者仲裁原型，带跨进程测试。没有接到 APK，也没有部署到真实链路。

## 目录结构

```text
app/                  模拟 APK。入口 com.g12.simulator.MainActivity
bench/                只读 APK。-PwithSkydroid=true 才加入构建
bench-core/           只读会话与启停契约，无 Android、无控制 core
sender/               电脑收包发送 APK。同样要 withSkydroid
sender-core/          映射与 SendEngine；编译时复用 core 的 Mavlink.java
x9/                   0.5.0 通道直传 APK。入口 com.g12.x9.MainActivity
x9-core/              ChannelSession（当前）和 DirectSession（0.4，测试仍覆盖）
core/                 模拟状态机、有限 MAVLink2 编解码、VendorAdapter
sdk-probe/            对照 RCSDK 类型编译的只读探针。不在 settings.gradle 里
lab/                  仲裁原型、串口回显、隔离 SITL 驱动
scripts/              离线回归、拉取 AAR、SITL 构建与运行
docs/                 需求、架构、边界、证据。细节以这里为准
.github/workflows/    verify.yml 随 push/PR；bench.yml、sender.yml、x9.yml 只手动触发
```

根上的构建文件：`settings.gradle`、`build.gradle`、`gradle.properties`。没有 Gradle Wrapper，没有 `LICENSE`。`vendor/`、`*.aar`、`*.apk`、`artifacts/`、`local.properties` 都在 `.gitignore` 里。

`docs/` 里和现状直接相关的说明：

- [docs/CHANNELS.md](docs/CHANNELS.md)：0.5.0 操作、协议和当时的验证记录
- [docs/BUILD_TEST.md](docs/BUILD_TEST.md)：构建与回归入口
- [docs/SITL.md](docs/SITL.md)：固定 4.7.1 隔离仿真
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)、[docs/REQUIREMENTS.md](docs/REQUIREMENTS.md)、[docs/BLOCKERS.md](docs/BLOCKERS.md)
- [docs/SOURCES.md](docs/SOURCES.md)：上游提交和资料核对
- [docs/VALIDATION.md](docs/VALIDATION.md) 与 [docs/evidence/](docs/evidence/)：2026-10-17 的机器可读证据

## 环境与依赖

| 用途 | 要求 |
|---|---|
| 离线 Java 回归 | JDK 17 或更高。脚本用 `javac --release 17`。2026-10-23 在本环境用 OpenJDK 21.0.12 跑通 |
| Python 协议核对 | Python 3。CI 使用 3.10；本次用 3.12.3。需要已安装的 `pymavlink==2.4.49`（`lab/requirements.txt`）。脚本不会自己装包 |
| 串口回显脚本额外依赖 | `lab/serial-requirements.txt`：`pymavlink==2.4.49`、`pyserial==3.5`、`lxml==5.3.0`、`fastcrc==0.3.6` |
| Android | JDK 17、Gradle **8.11.1**、Android Gradle Plugin **8.9.2**、compileSdk / Build Tools **35**。本环境没有 Gradle，也没有 `ANDROID_HOME`，下面的 Gradle 命令本次未执行 |
| 含 SDK 的 APK | 官方 `rcsdk-v1.9.2.aar`，SHA256 `e5977ba3292246bcee026defdb8563a18adc9d5573fdfb20a6880ee469f0fde2`。Kotlin stdlib `1.6.10`。ABI 只有 `armeabi-v7a` 和 `arm64-v8a` |
| 隔离 SITL | Linux 上的 g++、make、git、Python 3.10+、JDK 17+、`iproute2`/`unshare`，以及 `lab/requirements.txt`。本次未重新编译 ArduPilot |

目标硬件，按仓库文档和版本号：

- G12，用户确认 Android 13。`x9` 的 minSdk/targetSdk 都是 33；`bench` 和 `sender` 的 minSdk 是 23、targetSdk 是 33。具体系统构建号、ABI 实测仍空着。官方 Demo 写 G12/G20 数传管道要求厂商系统构建 20250110 或更新；本仓库没有核实用户机器是否满足。
- GR00 透传口，不是 SBUS 针。电气电平、底板和波特率要按实际模块核对，App 不改这些设置。见 [docs/GR00_REVIEW.md](docs/GR00_REVIEW.md)。
- 飞控行为对照的是上游 **ArduCopter 4.7.1**，不是本仓库里的一份固件。

SDK 来源是 [skydroid/rcsdk-demo](https://gitee.com/skydroid/rcsdk-demo) 提交 `d9c6bcab8bb9993cd16f117c01a7601dce10ea6a`。`scripts/fetch_skydroid.sh` 从该提交取出 `app/libs/rcsdk-v1.9.2.aar` 到 `vendor/rcsdk-v1.9.2.aar` 并核对 SHA256。AAR 不进 Git。公开/商业再分发范围，仓库没有下结论。

## 上游以及本仓库改了什么

这不是 PX4、ArduPilot 或 ego-planner 的分叉，仓库里也没有它们的源码。

- **ArduPilot**：SITL 和语义对照固定在上游 `ArduPilot/ardupilot` 的 Copter-4.7.1，提交 `dbe792162d06cab66c3475fd5556bf7a120f119e`。`scripts/build_sitl.sh` 克隆的是 `https://github.com/ArduPilot/ardupilot.git`，并检查工作树没有被修改。本仓库不打飞控补丁。`core` / `x9-core` 是手写的小范围无签名编解码，用 pymavlink 2.4.49 对向量，没有把上游 C++ 拷进来。
- **云卓 RCSDK**：构建时按上面的固定提交取 AAR。`bench`、`sender`、`x9` 按官方空白工程的集成方式调用 `KeyChannels` 和 `createG12G20Pipeline`。没有改 SDK 源码，也没有把 Demo 工程并进本仓库。`sdk-probe` 只用来对官方 class 签名做编译检查。
- **本仓库自己的部分**：四个 Android 应用、Java 会话/协议、Python 仲裁与 SITL 驱动、CI 和 `docs/`。

账号下另有公开仓库 `unununnnn/ardupilot`，它是 `ZeroOne-Aero/ardupilot` 的 fork（默认分支 `master`，最近推送 2026-08-18）。本仓库的 SITL 脚本不使用那个 fork，文档也要求不要拿现成的 X9 工作树来当被测固件。

## 构建与运行

模拟包不需要厂商 AAR。仓库没有 Wrapper，要自己使用 Gradle 8.11.1，并设置 `ANDROID_HOME`，或在被忽略的 `local.properties` 里写 `sdk.dir`。

```text
gradle --no-daemon :core:check :app:assembleDebug :app:lintDebug
```

产物路径按 Android 插件的默认规则是 `app/build/outputs/apk/debug/app-debug.apk`。CI workflow `Verify Android and isolated SITL`（`.github/workflows/verify.yml`）用的就是这条命令。**本次环境未执行。**

含 SDK 的三个包要先拿到校验过的 AAR，并加上 `-PwithSkydroid=true`（否则 `settings.gradle` 不 include `:bench`、`:sender`、`:x9`）。AAR 路径可用 `-PskydroidAar=` 覆盖，默认是各模块眼中的 `../vendor/rcsdk-v1.9.2.aar`。

```text
bash scripts/fetch_skydroid.sh
gradle --no-daemon -PwithSkydroid=true :x9-core:check :x9:assembleDebug :x9:lintDebug
```

对应 workflow 里的另外两条，同样 **本次未执行**：

```text
gradle --no-daemon -PwithSkydroid=true :bench-core:check :bench:assembleDebug :bench:lintDebug
gradle --no-daemon -PwithSkydroid=true :sender-core:check :sender:assembleDebug :sender:lintDebug :bench:assembleDebug :bench:lintDebug
```

`x9` 的 debug APK 默认在 `x9/build/outputs/apk/debug/x9-debug.apk`。`x9.yml` 把它复制成 `artifacts/x9/g12-x9-direct-0.5.0.apk` 后再跑 `scripts/verify_x9.py`。`bench`、`sender` 的检查脚本是 `scripts/verify_bench.py`、`scripts/verify_sender.py`。这三个 workflow 都是 `workflow_dispatch`，push 不会自动跑。

Windows 上的 SDK 签名探针（需要仓库外的 AAR，不执行 SDK）：

```text
./scripts/check_sdk_signatures.ps1 -SdkAar <rcsdk-v1.9.2.aar 的绝对路径>
```

**未在本次 Linux 环境执行。**

电脑收包只属于 `sender`，而且 UART 必须接电脑、飞控断开。参数来自 `lab/serial_echo.py` 的 argparse；端口、波特率和 ID 没有默认值，下面的尖括号都要换成现场核对过的值。**本次没有打开任何串口。**

```text
python3 lab/serial_echo.py \
  --port <PORT> --baud <BAUD> \
  --source-system <1-255> --source-component <1-255> \
  --target-system <1-255> --target-component <1-255> \
  --message 69 \
  --flight-controller-disconnected
```

`--message` 只能是 `69`（`MANUAL_CONTROL`）或 `70`（`RC_CHANNELS_OVERRIDE`）。需要本机已安装 pyserial 和 pymavlink。

## 测试 / 仿真

**2026-10-23 已在本环境重新跑通**（JDK 21 按 `--release 17` 编译，Python 3.12.3，pymavlink 2.4.49）：

```text
bash scripts/test.sh
python3 scripts/check_vectors.py
python3 -m unittest lab.test_arbiter lab.test_x9_wire lab.test_serial_echo -v
```

`scripts/test.sh` 依次编译并运行 `CoreTests`、`ReadOnlySessionTest`、`RetainedReadBackendTest`、`SenderTests`、`DirectTests`、`ChannelTests`。当次输出是 597、21、57、158、2453、18003 条断言，全部 PASS，并写出 `artifacts/vectors.json`、`artifacts/x9-vectors.json`、`artifacts/channel-vectors.json`、`artifacts/sender-vectors.json`。后两个 Python 模块要先有这些文件。`check_vectors.py` 对 core 的四类消息做了逐字节核对。三个 unittest 模块合计 15 项，全部 OK。`lab.test_arbiter` 只用标准库。

`scripts/test.ps1` 覆盖同一组 Java 测试。原作者机器上的记录把 `JAVA_HOME` 设为 `D:\install\stmcube\jre`；那是那台 Windows 的路径。脚本在没设 `JAVA_HOME` 时走 PATH 上的 `javac`/`java`。**本次没有跑 PowerShell。**

**隔离 SITL，本次未重新执行。** 脚本和 CI 定义在仓库里；通过记录在 `docs/evidence/`，不是这次重跑的结果。Linux 上需要能 `unshare` 出新的 network namespace（脚本检查自己不是 PID 1 的网络命名空间，并且只留下 lo）。文档中的重现命令是：

```text
bash scripts/build_sitl.sh "$HOME/g12-copter-4.7.1"
sudo env "PATH=$PATH" unshare --net -- bash scripts/run_sitl.sh "$HOME/g12-copter-4.7.1"
sudo unshare -n bash scripts/run_x9_sitl.sh /root/g12-sitl-4.7.1 artifacts/channel-sitl --channels
```

第一条构建固定提交的 stock SITL，不打补丁。第二条跑 `lab/sitl_e2e.py`（disarmed，只允许写 `SIM_RC_FAIL`）。第三条编译 `x9-core` 并用 `ChannelSitlHarness` 对上自启动的 SITL；`--channels` 走 0.5 的全通道会话，不加这个参数则走 0.4 的 `SitlHarness`。`run_x9_sitl.sh` 里的 javac 使用 `--release 11`，注释写明是为了迁就当时的 WSL JDK；主回归仍然是 `--release 17`。

`docs/evidence/channels-20261017.json` 记录：通道 SITL 在提交 `816b5b1` 上 PASS，收到 130 个覆盖帧，固件采用前 16 通道；安全参数前后一致；`hardware_end_to_end_verified` 和 `flight_validated` 都是 false。`docs/VALIDATION.md` 记录更早的 stock SITL 为 16/16 PASS（本地与 [CI 39015334397](https://github.com/unununnnn/g12-mavlink-control/actions/runs/39015334397)）。这些链接和 JSON 是仓库里的既有证据，本次没有重放 CI。

## 开发说明

- 分支：远程只有 `main`。没有 `CONTRIBUTING` 或另外的分支约定。提交说明是英文；2026-10-17 后段提交带 `[skip ci]`，所以 tip 上的证据提交没有再跑 `verify.yml`。
- 当前控制入口：`x9-core` 的 `ChannelSession`，Android 侧是 `x9` 的 `MainActivity` 和 `SdkLink`。`SdkLink` 实现 `ChannelSession.Link`，调用 RCSDK 的 G12/G20 pipeline。
- 0.4 入口仍保留在 `DirectSession`，由 `DirectTests` 和 `SitlHarness` 使用。
- 模拟入口是 `core/ControlSession` 和 `app` 的 `MainActivity`。协议实现是 `core/Mavlink`，只含 HEARTBEAT(0)、MANUAL_CONTROL(69)、RC_CHANNELS_OVERRIDE(70)、AUTOPILOT_VERSION(148)。
- 0.5 的协议实现是 `x9-core/Wire`。发送路径用到 PARAM_REQUEST_READ(20)、RC_CHANNELS_OVERRIDE(70)、COMMAND_LONG(76，只用来请求消息 148 和 65)。解码还认 HEARTBEAT(0)、PARAM_VALUE(22)、RC_CHANNELS(65)、COMMAND_ACK(77)、AUTOPILOT_VERSION(148)。签名帧直接拒绝。
- 配置不在仓库里的配置文件中。0.5 不保存通道档案。Gradle 侧只有 `gradle.properties` 里的 JVM 参数和 `android.useAndroidX=false`。SDK 开关是 `-PwithSkydroid` 和 `-PskydroidAar`。SITL 目录是脚本参数；可选环境变量 `G12_SOURCE_CACHE`（只读对象源）和 `G12_BUILD_JOBS`（默认 4）。
- `sender` 的映射、波特率和 ID 在 App 里当场填写，重启不保留发送确认。

## 已知问题和 TODO

这些是代码和现有文档里已经写明、尚未关闭的缺口，不是新的路线图。

- G12、GR00、X9 的端到端发送没有做过。没有飞行验收，也没有在本仓库里安装 APK、解锁或刷写。
- SDK `code=101` 的停止后再读超时，0.2.1 / 0.3.1 有软件回归，真机是否消失还没复测。0.5 沿用同一套前台连接保留逻辑。
- SDK 回调时间不能证明摇杆采样是新的。`writeData` 返回 void，不能当成 UART 发完或飞控 ACK。
- 跨设备只能有一个控制源这件事还没落地。`lab/arbiter.py` 的租约是实验原型（`LEASE_MS = 2000`），APK 里的进程内 owner 挡不住另一台 GCS 或主 SBUS。
- 固定 4.7.1 不采用第 17、18 通道。辅助通道会把飞控上已有的 RC 功能一起带出去。
- 断开后不发 release，覆盖值会留到飞控自己的 `RC_OVERRIDE_TIME`。停发不能解释成主 SBUS 已经接回。
- GR00 的实际底板、逻辑电平和波特率仍要现场核对。手册上的 5 V 供电不能推出 UART 电平。
- 厂商 SDK 没有随仓库声明可再分发的许可证。AAR 只在构建机上出现。
- 0.5 与 0.4 的 CI debug 证书不同，覆盖安装会失败，需要先卸载。
- 没有 Gradle Wrapper。Android 构建和 SITL 在本次写 README 的环境里没有重跑。
- 仓库 description，以及 `docs/ACCEPTANCE.md` 等旧段落里的「私有仓库」表述，还停在更早的阶段。

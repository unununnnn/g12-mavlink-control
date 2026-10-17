# G12 MAVLink Control

**当前开发交付：G12 通道直传 0.5.0。** 已取消首次设置、校准和X9绑定。点击连接后自动识别飞控，把SDK返回的全部通道（含辅助开关）按原值、原顺序通过MAVLink发送；无需保存档案或指定接管开关。无总时长及解锁状态限制，保留输入/链路失效停发。见[全通道直传说明](docs/CHANNELS.md)。尚未完成真机或飞行验收。

[下载0.5.0 APK](https://github.com/unununnnn/g12-mavlink-control/releases/download/v0.5.0-x9-direct/g12-x9-direct-0.5.0.apk) · [预发布与校验报告](https://github.com/unununnnn/g12-mavlink-control/releases/tag/v0.5.0-x9-direct)。本项目私有，下载需对应GitHub访问权限。MAVLink2可编码18通道；固定Copter4.7.1接收实现仅采用前16通道，App明确区分发送与接收采用。

**启停修复候选版：sender 0.3.1 / readonly 0.2.1。** 用户定位到“停止后再次读取出现 SDK code=101 timeout”。前台停止采集现在保留 SDK 连接，恢复时等旧请求结束后发起新 GET；释放端口和后台仍完整断开。软件回归通过，G12 实测待确认。见[启停修复与复测步骤](docs/RESTART_FIX.md)。

**此前台架交付：**用户自报只读版测试成功后，新增独立 `sender` **真实SDK串口台架发送版**。仅用于GR00 UART接电脑收包/逐帧回显，飞控断开；显式映射、启停、最多一帧在途和10秒窗口。不是完整双链路遥控或飞行版本。见[发送版说明](docs/SERIAL_BENCH.md)。

G12 Android 应用与**隔离 SITL 开发项目**，目标为云卓 SDK 读取真实摇杆，经 GR00 数传向 X9 / ArduCopter 4.7.1 传递 MAVLink 控制。新增独立 `bench` 真正集成官方 RCSDK1.9.2，面向用户确认的 Android13 G12，读取通道与链路信息；**仅台架只读，未真机验收，不是完整遥控版**。原 `app` 仍是独立模拟包。见 [SDK台架APK与兼容边界](docs/SDK_BENCH.md)。

主链路为原主接收机 SBUS RC + 串口双向数据；备用链路为串口 MAVLink 控制，绝不改作第二 SBUS 接收机。不改 G12 射频固件，不改飞控固件，不导入独立 X9 激活/围栏工程。

## 交付内容

- `x9` / `x9-core`: G12全通道原值直传、一键连接、自动目标/接收配置识别及启停/故障测试；保留0.4历史核心回归，当前App不再使用绑定档案。
- `core`: 无 Android 依赖的 Java 协议、流解析、目标识别及模拟接管状态机。
- `app`: Android 原生界面，显式启动模拟、模拟接管、冻结输入、交还与生命周期停止；无网络/串口权限。
- `bench` / `bench-core`: 官方SDK只读Android应用、异步会话契约与故障回归；无飞控数传管道或控制core依赖。
- `sdk-probe`: 按官方RCSDK1.9.2真实类型编译核对的原创只读请求类，未集成APK、未执行SDK、输出不具控制授权效力。
- `scripts/test.ps1` / `scripts/test.sh`: JDK 17+ 离线回归，无第三方 Java 测试依赖。
- `scripts/check_vectors.py`: 与已安装 pymavlink 独立交叉验证协议向量。
- `lab`: 单写者仲裁原型、独立进程竞争测试，以及仅能在loopback网络namespace运行的固定4.7.1 SITL端到端测试。
- `docs`: 需求、架构、SDK 边界、来源、构建、验收与风险。
- GitHub Actions: 核心/仲裁回归、Android debug APK与独立SITL构建/测试，保留模拟APK与测试报告。

## 快速验证

```powershell
$env:JAVA_HOME = 'D:\install\stmcube\jre'
./scripts/test.ps1
python ./scripts/check_vectors.py
```

Android 构建需要 JDK 17、Gradle 8.11.1、Android SDK Platform 35 / Build Tools 35.0.0，详见 [构建与测试](docs/BUILD_TEST.md)。未附带第三方 SDK、签名私钥、凭据或生产配置。

**完整控制的硬件阻塞**：仍须确认具体系统构建、真实源端新鲜度、GR00电气/数据行为、目标UID和能够强制排他的仲裁落点。只读集成按官方空白项目集成指引实施，公开/商业再分发范围未确认。模拟Android控制帧仅进入进程内存；独立lab只向自己启动的隔离SITL进程发送。[SITL复现与边界](docs/SITL.md)、[仲裁设计](docs/ARBITRATION.md)、[阻塞与最小补充信息](docs/BLOCKERS.md)。

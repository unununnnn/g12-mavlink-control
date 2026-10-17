# G12 MAVLink Control

G12 Android 应用的**模拟开发基线**，目标为云卓 SDK 读取真实摇杆，经 GR00 数传向 X9 / ArduCopter 4.7.1 传递 MAVLink 控制。当前没有真实 SDK 适配，也没有硬件写出能力，不能用于飞行。

主链路为原主接收机 SBUS RC + 串口双向数据；备用链路为串口 MAVLink 控制，绝不改作第二 SBUS 接收机。不改 G12 射频固件，不改飞控固件，不导入独立 X9 激活/围栏工程。

## 交付内容

- `core`: 无 Android 依赖的 Java 协议、流解析、目标识别及模拟接管状态机。
- `app`: Android 原生界面，显式启动模拟、模拟接管、冻结输入、交还与生命周期停止；无网络/串口权限。
- `scripts/test.ps1` / `scripts/test.sh`: JDK 17+ 离线回归，无第三方 Java 测试依赖。
- `scripts/check_vectors.py`: 与已安装 pymavlink 独立交叉验证协议向量。
- `docs`: 需求、架构、SDK 边界、来源、构建、验收与风险。
- GitHub Actions: 核心回归和 Android debug APK 构建，产物只包含模拟 APK。

## 快速验证

```powershell
$env:JAVA_HOME = 'D:\install\stmcube\jre'
./scripts/test.ps1
python ./scripts/check_vectors.py
```

Android 构建需要 JDK 17、Gradle 8.11.1、Android SDK Platform 35 / Build Tools 35.0.0，详见 [构建与测试](docs/BUILD_TEST.md)。未附带第三方 SDK、签名私钥、凭据或生产配置。

**硬件阻塞**：须取得可授权使用的 G12 SDK、匹配固件版本和文档，确认摇杆有效/失联语义、GR00 发送接口、目标 UID、主备仲裁和飞控 failsafe，再单独设计硬件构建。当前所有控制帧只进入进程内存。

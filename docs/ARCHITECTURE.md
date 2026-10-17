# 架构与安全决策

```text
真实目标（尚未连接）:
主遥控 -> 主接收机 --SBUS--> X9 RC
                     --串口<-> X9 MAVLink
G12 Android -> 云卓 SDK -> GR00 备用数传 --串口<-> X9 MAVLink

当前可执行:
模拟输入 -> ControlSession -> MAVLink MANUAL_CONTROL -> SimulationLink(内存)
模拟 HEARTBEAT + AUTOPILOT_VERSION -> 目标校验 -> 模拟会话
```

`core` 与 Android 解耦。SDK 契约是本项目自有接口，不是厂商 API。Android/core仅内置内存输出；硬件工厂始终拒绝创建。公开控制入口不提供任意字节发送。独立 `lab` 目录新增进程外SITL实验，具备独立network namespace和自启动子进程限制，不接入APK。

状态：READ_ONLY -> SIMULATION_READY -> ACTIVE -> HANDING_BACK -> SIMULATION_READY；任何失联、输入过期、时钟回退、写入异常或目标冲突 -> STOPPED。STOPPED 无自动恢复，必须显式新开模拟会话，清空输入和身份信息。`onPause` 停止并清空，不在后台维持控制。

每帧发送前检查：会话代次、目标已识别、心跳新鲜、固件/UID 相符、输入时间递增且在时限内、deadman、本实例唯一 owner、50ms 最小间隔。非有限/越界/重复时间戳输入被拒绝并停止。默认输入阈值 150ms、心跳阈值 1500ms 只用于模拟；不能作为 RF 链路已验证指标。

MAVLink 2 支持范围仅 HEARTBEAT(0)、MANUAL_CONTROL(69)、RC_CHANNELS_OVERRIDE(70，仅交还帧)、AUTOPILOT_VERSION(148)。CRC-X25 + CRC_EXTRA，截短 payload 零扩展，流分片/粘包，错误 CRC 丢弃。签名帧没有密钥验证能力，直接拒绝，不能把 CRC 当认证。

模拟交还必须主动请求，并提供当前会话内、100ms 内新鲜的主控恢复确认；随后仅释放 1-4 通道，其余 5-8 使用 ignore，9-18 扩展不发送（表示 ignore）。进入交还状态后不再输出摇杆帧。释放不具有 COMMAND_ACK；当前模拟确认只说明流程，真实交还必须验证主 SBUS 有效、主操作者确认、通道实际恢复和超时行为。输入过期/失联不盲目发送释放帧或中位值；立即停止写出，让已配置的飞控超时机制处理。

单进程租约无法阻止另一台地面站或主链路同时发送。[单写者仲裁原型](ARBITRATION.md)验证了统一权威下的多进程请求，但真实部署仍需强制覆盖所有控制入口。原版飞控不会仅凭相同sysid给某个设备独占权。RC Override Enable可提供额外主控门控，不能单独替代身份/租约协议。MAVLink MANUAL_CONTROL 本身不提供独占接管 ACK；在此问题关闭前硬件发送继续禁止。

不自动调整 MAV_GCS_SYSID、MAV_OPTIONS、RC_OPTIONS、RC_OVERRIDE_TIME、RC_FS_TIMEOUT、GCS/RC failsafe、FENCE_* 或模式。4.7.1 的 MANUAL_CONTROL 同时刷新 GCS seen 时间，持续发送陈旧值会掩盖失联，因此陈旧输入必须停止整个写出，不能只降为中位或补发心跳。

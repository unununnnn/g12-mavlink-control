# 本地验证记录

2026-10-17 UTC，Windows PC。

命令：`$env:JAVA_HOME='D:\install\stmcube\jre'; ./scripts/test.ps1`；实际运行 Temurin21.0.3，`javac --release 17 -Xlint:all -Werror`。

```text
PASS: 597 assertions; protocol, fault injection and simulation safety. No hardware accessed.
```

随后运行 `python scripts/check_vectors.py`（Python3.13.11 / 已安装 pymavlink2.4.49）：

```text
PASS manual: independent byte-for-byte encoder + decoder (23 bytes)
PASS release: independent byte-for-byte encoder + decoder (30 bytes)
PASS heartbeat: independent byte-for-byte encoder + decoder (21 bytes)
PASS version: independent byte-for-byte encoder + decoder (32 bytes)
pymavlink 2.4.49
```

这是 core 实际 Java 实现和独立 Python 协议实现的核对，不是 Android/G12/SITL 运行测试。

Android在 [GitHub Actions39013291734](https://github.com/unununnnn/g12-mavlink-control/actions/runs/39013291734) 完成 core回归、`:core:check :app:assembleDebug :app:lintDebug`，结论success。输入commit `bee13c34ba97a433bb50dd7156bcf6c6c0a5e1e9`，已用git ls-remote确认与本机一致。模拟APK已下载到本机独立项目 `artifacts/ci-39013291734/app/build/outputs/apk/debug/app-debug.apk`，22664字节，SHA256 `B7CEC83FC4079337AACC691A01B0217748B001D1C4C2370F9BEA2E114D09A005`。后续SDK只读源码及文档不改变该APK；不同提交的APK应以各自CI记录对应。

原创 `sdk-probe/ReadOnlyChannels.java` 对官方RCSDK1.9.2 AAR（哈希见SDK_ADAPTER）的实际class签名编译通过。未运行SDK，不将它集成模拟APK，不声称真实输入有效。

dot项目：https://chatgpt.com/g/g-p-6ad322080120819199f6ba942858ead5/project 。项目来源已保存仓库链接、目录、证据和约束；现有GitHub连接器get_repo成功读取新仓库并返回pull/push可用，没有新增OAuth授权。项目来源不是Git工作目录挂载，也不保证后台同步。

上述初期工作没有访问真实飞控或发送真实控制帧。真机和Android界面运行仍未验收；SITL新增结果如下。

## 固定4.7.1 SITL与仲裁

2026-10-17，本机Ubuntu22.04 WSL / g++11.4 / Python3.10 / pymavlink2.4.49。独立构建根为 `/root/g12-sitl-4.7.1`；来源固定 `dbe792162d06cab66c3475fd5556bf7a120f119e`，跟踪文件干净。未使用X9旧工程的工作文件或二进制。

实际命令：

```text
wsl -d Ubuntu-22.04 -- unshare --net -- bash /mnt/c/Users/PC/Documents/ChatGPT/g12-mavlink-control/scripts/run_sitl.sh /root/g12-sitl-4.7.1
python -m unittest lab.test_arbiter -v
```

本地SITL **16/16 PASS**，仲裁 **8/8 PASS**。原Java **597条断言**和独立协议 **4类逐字节向量**仍通过。故意在非隔离网络启动时，驱动在创建飞控子进程前拒绝，记录 `Refuse network access: interfaces other than lo exist`。

关键实测：

| 项目 | 观察 |
|---|---|
| Java MANUAL_CONTROL | 飞控四轴读回2000/2000/1500/1375，与输入映射一致 |
| Java release | 恢复模拟主输入1450/1550/1000/1500 |
| 第二串口、相同sysid | 可替换第一端的输入；原版飞控不提供独占权 |
| 停止覆盖、主RC仍正常 | 3.004s后恢复；参数RC_OVERRIDE_TIME=3.0 |
| 主RC无脉冲 / 协议failsafe位 | SIM_RC_FAIL=1/3均使RC健康位变为不健康 |
| 主RC仍失效，继续有效覆盖 | RC健康位重新变健康；它不能证明原始SBUS恢复 |
| 主RC失效时释放 | 不能恢复主RC健康；不应宣告交还成功 |
| 仲裁输出 | 拒绝第二设备；输入过期后锁存、停止新帧；显式交还后不自动重取 |

安全参数前后完全一致：ARMING_SKIPCHK=0、FS_THR_ENABLE=1、FS_GCS_ENABLE=0、FS_OPTIONS=16、FENCE_ENABLE=0、FENCE_ACTION=1、RC_OVERRIDE_TIME=3、RC_FS_TIMEOUT=1、RC_OPTIONS=32、MAV_GCS_SYSID=255、MAV_GCS_SYSID_HI=0、SIM_RC_FAIL最终恢复0。这是全新stock SITL默认配置，**不代表用户X9配置；没有验证启用围栏/GCS failsafe后的飞行动作**。全过程心跳检查保持disarmed；主输入是模拟UDP PWM，未验证SBUS电气或真实射频。

本地原始报告 `artifacts/sitl/report.json` 和运行日志 `sitl.log` 包含实际构建、schema及脚本SHA256。该次运行在979361d之后的修正工作树进行，报告project_head是父提交，脚本哈希标识实际运行文件；后续CI按完整提交验证。二进制SHA256为 `6ea99f2e9b33bc57e4a74a37c6064c166af31efa1ff90f51354f79a606b1ad22`。可复现步骤、固定版本差异和限制见[SITL.md](SITL.md)。

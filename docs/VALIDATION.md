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

没有访问飞控、启动遥测路由或发送真实控制帧。真机、SITL和Android界面运行未验收。

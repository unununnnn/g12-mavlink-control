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

这是 core 实际 Java 实现和独立 Python 协议实现的核对，不是 Android/G12/SITL 运行测试。Android 编译/lint、APK与远端 commit 结果待 GitHub Actions 返回后追加。没有访问飞控、启动遥测路由或发送真实控制帧。

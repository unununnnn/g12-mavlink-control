# 来源核对记录（2026-10-17 UTC）

## 公开一手资料

1. [云卓官方网站](https://www.skydroid.xin/)：可访问，SPA 页面；不是 SDK 接口文档。官网产品名不能证明 SDK 类名或签名。检查到官方前端入口 `/static/js/index-6e7c9923.js`，未取得已核实 G12 SDK 版本或接口，因此未实现厂商 API 调用。
2. [厂商 G12 产品页](https://skydroidglobal.com/products/skydroid-g12-remote-controller) 和 [技术支持](https://skydroidglobal.com/pages/skydroid-support)：产品与支持入口，本次可见内容不能提供可验证的摇杆/串口 SDK 方法。
3. [MAVLink 序列化规范](https://mavlink.io/en/guide/serialization.html) 与 [消息定义](https://mavlink.io/en/messages/common.html#MANUAL_CONTROL)：v2 帧头、CRC_EXTRA、截短规则和 MANUAL_CONTROL 字段。实现仅涵盖文档所列4类消息，不声称是完整 MAVLink 栈。
4. [ArduPilot RC Input 文档](https://ardupilot.org/dev/docs/mavlink-rcinput.html)：描述 MAVLink 覆盖普通 RC、停止输入后的超时恢复、输入缩放和门控。该在线文档可变化，具体行为以下方固定版本源码为准。没有采用文档中禁用 failsafe 的示例。
5. [Copter-4.7.1 GCS_Common.cpp](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/GCS_MAVLink/GCS_Common.cpp)：tag 经 GitHub API 核对，commit `dbe792162d06cab66c3475fd5556bf7a120f119e`。`handle_manual_control` 检查发送方 GCS sysid、目标 sysid，并调用 `sysid_mygcs_seen`。只参考行为，没有复制 GPL 飞控代码。
6. [Android AGP 8.9 兼容表](https://developer.android.com/build/releases/agp-8-9-0-release-notes)：AGP 8.9.2，Gradle 8.11.1，JDK17，最高 compile SDK35，Build Tools35.0.0。

## 本地证据（仅只读，不再分发）

- `G12-遥控器使用说明.pdf`，本机 WPS 云盘体系文件目录，44页；PDF 技能/pypdf 提取核对。第2页说明 Android13 64位并提到 SDK 开发包，第6/12/15页介绍串口/接线，第19页列 GR01/G12 RX。**这不是 SDK 方法文档，也没有证明用户 GR00 与其兼容。** 手册未附入仓库。
- Documents/Downloads/Desktop 按 G12、GR00、云卓、Skydroid、AAR 搜索，未发现相关工程或 SDK 包；`ChatGPT/图传系统` 只发现 `.git`，未取用任何代码。未遍历私密 sessions 或读取凭据内容。
- 复用 `D:/install/stmcube/jre` 的 Temurin21.0.3，按 `--release 17` 编译。常见路径与 D: 开发工具路径未发现 Gradle、Android platform 或 G12 AAR。不是全盘不存在的证明。
- 已安装 pymavlink2.4.49，`dialects.v20.common`。AUTOPILOT_VERSION 实际序列化为 capabilities@0、uid@8、flight_sw_version@16，避免按 XML 声明顺序错误拼包。Java 输出与该独立实现逐字节一致。

## 仍不可推断

G12 SDK 版本、原始摇杆有效位/采样时间、GR00 串口方法和电平、射频失联时是否重播、X9 实际 UID/厂商补丁均未知。模拟中的 UID 和 sysid 是明确的合成值，不能拿去识别实机。

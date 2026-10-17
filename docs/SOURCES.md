# 来源核对记录（2026-10-17 UTC）

## 公开一手资料

1. [云卓官方网站](https://www.skydroid.xin/)：SPA 页面；沿其公开下载中心前端 `/static/js/index-687a9938.js` 找到明确标注支持 G12 的 RCSDK 链接。前端调用的公开资料目录 `/api/portal/dataFiles/getDetailsTreeList` 列有 G12 产品说明书V2.1（2026-09-21）及 GR00 原理图/PIN脚/规格说明书V1.0（2026-09-14）。这些资料未再分发到仓库。
2. [厂商 G12 产品页](https://skydroidglobal.com/products/skydroid-g12-remote-controller) 和 [技术支持](https://skydroidglobal.com/pages/skydroid-support)：产品与支持入口，本次可见内容不能提供可验证的摇杆/串口 SDK 方法。
3. [MAVLink 序列化规范](https://mavlink.io/en/guide/serialization.html) 与 [消息定义](https://mavlink.io/en/messages/common.html#MANUAL_CONTROL)：v2 帧头、CRC_EXTRA、截短规则和 MANUAL_CONTROL 字段。实现仅涵盖文档所列4类消息，不声称是完整 MAVLink 栈。
4. [ArduPilot RC Input 文档](https://ardupilot.org/dev/docs/mavlink-rcinput.html)：描述 MAVLink 覆盖普通 RC、停止输入后的超时恢复、输入缩放和门控。该在线文档可变化，具体行为以下方固定版本源码为准。没有采用文档中禁用 failsafe 的示例。
5. [Copter-4.7.1 GCS_Common.cpp](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/GCS_MAVLink/GCS_Common.cpp)：tag 经 GitHub API 核对，commit `dbe792162d06cab66c3475fd5556bf7a120f119e`。`handle_manual_control` 检查发送方 GCS sysid、目标 sysid，并调用 `sysid_mygcs_seen`。只参考行为，没有复制 GPL 飞控代码。
6. [Android AGP 8.9 兼容表](https://developer.android.com/build/releases/agp-8-9-0-release-notes)：AGP 8.9.2，Gradle 8.11.1，JDK17，最高 compile SDK35，Build Tools35.0.0。
7. [官方RCSDK-Demo](https://gitee.com/skydroid/rcsdk-demo)，本地固定commit `d9c6bcab8bb9993cd16f117c01a7601dce10ea6a`：README、HomeActivityForJava.java、ReadRCButtonHelper.java、app/build.gradle及AAR公开签名核对。版本1.9.2，AAR SHA256 `E5977BA3292246BCEE026DEFDB8563A18ADC9D5573FDFB20A6880EE469F0FDE2`。G12采用KeyChannels GET；Pipeline写接口返回void，不能当成飞控接收ACK。README记录SDK1.7.3的G12/G20管道改UDP且有系统版本条件。没有LICENSE文件，Gitee也提示未声明许可证；不把厂商源码/AAR纳入本仓库。
8. 下载中心的 G12产品说明书V2.1 已只读提取，共113页，PDF第56页/正文第51页再次明确链接RCSDK-Demo；与官网前端链接一致。GR00资料包已只读解析（3956431字节），其中手册正文为V1.1、4页；透传/SBUS针脚核对与未关闭电气边界见[GR00_REVIEW.md](GR00_REVIEW.md)。没有据5V模块供电推断UART电平。

## 本地证据（仅只读，不再分发）

- `G12-遥控器使用说明.pdf`，本机 WPS 云盘体系文件目录，44页；PDF 技能/pypdf 提取核对。第2页说明 Android13 64位并提到 SDK 开发包，第6/12/15页介绍串口/接线，第19页列 GR01/G12 RX。**这不是 SDK 方法文档，也没有证明用户 GR00 与其兼容。** 手册未附入仓库。
- Documents/Downloads/Desktop 按 G12、GR00、云卓、Skydroid、AAR 搜索，未发现相关工程或 SDK 包；`ChatGPT/图传系统` 只发现 `.git`，未取用任何代码。未遍历私密 sessions 或读取凭据内容。
- 复用 `D:/install/stmcube/jre` 的 Temurin21.0.3，按 `--release 17` 编译。常见路径与 D: 开发工具路径未发现 Gradle、Android platform 或 G12 AAR。不是全盘不存在的证明。
- 已安装 pymavlink2.4.49，`dialects.v20.common`。AUTOPILOT_VERSION 实际序列化为 capabilities@0、uid@8、flight_sw_version@16，避免按 XML 声明顺序错误拼包。Java 输出与该独立实现逐字节一致。

## 仍不可推断

已确认公开 SDK 版本和部分调用签名；原始摇杆有效位/采样时间、GR00实际电平/固件匹配、射频失联是否重播、X9 实际 UID/厂商补丁仍未知。模拟中的 UID 和 sysid 是明确的合成值，不能拿去识别实机。

## 本阶段固定源码补充

以下均为 `dbe792162d06cab66c3475fd5556bf7a120f119e`，从独立git对象检出验证，没有取用X9工作树修改：

- [GCS.cpp](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/GCS_MAVLink/GCS.cpp)：实际参数 `MAV_GCS_SYSID` 默认255、`MAV_GCS_SYSID_HI` 默认0；范围匹配不是设备认证。旧 `SYSID_MYGCS` 在Copter参数迁移表中转换到新名字。
- [RC_Channel.cpp](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/RC_Channel/RC_Channel.cpp) 和 [RC_Channels_VarInfo.h](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/RC_Channel/RC_Channels_VarInfo.h)：覆盖优先、每通道时间戳与默认3.0s超时；覆盖启用辅助通道本身不能被GCS覆盖。
- [AP_RCProtocol_UDP.cpp](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/AP_RCProtocol/AP_RCProtocol_UDP.cpp)：SITL接收16/32字节的8/16路PWM数据；每20ms模拟一次输入，`SIM_RC_FAIL`定义见SITL.h；接收器绑定0.0.0.0，所以本实验强制独立network namespace。
- [radio.cpp](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/ArduCopter/radio.cpp)：RC输入更新、覆盖输入与失效状态处理。输入观察与飞行failsafe动作必须分开验收。
- [AP_Arming.cpp](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/AP_Arming/AP_Arming.cpp)：4.7迁移CHECK到SKIPCHK；`ARMING_SKIPCHK=0`是不跳过检查，不是禁用全部检查。
- [SITL_State.h](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/AP_HAL_SITL/SITL_State.h) 与 [SITL_cmdline.cpp](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/AP_HAL_SITL/SITL_cmdline.cpp)：serial9选项与实际9项数组边界不一致；本项目只使用0–8，未修改上游代码。

实际运行证据以 `docs/VALIDATION.md` 和 `artifacts/sitl/report.json` 为准。

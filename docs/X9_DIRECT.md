# 一键直连 X9（设计与实现记录）

交付：[v0.4.0-x9-direct预发布](https://github.com/unununnnn/g12-mavlink-control/releases/tag/v0.4.0-x9-direct)。APK源码提交 `8473c441adec6089802e9772f42a0d9f0e432f43`；[CI构建](https://github.com/unununnnn/g12-mavlink-control/actions/runs/39029425661)成功，3,286条Java断言、14项Python测试通过，lint零错误（13条警告，含中文文本、图标、备份规则及固定频率调度建议）。后台取消调度，恢复时钟间隙先故障停发，防止补跑积压形成控制输出。实际APK SHA256 `da61c60f28dd7bccf9c9d9fea77114a1a62ab31e4117cad5c7bb4df71c46b41e`，5,504,021字节。

[交付证据](evidence/x9-direct-20261017.json)及[最终源码隔离SITL记录](evidence/x9-direct-sitl-20261017.json)：实际Java会话在未解锁Copter4.7.1连续发送125个控制帧，覆盖首次只读绑定、开关停发、飞控超时回到模拟主输入、手动重连保持开关ON不自动发送、输入过期锁存；安全参数前后一致。超过65秒且已解锁状态的连续传输来自合成飞控回归，不能说成真实飞行。

用户于2026-10-17通过grilling确认：GR00直接接X9；首次设置并保存档案；首页仅连接/断开、状态与摇杆显示；连接后待命，以G12物理开关请求/退出传输；绑定单机，核对身份和配置，不自动修改飞控参数；故障停发，手动恢复。

用户明确撤回Q7的10秒窗口和仅未解锁限制。本版持续传输直到物理开关退出、手动断开、后台或故障；解锁状态用于显示，不作为拒发条件。此变更不撤销已接受的身份、输入时效、连接校验和故障锁存要求。开发期间不安装、连接真机发送、解锁、刷写或飞行。

实现采用独立x9/x9-core模块，旧sender电脑收包版本保留其原有台架约束。官方RCSDK输入连接继续采用0.3.1的前台复用和旧GET排空。SDK逻辑UART0不得标成GR00物理透传口；物理口依实际底板手册核对。

首次设置引导采集四轴端点/中心、一个接管开关的关/开位置，保存真实映射而非硬编码示例。GR00与X9的实际透传波特率只录入和用于带宽预算，App不修改硬件串口。首次绑定只发版本/参数/RC遥测读请求，显示目标身份与参数后由操作者保存。以后每次连接自动核对同一目标与关键参数。

校准端点允许实际行程1%的小幅抖动（至少2个原始单位），该范围内钳位到端点，明显越界仍停止。管道关闭等待旧写调用和SDK断开都完成后才允许重连；未完成时提示等待，真正关闭失败提示重启进程，不偷偷并开新管道。

采用无签名MAVLink1/2接收及对应帧格式发送；需要MAVLink签名的链路不能通过本版认证，不关闭其保护。仅发PARAM_REQUEST_READ、REQUEST_MESSAGE(148/65)及所选四轴RC_CHANNELS_OVERRIDE；不发送解锁、模式、任务、参数写入或周期GCS心跳。源sysid根据实际MAV_GCS_SYSID读取，目标从真实心跳与版本绑定，不使用单测示例。

四轴输出读取RCMAP及各通道MIN/MAX/TRIM/REVERSED；其他通道保持ignore，物理接管开关仅在App本地使用。首次连接必须先观察开关关闭，再接受新的开启动作，旧授权不能恢复。退出时停止刷新，不自动发送release；飞控按实际RC_OVERRIDE_TIME处理最后覆盖值，界面不能宣称主SBUS已经接回。

输入回调时效、独立调度看门狗、SDK写超时、近期心跳/RC遥测、参数变化、目标冲突等均停止并关闭管道。没有持续运行的总时限，没有armed/disarmed准入限制。单个SDK写任务在途、无应用积压，不能证明厂商内部队列已排空或物理UART完成。

RC_CHANNELS是当前采用的原始PWM，可核对输入是否跟随，但不是逐帧ACK、独占权确认或独立主SBUS健康。SDK回调时间不证明硬件源端采样新鲜度。标准双串口直入X9不能强制不同合法发端互斥；本应用不能冒充已部署空端仲裁。身份UID/固件比对也不是密码学认证。这些硬件和系统边界仍未验收。

公式依据固定Copter4.7.1 `dbe792162d06cab66c3475fd5556bf7a120f119e`：pitch前推对应负RC角度；REVERSED在PWM后续处理，故本应用按目标参数补偿物理方向；RC_CHANNELS回报原始PWM。PARAM_VALUE使用数值float转换；不要求该旧版未声明的新C_CAST能力位。REQUEST_MESSAGE支持148/65，收到COMMAND_ACK并不等于收到所请求遥测。

## 使用流程

包名 `com.g12.x9`，名称“G12 X9直连”，版本0.4.0-x9-direct。Android13/API33，ARMv7/ARM64。与旧只读/电脑台架应用分开安装；运行时须先关闭其他占用SDK的应用。

首次进入“首次设置”，填入已经在GR00和X9设置好的实际透传波特率。按14步提示分别记录四轴端点/中心与一个独立接管开关的关/开位置；勿同时移动其他轴。然后“读取X9并绑定”，待读取完成核对显示的目标系统/组件、UID和版本，再保存。接线依实际底板的透传口手册，不能把SDK逻辑UART0名称当作物理引脚。缺少心跳、UID或必需参数会显示具体原因，不自动补值或改参数。

日常点击“连接”，完成核对后待命。先把物理接管开关放在关闭位置，再开启才传输；连接时已在开启位置不会自动接管。拨回关闭停止刷新，点击“断开”关闭数传但保留前台SDK读取连接，以支持再次读取。进入后台完整释放；回前台需手动连接。设备档案与诊断在独立按钮中，诊断提供完整释放SDK端口的操作。

本版不需要电脑串口回显脚本。没有总运行时长限制，也不因已解锁而拒绝发送。输入本地回调250ms、SDK写调用200ms、心跳/RC反馈3s等故障检测仍有效；采样和带宽允许时最高10Hz。退出后最后一次覆盖可能持续到飞控现有RC_OVERRIDE_TIME到期，App不把停发视作已完成交还。

## 构建与可复现测试

官方SDK固定至 [rcsdk-demo](https://gitee.com/skydroid/rcsdk-demo) 提交 `d9c6bcab8bb9993cd16f117c01a7601dce10ea6a`，AAR1.9.2 SHA256 `e5977ba3292246bcee026defdb8563a18adc9d5573fdfb20a6880ee469f0fde2`。使用真实KeyChannels GET和createG12G20Pipeline；设备须具备厂商data_stream，缺少时拒绝连接，不安装辅助程序。SDK生命周期继承[前台启停修复](RESTART_FIX.md)，仍需真机复测101问题。

JDK17+运行 `scripts/test.ps1` 或 `bash scripts/test.sh`；再运行 `python -m unittest lab.test_serial_echo lab.test_arbiter lab.test_x9_wire -v`。Java测试覆盖持续已解锁模拟状态超过65秒、默认只读绑定、开关退出、重新连接开关仍ON、输入/心跳/反馈/写调用故障、参数变化、重复进程会话和档案损坏。pymavlink2.4.49独立核对实际输出字节。测试轴值与UID都是合成数据，不作为G12默认档案。

Android需要Gradle8.11.1、AGP8.9.2、Platform35/BuildTools35.0.0。`bash scripts/fetch_skydroid.sh`获取并校验固定SDK后，运行 `gradle --no-daemon -PwithSkydroid=true :x9-core:check :x9:assembleDebug :x9:lintDebug`。CI工作流`x9.yml`构建并检查实际APK包名、权限、签名、14个厂商native库和原创应用调用边界；不上传SDK源包或签名私钥。本机未安装Android工具链，使用已有GitHub Actions构建环境。

`lab.x9_sitl`可将实际Java控制会话通过测试专用stdio传输接到固定4.7.1独立子进程。仅允许loopback隔离namespace，沿用已有SITL哈希/源码检查，无外部目标选项；不解锁、不改飞控安全参数。该测试和mock均不覆盖厂商SDK运行、Android调度、实际RF/UART和飞机动力学。

已有固定SITL构建和Python依赖的Linux环境中运行 `sudo unshare -n bash scripts/run_x9_sitl.sh /root/g12-sitl-4.7.1`。脚本将同一Java源码单独编译为Java11目标以使用本机预装WSL工具链；正常离线回归与Android构建仍使用Java17目标。输出 `artifacts/x9-sitl/report.json`，记录实际固定源码和二进制来源、只读安全参数前后值及会话结果。

协议依据：[MAVLink标准消息](https://mavlink.io/en/messages/common.html)、[固定4.7.1 GCS消息实现](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/GCS_MAVLink/GCS_Common.cpp)。AUTOPILOT_VERSION同时比较语义版本与flight_custom_version，接收字段偏移由独立pymavlink向量核对。

真机待验：首次映射与方向、静止/端点抖动、连续前台启停、后台恢复、101超时恢复、双向数据与版本参数读取、断链停发延迟、厂商队列残留、覆盖超时与真实主SBUS交还、多个合法发端冲突。没有空端强制仲裁与独立源端时间戳，不可把软件回归通过称为完整双机系统已验收。

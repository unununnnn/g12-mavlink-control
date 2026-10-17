# G12 全通道直传 0.5.0

按用户2026-10-17最新要求，取消首次设置、校准向导、X9绑定与保存档案。用户进一步明确要求发送SDK返回的全部通道，包括辅助开关。本版替换0.4的首页流程：打开App不自动发送；点击“连接”后读取真实SDK通道，自动识别链路上的ArduPilot飞控并开始持续发送；点击“断开”停止。没有独立接管开关步骤、总时长限制或解锁状态门槛。

数据采用 `RC_CHANNELS_OVERRIDE`：SDK数组第0项→CH1、第1项→CH2，依次类推；保留原值，不做四轴重映射、归一化、反向或端点缩放。辅助通道也生效，可能触发飞控已配置的模式、解锁或其他RC辅助功能。本App不发额外的解锁/切模式命令，也不改写参数；这不等于辅助开关没有实际作用。

## 日常操作

关闭其他占用SDK的应用，打开 **G12 通道直传**，点击 **连接**。界面显示自动识别的飞控ID、通道原值、提交数与SDK返回数；无需录入波特率、UID或通道档案。GR00与X9已有串口接线、相同波特率和MAVLink协议配置仍须有效，App不自动改写它们。9～18通道需要该链路已使用MAVLink2。

前台断开保留SDK读取连接；重新连接先排空旧读取。进入后台完整释放SDK，回前台需再次点击连接。数传关闭等待旧写调用和断开都结束；迟到的旧管道回调不能影响新连接。诊断页可以完整释放SDK端口。

包名仍为 `com.g12.x9`，版本代码5，版本名0.5.0-x9-direct，界面名称改为“G12 通道直传”。已确认本次CI生成的debug签名与0.4不同，需先卸载旧0.4再安装本版。旧档案不再读取，也不会恢复发送授权。

## 自动发现与协议边界

- 每次连接重新读取心跳目标，不绑定UID，不限制4.7.1版本字符串，不读取旧SharedPreferences。连接中出现第二个飞控则停发，避免向不确定目标继续发送。
- 只读自动查询 `MAV_GCS_SYSID`、`RC_OPTIONS`、`RC_OVERRIDE_TIME`：源ID使用飞控实际配置；拒绝会忽略MAVLink输入的配置或无有限正覆盖超时，不能假装已发送就已被采用。无人工设置页，也不写回参数。
- MAVLink2最多18通道、MAVLink1最多8通道。超出容量明确报错，绝不静默截断。SDK通道数在同次连接中变化也停止。
- 固定ArduCopter4.7.1 `dbe792162d06cab66c3475fd5556bf7a120f119e` 的 [handle_rc_channels_override](https://github.com/ArduPilot/ardupilot/blob/dbe792162d06cab66c3475fd5556bf7a120f119e/libraries/GCS_MAVLink/GCS_Common.cpp#L4177)只处理前16通道。App仍完整编码至18通道，但反馈匹配只核对前16；如SDK返回17/18通道，首页明确提示其接收采用未确认。不能承诺该固件会采用17/18通道，也不为此改固件。
- 直传接受800～2200的PWM数值，原样编码；0、65535等协议特殊值不能被当作普通通道数据发送。不凭单个读数猜测SBUS计数或归一化值的单位。异常提示包含通道号与原值。
- 最高10Hz，每个新SDK样本最多发送一次，不积压。保留本地输入250ms、写调用200ms、心跳/RC遥测3s和反馈未跟随的停止检查；恢复需要手动连接。无周期GCS心跳掩盖输入失效。
- 断开只停发、不自动发送release；飞控按现有RC_OVERRIDE_TIME处理最后覆盖值。该过程不能证明真实主SBUS已接回。签名链路不绕过认证。

官方SDK1.9.2固定来源仍为 [rcsdk-demo](https://gitee.com/skydroid/rcsdk-demo) 提交 `d9c6bcab8bb9993cd16f117c01a7601dce10ea6a`，AAR SHA256 `e5977ba3292246bcee026defdb8563a18adc9d5573fdfb20a6880ee469f0fde2`。实际 `KeyChannels` 的ChannelsConverter按两字节解出原始整数，没有自动四轴归一化；官方按钮示例使用1950，SDK云台按钮处理器使用1050/1500/1950。此为原值直传依据，不是对用户具体G12每个通道范围的硬件测量。

协议依据：[MAVLink RC_CHANNELS_OVERRIDE](https://mavlink.io/en/messages/common.html#RC_CHANNELS_OVERRIDE)、[ArduPilot RC输入](https://ardupilot.org/dev/docs/mavlink-rcinput.html)。RC_CHANNELS反馈用于观察数值跟随，不是逐帧ACK、密码学身份认证或独占控制权。

## 验证与构建

已发布[0.5.0 APK与校验附件](https://github.com/unununnnn/g12-mavlink-control/releases/tag/v0.5.0-x9-direct)，源码提交 `66ee81c036de501d62280bc8d8f54ae79436e834`。[Android构建](https://github.com/unununnnn/g12-mavlink-control/actions/runs/39031055750)通过，Java共21289项断言、Python15项测试通过，lint为0错误、12项非阻断警告（文本国际化、图标、备份声明）。APK SHA256为 `ef7fd4e6a90e5e1ea839a56b9787c26c50077a536b40ec9ff27d4c86a7c31cd4`，本地下载与GitHub附件摘要一致。

隔离SITL在提交 `816b5b1` 上通过，至发布提交仅增加测试输出目录创建，生产源码和仿真入口没有变化。实际收到130个覆盖帧，全部18个报文字段独立核对，前16通道反馈跟随、断开停发、再次连接发送和输入过期停发均通过。见[交付证据](evidence/channels-20261017.json)及[原始SITL报告](evidence/channel-sitl-20261017.json)。

`scripts/test.ps1` / `bash scripts/test.sh`增加ChannelTests，同时保留旧版回归。`python -m unittest lab.test_serial_echo lab.test_arbiter lab.test_x9_wire -v`独立核对16/18通道MAVLink2、8通道MAVLink1的全部字节与辅助通道值。测试覆盖直接连接发送、切换目标后重连、不使用UID/版本/档案、持续已解锁合成状态超过65秒、失联、旧回调、输入失效及容量错误。

已有固定4.7.1 SITL环境中：`sudo unshare -n bash scripts/run_x9_sitl.sh /root/g12-sitl-4.7.1 artifacts/channel-sitl --channels`。使用实际ChannelSession源码，独立启动未解锁SITL，独立解析18通道报文并验证前16通道被采用、持续传输和重连；同时确认17/18没有被该固件采用。辅助值保持模拟主接收机当前配置，不执行解锁/模式操作，安全参数前后一致检查。此测试不接触硬件。

Android构建仍为JDK17、Gradle8.11.1、AGP8.9.2、Platform35/BuildTools35.0.0；运行 `gradle --no-daemon -PwithSkydroid=true :x9-core:check :x9:assembleDebug :x9:lintDebug`。`x9.yml`检查真实APK签名、权限、14个官方native库及应用不再引用校准/档案存储类的字节码。

真机边界：尚未验证G12/GR00/X9端到端发送，SDK code101修复仍需复测。SDK本地回调不能证明源端采样新鲜度，SDK返回不是UART物理发送完成；当前没有空端跨设备强制互斥。不得以模拟结果替代真机或飞行验收。

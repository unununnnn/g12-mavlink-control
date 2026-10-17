# G12 串口台架发送版 v0.3.1

0.3.1新增前台“停止采集与发送（保留SDK连接）”，再次开始排空旧GET后重新读取；“断开SDK / 释放端口”是独立操作。其余发送边界沿用0.3.0。用户报告旧版停止后再读出现SDK101，本版为待G12复测的修复候选，见[启停修复](RESTART_FIX.md)。

用户自报 v0.2.0 真实SDK只读测试成功，并要求继续交付串口发送版。这是用户提供的运行反馈，不是开发者独立真机验证。本版真实集成同一官方RCSDK1.9.2，路径为 **G12 SDK摇杆 → 官方G12数据管道 → GR00 UART0 → 电脑串口收包**。不是SBUS转换，不改RF或X9固件。独立包名 `com.g12.bench.sender`，Android13/API33，ARMv7/ARM64；原只读APK仍可保留，但两应用不能同时占用SDK端口。

## 本次交付与严格边界

本版只交付**单链路、飞控断开的电脑串口收包验证**。真实飞控控制帧已经能由SDK发送，所以禁止把本包当作可飞行遥控器。启动默认停止，必须手动读取、填写并确认映射、打开管道、再明确开始发送。没有解锁、模式切换、任务、参数写入、对频、校准或刷写指令。首次GR00只接电脑的串口适配器，不接X9；之后的X9拆桨台架另行制定受控验收，不因本次收包成功自动获准。

每次窗口最多10秒、1..10Hz、一个新SDK回调最多发送一次，且最多一帧在途。**必须运行随包电脑收包/回显脚本**；任意串口终端若不回显，本App只发第一帧，随后停止。此要求用于限制SDK内部排队和验证GR00字节路径，不是飞控ACK、身份认证或双链路仲裁。

## 核实的SDK语义和端点

厂商 [官方Demo](https://gitee.com/skydroid/rcsdk-demo)，commit `d9c6bcab8bb9993cd16f117c01a7601dce10ea6a`，`rcsdk-v1.9.2.aar` SHA256 `e5977ba3292246bcee026defdb8563a18adc9d5573fdfb20a6880ee469f0fde2`。沿用其空白项目集成指引与前版私有台架打包范围，不新增协议接受、密钥或独立SDK再分发。

- README说明G12/G20在v1.7.3改用UDP数据管道，要求厂商Android系统构建20250110或以后。用户已确认Android13；这不证明厂商构建日期。应用显示Build.DISPLAY并要求用户核实该条件。
- 实际AAR中 `PipelineManager.INSTANCE.createG12G20Pipeline()` 返回UDP管道；固定版本默认本地14551、目标127.0.0.1:14552，是G12内部数据服务端点，不是飞控IP。`createPipeline(G12, UART0)`指向同一工厂。本版使用专用工厂，不猜测`/dev/tty*`、自建网络端点或直接写手机串口。
- 工厂在系统缺少 `/system/bin/data_stream` 时会尝试启动SDK辅助程序。**本应用在调用前检查该系统文件；缺失时拒绝打开并报告，不触发SDK的辅助程序安装/执行回退。**系统升级需另行核实，当前不刷写。
- `setOnCommListener`、`connectPipeline`、`isConnected`、`writeData(byte[])`、`disconnectPipeline`均与真实签名核对。`writeData`返回void，内部UDP实现使用队列且可能静默丢弃。因此界面分别统计“提交帧”“SDK调用返回”“电脑回显”，永不显示“飞控接受成功”。
- Android到服务的UDP不是GR00输出的电气接口；GR00仍走UART透传。波特率由用户核对并填入App与电脑脚本，**应用不修改接收机波特率**。需要确认GR00底板、UART0实际引出及逻辑电平；不可把5V电源等同于UART信号电平。接线依据实际模块/底板手册，不给未经核实的跨板引脚编号。

## 明确映射和消息选择

没有默认物理摇杆/通道对应关系，也没有猜测原始量程。先在App读取真实通道，逐轴/开关确认，然后为ROLL、PITCH、THROTTLE、YAW填写不同的输入CH、实际min/center/max及反向。CH从SDK数组的1开始，输入范围必须递增；超范围立即停止，不钳位掩盖错误。油门按min..max线性归一化0..1000；其他三轴按各自min/center/max分段归一化-1000..1000。

明确输入source sysid/compid、target sysid/compid（均1..255，拒绝广播），并选择：

- `MANUAL_CONTROL` #69：x=PITCH、y=ROLL、z=THROTTLE、r=YAW，buttons=0。该消息**只有target system，没有target component**；App仍记录所填compid，但不声称它在帧中或能隔离组件。其方向需用户按电脑解码记录核对。
- `RC_CHANNELS_OVERRIDE` #70：分别填写4个不同的目标RC通道1..8；归一化后明确输出1000..2000µs，其余1..8通道使用65535 ignore，9..18扩展未发送（ignore）。这只是本台架定义的输出比例，不证明X9实际RC映射/校准正确。消息包含target system/component。

消息定义见 [MAVLink common](https://mavlink.io/en/messages/common.html)。帧为无签名MAVLink2，CRC只校验传输损坏，不证明可信来源。开始要求三轴±50以内、归一化油门≤50；不是飞控解锁许可。修改配置立即停止且须重新确认。重启App不保存发送状态和确认勾选。

## 电脑收包步骤

1. 飞控完全断开；GR00 UART经匹配逻辑电平的串口适配器连接电脑。确认GR00 UART0与电脑波特率一致、TX/RX/GND及供电符合实际手册。G12保持与GR00正常已对频状态，本应用不执行对频。
2. 电脑环境需要 Python、pymavlink2.4.49、pyserial3.5。开发机已存在这些包；没有打开任何真实串口。依赖清单在 `serial-requirements.txt`，若你的电脑缺包，需要你明确安装；不能假设所有电脑已具备环境。
3. 手动运行以下命令；示例ID、COM7和115200只是格式示范，必须替换成你核对的配置，App中填相同ID、波特率和消息类型：

```text
python serial_echo.py --port COM7 --baud 115200 --source-system 245 --source-component 191 --target-system 7 --target-component 2 --message 69 --flight-controller-disconnected --seconds 120 --log capture-new.jsonl
```

脚本不枚举/自动选择端口，日志只新建不覆盖。8N1、读超时50ms、写超时100ms；只对CRC有效、无签名MAVLink2、消息及源/目标ID匹配的帧原样回显。短写/写超时直接结束，不重发旧值，不发送心跳或额外控制。Ctrl+C退出。

4. 关闭助手/地面站及旧只读App，打开新App；开始读取并填写全部配置，确认预览方向。勾选“仅电脑/飞控断开”和厂商构建已核实，打开SDK管道，等待已连接。置中位/低油门，明确点击开始。
5. 观察电脑JSONL中的原始帧hex、序号、source/target及解码轴值。App的“电脑回显”应逐帧增长。逐轴小范围动作核对方向，窗口10秒后自动停止；再测试停止、后台、关闭电脑脚本、关闭GR00、端口占用/迟到回显，均须停止且不自动恢复。电脑日志确认停止后的实际字节尾部，而非只看App状态。
6. RC模式可在无在途帧时**明确**发送一次所选4通道release=0，然后等回显并停止；其他通道保持ignore。MANUAL模式没有猜测映射的释放按钮行为，只停止。异常、后台、超时及普通停止**不会自动补发释放、中位、低油门或心跳**。释放字节返回不证明主SBUS有效或交还完成。

## 停止保护与剩余风险

独立25ms监视器检查本地输入250ms、SDK写调用200ms、电脑回显800ms、最大10秒窗口。只允许一帧等待回显；SDK写工作线程不堆队列、不重发，SDK阻塞时锁存要求结束进程检查。所有条件重新成立也不会自动启动。后台/暂停即关闭发送门、清空输入、请求断开管道；旧回调不能恢复发送。旧管道异步关闭完成前不允许创建相同端点的新管道。

停止只能禁止后续提交并请求SDK断开，不能撤回已经进入SDK、系统服务、无线链路或UART的帧；物理尾帧/内部缓存必须通过电脑日志实测。完整源码未由厂商公开，不能证明其所有缓存/断开行为。源端采样时间/序号未获证明，近期回调可能仍来自SDK缓存；本地新鲜度仅是必要诊断条件。10Hz和阈值是电脑台架上限，不是飞行时延指标。

进程内owner不等于跨设备互斥。现有双链路仍需统一权威覆盖全部控制入口、独立主SBUS健康和受控交还证据；同sysid/签名不自动产生独占权。未验证X9 UID、实际参数、failsafe和围栏行为，不会修改它们。本次未自动安装APK、连接硬件发送、解锁、刷写或飞行。

## 复现

```text
bash scripts/test.sh
python -m unittest lab.test_serial_echo lab.test_arbiter -v
bash scripts/fetch_skydroid.sh
gradle --no-daemon -PwithSkydroid=true :sender-core:check :sender:assembleDebug :sender:lintDebug
python scripts/verify_sender.py sender/build/outputs/apk/debug/sender-debug.apk vendor/rcsdk-v1.9.2.aar
```

CI `sender.yml`使用原有固定JDK/Gradle/Android工具链，临时runner安装固定电脑解码测试依赖；本机不安装新工具或接受协议。同时重建原只读APK并检查其禁止writeData边界。测试含真实Java帧与独立pymavlink逐字节对照、收包分片/CRC/ID过滤，以及发送故障/生命周期；不以离线成功替代GR00或X9实测。APK为debug测试签名，后续构建不保证同签名或逐字节相同，私钥不交付。

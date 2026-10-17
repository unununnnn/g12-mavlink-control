# 云卓 SDK 适配边界

截至 2026-10-17：初次本机搜索未找到 SDK；随后从官网二次开发标签定位并只读取得 [官方 RCSDK-Demo](https://gitee.com/skydroid/rcsdk-demo)，commit `d9c6bcab8bb9993cd16f117c01a7601dce10ea6a`。README 明确列出 G12，版本1.9.2；AAR SHA256 `E5977BA3292246BCEE026DEFDB8563A18ADC9D5573FDFB20A6880EE469F0FDE2`。厂商包仅在项目外 `vendor-review/rcsdk-demo` 核对，没有提交或打包。

实际接口已与 README、Java示例和 AAR 的 javap 签名交叉核对：`RCSDKManager.INSTANCE.initSDK/connectToRC`；`KeyManager.INSTANCE.get(RemoteControllerKey.INSTANCE.getKeyChannels(), CompletionCallbackWith<int[]>)`；`PipelineManager.INSTANCE.createG12G20Pipeline()`；`Pipeline.setOnCommListener/isConnected/writeData(byte[])`。G12摇杆是 GET；H16 LISTEN 不应照搬。README v1.7.3 指 G12/G20 数传改 UDP，要求 Android系统20250110及以后；这不意味着接收机到飞控不是串口。

`sdk-probe` 是本项目原创的只读请求类，隔离于 Gradle/app，不写数据、不初始化 SDK、不连接设备。`scripts/check_sdk_signatures.ps1 -SdkAar <外部AAR路径>` 只做真实类型编译（不是运行测试）。输出原始通道数组与本地接收时间，明确 `sourceFreshnessVerified=false`，不能向控制器授权。SDK 结果没有已核实的源端采样时戳/有效位，不得把回调接收时间当成真实输入新鲜度。

Gitee 页面未声明许可证，本地未发现 LICENSE；公开下载不等于允许再分发 AAR 或含它的 APK。当前硬件阻塞已从“未找到SDK”收敛到：授权/再分发许可、真实采样新鲜度语义、实际G12固件和GR00台架验证、跨设备仲裁。不得把 Android 通用 MotionEvent 当 G12 真摇杆。

`VendorAdapter` 是项目定义的待实现契约，不声称任何云卓同名类/方法存在。当前 `hardware()` 无条件抛 UnsupportedOperationException，且 app 无真实适配入口。

适配前必须取得：

1. SDK 原包、版本、SHA-256、适用 G12 固件、Android API/ABI、许可证与再分发权限；默认放仓库外，不提交二进制。
2. 摇杆与开关读数接口、通道编号、校准/方向/死区/量程、油门模式；每次硬件采样的单调时戳或序号，以及真正的链路有效/失联标志。回调频率不能替代源端新鲜度。
3. GR00 数据收发接口、线程约束、完整写/短写/阻塞/关闭语义、串口参数、电平和拓扑，保留双向遥测。
4. SDK reconnect 是否重播旧样本，权限和服务重启语义。重连必须清队列并更换会话代次。
5. 主链路健康、主操作者交还确认、并发地面站排他策略；UID 和固件版本只校验对象，不证明其可信。

许可和固件/输入契约确认后再集成 adapter 模块与运行契约测试；先只读记录真实输入，再隔离台架。真实硬件发送、安装 APK、解锁、刷写和飞行均需后续明确授权，当前不执行。

# 云卓 SDK 适配边界

**发送版更新：**独立 `sender` 已调用真实 `createG12G20Pipeline/connectPipeline/writeData/disconnectPipeline`，仅用于飞控断开的电脑串口收包；有明确映射、手动启停与单帧回显限制。不是打开原模拟硬件工厂，原只读 `bench` 仍不发送。SDK端点、内部排队和系统辅助程序检查见[SERIAL_BENCH](SERIAL_BENCH.md)。

`bench` 已接入官方 RCSDK1.9.2 的初始化、G12 原始通道 GET 和链路 LISTEN，目标为用户确认的 Android13 G12。完整打包依据、ABI、构建、验收和未验证边界见 [SDK_BENCH](SDK_BENCH.md)。这不是完整遥控版，实际 G12 尚未运行验收。

官方 [RCSDK-Demo](https://gitee.com/skydroid/rcsdk-demo) commit `d9c6bcab8bb9993cd16f117c01a7601dce10ea6a`，AAR SHA256 `e5977ba3292246bcee026defdb8563a18adc9d5573fdfb20a6880ee469f0fde2`。厂商 README 明确提供空白 Android 项目集成方式；本次按此用途制作私有台架 APK。仓库未声明通用开源/再分发许可证，不据此声称可以公开/商业分发或单独分发SDK。AAR只在仓库外/忽略目录使用，不提交厂商二进制或签名密钥。

真实接口已与 README、Java示例和 AAR 签名交叉核对：

- `RCSDKManager.INSTANCE.initSDK/connectToRC/disconnectRC`，`SDKManagerCallBack` 位于 `com.skydroid.rcsdk` 包。
- `KeyManager.INSTANCE.get(RemoteControllerKey.INSTANCE.getKeyChannels(), CompletionCallbackWith<int[]>)`。G12是GET，H16的LISTEN不适用。
- `KeyManager.listen(AirLinkKey.getKeyRawSignalQuality()/getKeySignalQuality(), listener)`，取消时`cancelListen(listener)`。
- 后续传输边界已核对 `PipelineManager.INSTANCE.createG12G20Pipeline()`、`Pipeline.setOnCommListener/isConnected/writeData(byte[])`，但只读应用没有引用或创建这些对象。`writeData`返回void不等于飞控确认。

`bench-core` 的采集会话以模拟SDK边界做超时、断开、迟到回调、并发请求、设备类型及生命周期契约测试；SDK实例本身未在本机/真机执行。`sdk-probe`保留为早期独立签名探针，不参与APK构建。`scripts/check_sdk_signatures.ps1`只做类型编译。

原始数组没有已核实的源端采样序号、时间或有效位；本地回调接收时间只能诊断应用收到了回调，不能证明真实输入新鲜。当前只读APK不引用 `core` 的控制会话，原有 `VendorAdapter.hardware()`仍无条件抛出异常。厂商SDK自身正常连接/GET可能产生设备通信，不能称为无线静默。

后续完整控制仍需核实：G12具体系统构建、源端缓存和失联语义、通道方向/校准/量程、GR00双向串口/电气契约、目标身份、强制覆盖全部控制入口的跨设备仲裁，以及主SBUS健康和操作者交还确认。README v1.7.3说明G12/G20新版数传UDP管道要求20250110+系统，不代表接收机到飞控不再是串口。本APK不创建数传管道。

没有执行安装、真实飞控发送、解锁、刷写或飞行。保留原安全检查、围栏和failsafe，不以只读APK或SITL成功宣称真机控制可交付。

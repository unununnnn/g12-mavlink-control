# 固定 ArduCopter 4.7.1 隔离端到端验证

源码固定为 `dbe792162d06cab66c3475fd5556bf7a120f119e`（Copter-4.7.1）。只从该提交取独立源树及其固定submodule；不使用本机已有 X9 工程的工作文件或二进制，不修改飞控源码。构建记录包含编译器、submodule清单、配置命令和二进制SHA256。

## 重现

Linux需要现有g++、make、git、Python3.10+、JDK17+、iproute2/unshare，以及 `lab/requirements.txt` 中测试依赖。`build_sitl.sh` 不安装软件、不接受SDK协议。CI在临时Ubuntu22.04 runner安装这些Python测试依赖；本机使用已有WSL环境。

```bash
bash scripts/test.sh
python3 scripts/check_vectors.py
python3 -m unittest lab.test_arbiter -v
bash scripts/build_sitl.sh "$HOME/g12-copter-4.7.1"
sudo env "PATH=$PATH" unshare --net -- bash scripts/run_sitl.sh "$HOME/g12-copter-4.7.1"
```

应选全新独立构建路径；已有目录必须是正确固定提交、正确上游且无跟踪文件修改。可设置 `G12_SOURCE_CACHE` 指向现有git对象源以只读fetch该提交；不复制工作树。Windows本机使用 Ubuntu-22.04 WSL，源树放 `/root/g12-sitl-4.7.1`，避免临时目录清理；Java核心测试可先在Windows运行，生成的协议向量由WSL读取。

`run_sitl.sh` 仅在新的network namespace中启动。Python再次检查该namespace独立且只有lo接口；任何可见网卡则拒绝。因为原版SITL的RC UDP接收器绑定0.0.0.0，不能仅依赖目标地址127.0.0.1来宣称隔离。使用namespace使其无法访问真实网络/设备，测试自行启动并只清理自己的子进程，不接入已有SITL或飞控。脚本没有自定义远端主机选项，不打开串口。

初始参数仅设置quad FRAME_CLASS、两条模拟MAVLink串口协议和1–4通道模拟校准量程。每次从空临时存储启动，记录上游原版固件默认的arming/failsafe/fence/RC参数，测试前后比较一致。运行时唯一PARAM_SET为 `SIM_RC_FAIL=0/1/3`；不写真实安全参数、不发arm/mode/mission命令或GCS心跳。持续检查飞控心跳处于disarmed。

## 证据范围

- 由本项目Java编解码器生成的MANUAL_CONTROL和release字节真正经过TCP到达自启动SITL；通过飞控RC_CHANNELS输出检验四轴映射和恢复。
- 错误target和错误GCS sysid拒绝；另一个模拟串口以相同GCS sysid可以替换输入，证明sysid不是排他授权。
- RC_CHANNELS_OVERRIDE基础通道0释放/65535忽略，与扩展通道9的0忽略/65534释放。
- 不再发送后，在持续正常主RC输入时测量默认3s覆盖超时。
- `SIM_RC_FAIL=1`无脉冲、`=3`协议failsafe位模拟，观察RC健康变化；继续输入和释放后的行为。
- 仲裁原型向同一SITL输出时的设备排他、输入过期、停止锁存、显式交还和禁止自动重取。

测试的主输入是SITL RC UDP后端每20ms提供的16通道PWM值，不是SBUS串行电气/射频/GR00验证。namespace里的两个串口也不是两台G12。源端新鲜度、跨设备时钟、厂商SDK缓存行为仍未关闭。

只验证disarmed输入接收与相关状态；没有验证飞行中的RTL/LAND、地理围栏动作或真实链路丢包时延。stock的FENCE_ENABLE/FS_GCS_ENABLE值如为0，会如实记录为stock配置，不能据此声称已验证启用后的failsafe。当前用户X9真实参数从未载入或更改。

输出：`artifacts/sitl/report.json`、`sitl.log`；CI额外保存构建日志与Java协议向量。以报告中的result为准，脚本存在不表示通过。

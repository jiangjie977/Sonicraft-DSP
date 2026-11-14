# Sonicraft 架构决策记录（ADR）

> 目的：把散落在代码注释里的「事故考古 / 为什么不能这样改」集中到这里。
> 代码里今后只保留**一句约束 + ADR 编号**，长解释放这里。
>
> 每条包含：**背景（出了什么事）→ 决策（怎么定的）→ 约束（改代码时必须守住什么）**。

---

## ADR-01 重路由：注册 policy 后必须等 2s 再建 AudioRecord

**背景**：长期现象是「Loopback 能建链、record 也 `state=1`，但 `inputLevel` 恒 0、mix output 恒 `Standby`，原声照样从扬声器出」。
**决策**：注册 `AudioPolicy` 后 `Thread.sleep(2000)` 再建 `AudioRecord`。
**约束**：
- 2s 是**必须的**，不是随意等待。实测改成 300ms 后，首链「重路由 ok（端口已建）但 record 恒 0」，只能靠重建第二条链才有数据，冷启动被拖到 20s+。
- 规律：**重路由只对「晚于 submix 设备就绪」创建的 AudioTrack 生效**，已存在的 track 不迁移。
- 提交：`f435658`（打通）、`d44ef8b`（300ms 回退）。

---

## ADR-02 Loopback 与 Stellar+MP 的原声压制手段不可互换

**背景**：曾试图用同一套静音逻辑同时服务两条链路，结果一条双声、一条全静音。
**决策**：
| 路径 | 取音 | 压原声 |
|---|---|---|
| Loopback | 我方 mix 的 `LOOP_BACK` 复制 | **重路由**（player `deviceIds` → 我方 `Remote Submix Out`） |
| Stellar+MP | MP 捕获 | **`appops PLAY_AUDIO=ignore`** |
**约束**：
- `appops` 静音会**连坐 LOOP_BACK 副本**（0.09 → 0.0），所以 Loopback 绝不能靠它。
- MP 模式下 player **不会**被重路由（`deviceIds` 留在主输出），对 MP 做「重路由校验」是错的，曾误报 fail 并无谓发 pause/play 打断播放（`5a5785c` 移除）。
- 重路由成立时**整链路不挂 DP**，这同时消除了「孤儿 DP 导致某些 app 无声、必须重启」的整类问题。

---

## ADR-03 `nudgeRouting` 禁止切 `MODE_IN_COMMUNICATION`

**背景**：为逼 APM 重算路由，曾用「切通信模式 + speaker 切换」。实测 App 启动后用户正在播放的音乐被**暂停**，建链时「无活跃媒体会话」→ 重路由没有对象 → 表现成「启动后没音效、启动像要 20 秒」。
**决策**：只做 `isSpeakerphoneOn` 切换，**不切 mode**。
**约束**：任何“切 mode”的手法都会让媒体失焦暂停，等价于替用户按了暂停键。提交 `5b52f73`。

---

## ADR-04 输出通道必须用 `USAGE_MEDIA`

**背景**：曾把 AAudio 输出改成 `USAGE_ASSISTANCE_ACCESSIBILITY`，实测连着蓝牙耳机时声音全部从**手机扬声器**输出。
**决策**：维持 `AAUDIO_USAGE_MEDIA`。
**约束**：
- 该次“无障碍导致扬声器”的**因果未获证实**（受控实验期间播放器处于暂停状态，实验作废），但 MEDIA 是**唯一已验证**在蓝牙下正常的配置，不得随意更换。
- 若要重做实验，前置条件必须写死：音乐正在播放（`state:started` 可查）、并同时抓我们自己 player 的 `deviceIds` 与 `usage`。

---

## ADR-05 权限层类名不能混淆（R8 keep）

**背景**：release 版打开即闪退；dex 里查不到 `LoopbackProbeUserService` / `BinderContainer` 原名。
**决策**：proguard 保留 `com.jiangjie.Sonicraft.privilege.**`、`com.stellar.**`、`Parcelable.CREATOR`。
**约束**：这些类的名字是**以字符串跨进程传递**的（Stellar 在自己进程按类名实例化我们的 UserService；Parcelable 类名写进 Parcel 需两端一致）。任何 `-repackageclasses` / 字典混淆都必须配合 keep。提交 `e4722d7`。

---

## ADR-06 构建与服务端的“双陈旧”陷阱

**背景**：多次出现「代码改了、装上去行为没变」。
**决策**：
1. `:integration:stellar` 会被 Gradle 误判 UP-TO-DATE → 用 `--rerun-tasks`，并用唯一中文字符串扫 dex 验证是否进包；
2. Stellar 会按 versionCode 复用服务进程 → **递增 UserService `versionCode`** 才会加载新 dex；
3. `install` 与 `build` 同批执行会装到上一个 APK → 分开执行并核对。
**约束**：服务端代码改动后，务必确认 `versionCode` 递增 + dex 标记校验。

---

## ADR-07 设备卡的“技术指标”要面向用户语义

**背景**：「CPU 载荷」显示 90% 让用户以为整机 CPU 跑满；「路由：port=NNN · 命中」让用户完全看不懂。
**决策**：
- 「CPU 载荷」→ **「DSP 负载」**，且分母从 128 帧(2.67ms) 校准为真机 192 帧(4ms)；
- 路由信息**从 UI 下架**，只保留在诊断日志。
**约束**：面向用户的界面只显示“用户能据此行动”的信息；技术真相进日志。

---

## ADR-08 状态标签必须与真实阶段同步

**背景**：电源键在呼吸闪烁（建链中）时，胶囊已显示 `LOOP`，诱导用户以为已就绪；另一头又出现过“建链完成后仍卡在 Waiting”（`chainStarting` 清除晚于状态变 Running）。
**决策**：闪烁期间显示 `Loading`（中性色）；闪烁结束且 `engineState=Running` 才显示 `LOOP` / `MP+DP`。
**约束**：`Running` 判定优先于残留标志；`chainStarting` 必须有看门狗兜底（15s）。

---

## ADR-09 验收判据要直接命中“后果”，不要迷信代理指标

**背景**：多频段相位验收曾用「互相关延迟」→ 6kHz@48k 周期正好 8 样本，lag=8 与 0 等价（整周期混叠）；改用「相位拟合」仍被周期混叠（100Hz 读出 446 ≈ -34 mod 480）；改用「冲激群延迟」测得准，但 **LR4 最小相位分频的群延迟本就随频率变化**，把 4.3 样本的斜率当跳变 → 误判。
**决策**：最终用「对数扫频 100Hz–10kHz 的分段幅度平坦度」——相位不连续的真实后果就是相邻频带抵消、幅度塌陷。
**约束**：设计断言时先问“这个指标在失败时一定异常吗”；能用“后果”就不用“代理”。

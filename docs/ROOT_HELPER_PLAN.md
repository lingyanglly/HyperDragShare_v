# Root Helper 设计（阶段 2）

目标：把 evdev 读取从“传送门进程内临时 `su`”搬到模块自己的常驻进程，让传送门启动时事件流已经在跑；
同时为后续功能（全局手势、主动截图/OCR、相册 URI）提供一条受控的 root 通道。

## 1. 组件

| 组件 | 进程 | 职责 |
|---|---|---|
| `RootInputService` | 模块（`com.leaf.hyperdragshare.codex`） | 前台服务，持有 `su` 管道，解析 evdev 帧，向订阅者广播 |
| `RootInputHub` | 模块 | 单例，参考计数管理 `su` 会话；无订阅者时闲置 N 秒后关闭 |
| `RootEventClient` | 传送门（注入进程） | `bindService` 拿 Binder，发 `Message` 订阅，收 `Message` 事件，断线重连 |
| `RootTouchSource` | 传送门 | 改为“优先 helper，失败回落本地 pipe”，接口不变 |

## 2. 协议（Messenger，避免 AIDL 文件）

- 客户端 `bindService(Intent(RootInputService).setPackage(module))`，服务 `onBind` 返回 `Messenger`
- 客户端 `Message.what = MSG_SUBSCRIBE`，`replyTo = 自己的 Messenger`（用于回调）
- 服务端每条触点事件 `Message.what = MSG_POINTER`，`Bundle{action, x, y, eventTime}`
- 心跳 `MSG_PING/PONG`，5 秒无响应则客户端重建连接
- 取消订阅 `MSG_UNSUBSCRIBE`；客户端 `onServiceDisconnected` 时清空并重连

## 3. 安全

- **权限级别必须用 `normal`**：传送门是系统签名，拿不到我们自定义的 `signature` 权限，
  绑定会被系统直接拒绝。真正的门禁放在 `onBind` 的 `Binder.getCallingUid()` 校验：
  只放行模块 UID 与传送门 UID，其余返回 `null` 并记日志。
- 服务 `exported="true"` + 该 `normal` 权限 + 上面的 UID 校验
- 只提供“订阅触点”一个能力，不暴露通用 `exec`
- 默认开启（用户 2026-11-01 决定）；可通过设置项关闭

## 3.1 常驻的必要条件

仅靠 `bindService` 拉起服务仍然拿不到“portal 启动前已有事件流”。必须：

- 开机 `BOOT_COMPLETED` 接收器在开关开启时 `startForegroundService`
- 首次冷启动后保持前台服务存活（`START_STICKY`）
- 若开机自启被 ROM 拦截，退回“绑定时启动”，功能降级但不报错


## 4. 设置

- `DragShareSettings` 新增布尔 `rootInputHelperEnabled`，Bundle key `root_input_helper_enabled`
- 设置页“实验”分组加开关，说明：开启后模块常驻读取触摸设备，冷启动首次拖动即可跟手
- 关闭时：停止服务、销毁订阅，传送门回到本地 `su` pipe

## 5. 迁移顺序

1. 服务 + Hub + 前台通知，能打开/关闭、写心跳日志（不动传送门行为）
2. `RootEventClient` 接入 `RootTouchSource`，开关打开时优先使用；本地 pipe 保留为回退
3. 压测：开关切换、进程被杀、root 授权撤销、双指手势、锁屏
4. 默认值仍为关；稳定一个版本后再考虑默认开启

## 6. 后续能力（挂同一通道）

- `MSG_FOREGROUND_PACKAGE`：当前前台包名（手势/目标判断）
- `MSG_CAPTURE_REGION`：按矩形截图（复用 `RootScreenshotter`）
- `MSG_RESOLVE_URI`：相册/文件 URI 读取（视频拖拽的前置）
- 以上都只做具体 RPC，不做通用 shell

## 7. 风险

- 前台服务常驻耗电/被杀：MIUI 需要“自启动 + 省电无限制”，服务被回收时客户端自动回落本地 pipe
- root 授权被撤销：Hub 检测 `su` 退出码，通知设置页状态
- 多传送门进程：订阅按 PID 记账，进程死亡自动清理

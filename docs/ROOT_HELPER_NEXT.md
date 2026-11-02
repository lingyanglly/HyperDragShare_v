# 下一轮执行清单（root helper 收口）

目标版本：`1.9.9 / versionCode 92`。工作副本：`HDS-515/`。构建：
`gradle-8.13 + JDK21 + SDK37`，`assembleRelease` → `apksigner`（debug keystore）→
`adb install -r`。

## 1. 权限与默认值

- `AndroidManifest.xml`：`ROOT_INPUT` 权限改 `protectionLevel="normal"`（signature 权限传送门拿不到）
- `RootHelperPrefs.java`：`KEY_ENABLED` 默认值 `false` → `true`

## 2. 开机自启

- 新文件 `BootReceiver.java`：`BOOT_COMPLETED` → `RootHelperPrefs.isEnabled()` 为真时 `startForegroundService(RootInputService)`
- Manifest：`<uses-permission RECEIVE_BOOT_COMPLETED>`；`<receiver android:name=".BootReceiver" android:exported="true"><intent-filter><action BOOT_COMPLETED/></intent-filter></receiver>`

## 3. 客户端

- 新文件 `RootEventClient.java`（同包，运行在传送门进程）：
  - 常量：`MSG_SUBSCRIBE=1`、`MSG_POINTER=10`、`EXTRA_ACTION/X/Y/TIME`
  - `bindService(new Intent().setComponent(new ComponentName("com.leaf.hyperdragshare.codex","com.leaf.hyperdragshare.codex.RootInputService")), conn, BIND_AUTO_CREATE)`（用字符串组件名，避免 portal 进程解析不到服务类）
  - `onServiceConnected` 发 `MSG_SUBSCRIBE`，`replyTo = 本地 Messenger`
  - 收到 `MSG_POINTER` → 回调 `Listener.onPointer(action,x,y,time)`
  - `onServiceDisconnected` / 绑定失败 → `isConnected()=false`，500ms 后重试
  - `start()` / `stop()` 幂等

## 4. 接入 PortalHooks

- `applyPortalRuntime()`：先建 `RootEventClient` 并 `start()`；同时**仍然**建 `RootTouchSource`（本地回退）
- `dispatchRootPointer` 已是唯一漏斗，client 回调直接复用它
- `hasReadyRootSource()` 改为：`(client != null && client.isConnected()) || (rootTouchSource != null && rootTouchSource.isReady())`
- 防重复事件：client 连接成功时 `rootTouchSource.stop()`；断线时重新 `start()`
- `stopPortalRuntime()`：`client.stop()` + 原有 `rootTouchSource.stop()`

## 5. 设置开关

- `SettingsScreen.kt`：新增 `SwitchPreference`（标题「常驻输入（实验）」，summary 说明冷启动首笔立即跟手）
  - 切换时：`RootHelperPrefs.setEnabled(context, checked)`；开 → `startForegroundService`，关 → `stopService`
  - 与现有开关写法一致（`checked = ...` / `onCheckedChange = { ... }`），放在外观设置之前的“实验”位置

## 6. 验证

1. `adb install -r` 后 → `logcat | grep DragShare/RootService` 应出现 `helper started`
2. `adb shell am force-stop com.miui.contentextension` → 立刻长按拖图 → 首笔即跟手，日志有 `subscriber added`
3. `adb reboot` → 开机后 `dumpsys activity services com.leaf.hyperdragshare.codex` 可见服务（若被 ROM 拦截，记录并降级）
4. 设置里关闭开关 → 服务停止、日志 `helper stopped`，行为回落到本地 pipe
5. 回归：胶囊淡入、横向滚动、地址排序、投递

## 7. 已知风险

- MIUI 省电策略可能杀前台服务：需要“自启动 + 无限制”权限；被杀后 client 断开自动回落，不报错
- root 授权撤销：`su` 退出 → `RootTouchSource` 记 `input stream ended`，client 仍连接但无帧，需在设置页显示状态（本轮可不做）
- 双指手势：协议只传单指主队列，行为与本地 pipe 一致

## 遗留两项（1.10.1 之后待改）

### F1. 冷启动首笔 250ms 宽限

文件：`DragShareController.java`，`showOnMain()` 开头附近现有分支：

```java
if (!rootInputProven && !miuiMotionSeen) {
    pendingPortalHostFloatWindowSuppression = false;
    log("overlay skipped: no input frame yet in this portal process");
    return;
}
```

改为：先判断是否已创建 client；若是且还没重试过，`mainHandler.postDelayed(() -> showOnMain(...), 250L)`
并置 `firstShowDeferred = true`；重试时若仍无输入才真正跳过。需要新增字段：

```java
private boolean firstShowDeferred;
```

注意：`CapturedContent` / 坐标 / settings 需用 final 局部变量捕获；`firstShowDeferred` 在成功进入会话后复位。

### F2. 设置页「常驻输入（实验）」开关

文件：`SettingsScreen.kt`。**不要插在 `item(key = "content-title")` 之前**（该位置在 `LazyColumn` DSL 之外，
会报 `Unresolved reference 'item'`）。正确位置：`item(key = "activation-status") { ... }`
闭合之后、`item(key = "content-title") {` 之前，且必须仍处于 `LazyColumn { ... }` 的 lambda 内。

控件写法（与该文件其它开关一致，`context` 已在作用域内）：

```kotlin
item(key = "root-helper") {
    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
        var rootHelperOn by remember { mutableStateOf(RootHelperPrefs.isEnabled(context)) }
        SwitchPreference(
            title = "常驻输入（实验）",
            summary = "保持触摸读取常驻，传送门启动后的第一个拖拽即可跟手",
            checked = rootHelperOn,
            onCheckedChange = { checked ->
                rootHelperOn = checked
                RootHelperPrefs.setEnabled(context, checked)
                val helperIntent = android.content.Intent(context, RootInputService::class.java)
                if (checked) context.startForegroundService(helperIntent)
                else context.stopService(helperIntent)
            },
        )
    }
}
```

### F3. 断线回落实测

helper 进程被杀后 `START_STICKY` 会自愈，因此“回落”分支难以自然触发。测试方法：
`adb shell am force-stop com.leaf.hyperdragshare.codex` 后**立即**（<1s）长按拖动，
预期日志 `root helper unavailable; staying on local evdev`，且拖动仍可用。

## 实施记录（1.10.3 / versionCode 96）

- F1：首测（16:24）证明固定 250 ms 宽限不够：长按静止期间 evdev 不产新帧，日志为
  `overlay skipped: no input frame after 250ms grace`。改为两层：`RootInputService` 在新订阅者
  加入时重放最后一条 `DOWN`/`MOVE`；`DragShareController.acceptPointerEvent()` 收到首帧时立即
  接管，最长等待 1500 ms 作为清理兜底。延迟接管期间保留原浮窗抑制。
- F2：设置页在 `activation-status` 之后新增“常驻输入（实验）”开关；`get_settings` 附带
  `root_helper_enabled`，`RootEventClient` 观察设置 URI，关闭时 unbind 并回落到本地 evdev，
  服务随后被 `stopService` 真正停止。
- F3：补齐 `onServiceDisconnected` → `root helper disconnected; restarting local evdev`。
  订阅协议从 Messenger `MSG_SUBSCRIBE` 改为 one-way `transact(TRANSACTION_SUBSCRIBE)`，
  UID 校验收进自定义 `Binder.onTransact()`；`onBind()` 里的 `Binder.getCallingUid()` 拿不到
  调用方 UID（lint `BinderGetCallingInMainThread`），不能作为门禁。
- 待复测：强停传送门后的冷启动首笔、强停模块后的断线回落、设置开关关闭后的服务停止。
- 实测（16:30，F1 复测）：`input not ready; taking over on the first input frame (waiting up to
  1500ms)` → `replayed active pointer to new subscriber action=ACTION_MOVE` → 9 ms 后
  `first input frame arrived; taking over the drag` → `preview shown` → `input source=root` →
  `gesture finished source=root`，`control-257` 被暂存到 UP 后回放；用户确认预览跟手。
- 实测（16:32，F3）：强停模块后 `helper disconnected` → `root helper disconnected; restarting
  local evdev`，portal 内本地 evdev 24 ms 后 ready；随后 portal 的 `BIND_AUTO_CREATE` 又把模块
  拉起，1 s 内 `subscribed to root helper` 并停本地。回落链路成立，但系统自愈很快，纯本地
  evdev 窗口只有约 1 s。两个进程都冷启动时，若 1500 ms 内没有首帧会跳过
  （`overlay skipped: no input frame within 1500ms`），第二次长按（helper 已热）正常。
- 未实测：设置开关关闭后的服务停止（需要 UI 点击），逻辑已实现并在 `IMPLEMENTATION.md` 描述。
  该项已在 1.10.4 补测，见下。

## 1.10.4 / versionCode 97 收口

- 延迟接管兜底从 1500 ms 放宽到 3000 ms：完全冷启动时用户可能在传送门预览上停留超过 1.5 s，
  3 s 仍能在首帧到达时接管（实测 16:32 的首笔就是 1.5 s 到期被跳过）。
- 去掉 `UP`/`CANCEL` 判断里的时间戳比较：延迟接管期间只要最新观测到抬手就立即放弃，
  不存在“旧手势 UP 时间戳更早所以继续接管”的路径。
- F2 开关实测（16:41，传送门进程存活）：
  `RootClient: root helper disabled; unbinding` → `RootService: helper stopped after frames=1632`
  → `Taplus: root helper disconnected; restarting local evdev`（portal 本地 evdev 24 ms 后 ready，
  ActivityManager 同时取消 FGS 通知）；重新打开后 `helper started` → `subscriber added` →
  `subscribed to root helper` → `root helper connected; local evdev stopped`。
  开关的启停、通知和 unbind/回退链路全部验证通过。
- 1.10.4 的实机拖拽回归（16:41，图片，helper 热）：`preview shown kind=IMAGE` →
  `input source=root` → `gesture finished source=root`，正常。

## 1.10.5 / versionCode 98：冷启动中途接管

- 16:44 双冷启动长按复测（3 s 兜底）：portal runtime 28.006，helper 进程 27.980 才启动，
  28.011 进入延迟接管，31.012 `overlay skipped: no input frame within 3000ms`；
  `RootService` 的 `frames` 直到 44.834 才从 0 变 1，即整手没有任何 evdev 帧。传送门自己在
  29.695 收到 `control=257` 并 `cancelTask`，所以用户看到的是原版识图。
- 根因：Type-B 驱动只在按下沿报告 `ABS_MT_TRACKING_ID`，读取器在 DOWN 之后才启动；静止期间
  没有帧，移动帧又只有坐标，解析器因没有 tracking id / `BTN_TOUCH` 而丢弃。
- 修复：`EvdevTouchParser` 在 `adoptsInFlightStroke` 状态下用仅有坐标的首帧合成 DOWN，并把该
  slot 视为已跟踪，直到真实 tracking id 或 `-1`/`BTN_TOUCH=0` 结束。新增单测
  `adoptsInFlightStrokeWhenStreamStartsWithPositionOnlyFrames`。
- 16:53 复测（双冷启动，长按约 1.7 s 后再慢拖）：`frames=1`（解析器对进行中触摸合成 DOWN）
  → `first input frame arrived; taking over the drag` → `preview shown kind=TEXT at=701,1680`
  → `input source=root action=ACTION_DOWN` → `frames=101/201/301` → `gesture finished
  source=root point=858,414`，`control=257` 延迟到 UP 后回放。双冷启动首笔已能跟手。
- 已知外观：双冷启动时传送门原生 `SplashFloatView` 在首帧之前已挂窗，会和模块预览同时显示到
  UP。16:53 日志：04.159 原生资源加载 → 04.162 模块 `preview shown` → 04.282 暂存
  `control-257` → 06.950 `quitSplashFloatView`。helper 已热时原浮窗在 `addToWindow` 就被抑制，
  不会双窗。

## 1.10.6 / versionCode 99：接管时隐藏原版 SplashFloatView

- 识屏来源确认：截图里“T 文本识别”的几何位置等于 `TaplusSplashFloatView` 窗口绘制矩形
  （632,752–1200,979），不是主浮窗、也不是小爱；`TaplusMainFloatView` 才是大识屏面板。
- 实现：接管后反射调用 `mSplashFloatView.hideSplashFloatView(false)`（`isCancelTask=false`），
  只隐藏不结束任务；传 `true` 会走传送门的退出路径并触发 `cancelTask`，把会话也带走。
  `mMainFloatView.hideMainFloatView()` 必然 cancelTask，不动它。
- 关键坑：`floatView instanceof android.view.View` 在宿主类加载器下可能为 false，导致早期版本
  的隐藏静默跳过；改成纯反射（`getObjectField`/`getBooleanField`/`getDeclaredMethod`）。
- 实测（17:22–17:23 双冷）：第一次只按未拖 → `overlay grace skipped`（无帧，符合预期）；第二次
  拖动 → `preview shown` + `suppressed Taplus float window`，T 在拖动瞬间消失，模块预览接管。
- 复测（17:27 双冷，第一手即成功）：`input not ready; taking over ...` → `subscribed to root
  helper` → `RootService frames=1`（解析器中途接管）→ `first input frame arrived; taking over`
  → `preview shown` → `hid Taplus splash float after takeover attempt=0` →
  `input source=root` → `frames=101/201/301` → `gesture finished source=root`；`control-257`
  延迟到 UP 后回放。用户确认预览跟手。日志：`build/device-logs/f1-cold-final2.log`。
- 剩余外观：冷启动按住未动时，原版 T chip 仍会显示（没有输入帧可跟，无法更早接管）；开始拖动后
  即被接管/抑制。若要在按住阶段也不显示原版，需要在首帧之前就抑制 `addToWindow`，风险是 root
  输入失败时整手没有任何 UI。
- 决定（2026-11-01）：保持现状，不在首帧之前抑制 `addToWindow`。

## 1.10.7 / versionCode 100：地址拖拽目标去重

- 现象：拖拽地址时菜单出现 高德/百度/高德/百度。
- 原因：`DragShareController.buildActionTargets()` 为 ADDRESS 加入 geo 动作磁贴（高德、百度），
  随后 `ShareTargetRepository.applySettings()` 又把这两个应用的普通 `ACTION_SEND` 目标加进来。
- 设备核对：`text/plain` 下高德/百度各只有一个 SEND Activity
  （`com.autonavi.minimap/...NewMapActivity`、`com.baidu.BaiduMap/...WelcomeScreen.Alias0`），
  重复完全来自动作磁贴和 SEND 目标的叠加。
- 修复：`ShareTargetRepository.dedupeByPackage()` 在 `prepareTargets()` 末尾按包名折叠，保留先
  出现的 geo/tel/mailto 动作磁贴；`builtin` 包名的复制/保存/分词/系统分享不受影响。
- 新增单测 `ShareTargetRepositoryTest`（2 个），总计 82。
- 已实机验证（17:34，微信地址拖拽）：用户确认菜单只剩 高德、百度 各一个。日志：
  `build/device-logs/target-dedupe.log`。

## 1.10.8 / versionCode 101：文本分词改为系统悬浮窗

- `BoomChipPage` 原来绑定 `Activity`（finish/startActivity/getLayoutInflater）；已重构为
  `Context + Host`（`requestDismiss`/`openSearch`），Activity 与悬浮窗共用同一套词块。
- 新增 `SegmentationOverlay`：在拖拽会话所在进程用 `WindowManager` 加全屏可触摸窗口
  （传送门 `TYPE_SYSTEM_DIALOG`，无障碍 `TYPE_ACCESSIBILITY_OVERLAY`），复用
  `BigBangOverlayContent` 外壳；`ModernOverlayComposeView` 提供 Compose owner，返回键在
  `dispatchKeyEvent` 处理，点外部关闭。
- 分词仍在模块进程：`ShareImageProvider` 新增 `segment_text` RPC（`int[]`），portal 进程的
  悬浮窗通过 `ImageStagingClient.segmentText()` 取结果，避免在 portal 加载 cppjieba native lib。
- 生命周期：portal 任务结束会 `stopSelf`；新增 `stopSelf`/`stopSelf(int)` hook，在悬浮窗打开
  期间暂存，关闭后由 `onSegmentationClosed` 补一次 `stopSelf`。
- 背景操控：`DEFAULT_BLOCK_BACKGROUND_SCROLL` 从 false 改为 true，拖拽首帧后自动尝试
  `cancelCurrentTouch`；长按菜单若在取消前已弹出，ACTION_CANCEL 不保证收起它。
- `TextSegmentationActivity` 保留给外部 `ACTION_SEND`/`ACTION_PROCESS_TEXT`，拖拽菜单改走悬浮窗。
- 待实机：悬浮窗显示/返回键/点外部关闭、word chip 交互、portal stopSelf 延迟与恢复、背景阻塞日志。

## 1.10.9 / versionCode 102：刷新率投票修复 + 首笔唤醒提速（初版）

### R1. 悬浮窗刷新率投票（拖动手感随应用刷新率漂移）

- 现象：60 Hz 应用里拖拽以 60 Hz 渲染，120 Hz 应用里以 120 Hz 渲染，跟手手感不一致。
- 根因（日志证据，`hds-refresh-test.log` 18:22 段）：旧 `applySurfaceFrameRate()` 反射读
  `ViewRootImpl.mSurface`，BLAST 设备上该字段首次 relayout 后即释放，每次都抛
  `Surface has already been released`，Surface 投票从未生效；仅剩的窗口属性投票又压不过
  前台应用的显示模式（18:09:47 段可见 `MirimRefreshRatePolicy [SceneFps] apply
  channel=powerkeeper fps=60` 把面板按在 60）。
- 修复：`DisplayRefreshRate` 重写为三层投票——窗口属性（`preferredRefreshRate` +
  `preferredDisplayModeId`）、`SurfaceControl.Transaction.setFrameRate(FIXED_SOURCE,
  CHANGE_FRAME_RATE_ALWAYS)`、API 34+ `setDesiredRefreshRate`（milliHz，SDK stub 里
  @hide，反射调用）。SurfaceControl 经 `ViewRootImpl.getSurfaceControl()` 反射获取（注入
  代码运行在传送门进程；失败静默回落），`ModernOverlayWindow.show()` 后按动画帧重试最多
  8 次等第一次 relayout，全部失败时打 `surface frame-rate vote unavailable after 8
  attempts`。`DragShareController.overlayParams()` 为旧式预览/光效/环形/线性菜单补
  `applyParams()` 属性投票。旧 `applySurfaceFrameRate` 删除。

### R2. 空闲后首笔拖放唤醒超过 2 s（初版，1.10.10 重做，见下）

- 初版（1.10.9）：每手第一笔 DOWN 走同步 `transact(IMessenger.SEND, reply)` + 空闲 >15 s
  时的 root 踢脚。实机无效：同步事务在 ROM 不接入 binder 解冻时会阻塞读取线程且等待时间
  不变，15 s 门槛又会被空闲前的零星触摸清零。
- 实机未复现确认后按“管道死亡 / 冻结 / 回收”三种假说重做，见 1.10.10。

## 1.10.10 / versionCode 103：首笔唤醒三层自愈 + 投票落地日志

- 移除首笔同步投递（会阻塞 evdev 读取线程，收益不确定）；帧恢复全程 one-way，顺序不变。
- `RootInputService`：每手第一笔 DOWN 都异步执行 root 踢脚（同手势 2 s 去重）：
  `pidof` 为空先 `am start-service` 拉起传送门，随后 `cmd activity make-uid-active
  <portalUid>` 解冻 UID；3 s 超时销毁 su。日志 `scheduling portal wake kick` /
  `portal wake kick finished exit=N`（exit≠0 时输出原因，便于实机定位）。
- `RootTouchSource`：`su cat` 管道意外 EOF 后 2 s 自动重启发现与打开（旧代码直接退出，
  之后整手无帧）；发现/打开失败不重试，避免 root 被撤销后空转刷 su。模块与传送门两侧
  共用。日志 `input stream ended unexpectedly; restarting discovery in 2000ms`。
- `PortalHooks`：helper 连接在但 400 ms 无帧时（模块管道死而连接未断），`dispatchMotion()`
  跟随 MIUI 运动的同时启动本地 evdev；helper 帧恢复时 `dispatchHelperPointer()` 停本地
  防双源重复。日志 `root stream silent; starting local evdev as fallback` /
  `helper frames resumed; local evdev stopped`。
- `ModernOverlayWindow`：Surface 投票 8 次重试失败时打警告，实机可一眼区分“投票没落地”
  与“投票落地但面板不抬”。
- 单测：移除 1.10.9 的 `wakeKickDue` 2 个测试，总计恢复 82。
- 待实机：
  1. 问题 2：空闲后首笔，看 `scheduling portal wake kick` / `portal wake kick finished
     exit=0` 是否出现；出现后拖放是否仍 >2 s。
  2. 问题 1：拖动时看 `surface frame-rate vote applied rate=120.00001` 是否出现；
     对照 `dumpsys display` 当前模式，判断是投票没落地还是 MIUI 压过应用侧投票。

## 1.10.11 / versionCode 104：实机定位后的三项修复

实机证据（`hds-diag-1110.log` + `hds-refresh-sample-1110.log`）：

- 拖拽期间面板始终跟随前台应用的限速：`MirimRefreshRatePolicy [SceneFps] apply
  channel=powerkeeper fps=60`，模块的 Surface 投票（`surface frame-rate vote applied
  rate=120.00001` 已落地）压不过 powerkeeper 场景。
- `settings put secure miui_refresh_rate 120`（shell 有 WRITE_SECURE_SETTINGS，之前的
  `su -c settings` 探测因 KernelSU 不对 shell 放行而全部空跑）后，基础模式族切到 120，
  拖拽全程 120——`miui_refresh_rate` 才是 Mirim 实际读的总闸。
- HyperOS 没有 `cmd activity make-uid-active`（Unknown command），也没有
  `/sys/fs/cgroup/frozen`（AOSP 冻结机制不存在）；唤醒踢脚里真正生效的是
  `am start-service` 拉起传送门（DOWN 后约 550 ms 进程起来，首笔约 0.9 s 完成）。
- 哔哩哔哩交替失败：成功拖拽后主机 `sIsTaskFinished` 卡在 true（DEX 核对：
  `TextContentExtensionService.onContentReceived` 在 flag=true 时只处理
  `observe_control_event` 257/258、不再取词；`cancelTask` 会置 true，`startPick*Task`
  置 false；实测 257-only 的长按会把 flag 翻回 false，因此“成功→失败→成功”循环）。

### F1. 拖拽期间临时抬升 miui_refresh_rate（手感一致）

- `RootInputService`：进程启动时后台探测 `user_refresh_rate`（system）与
  `miui_refresh_rate`（secure）各一次；两者不同时，每手第一笔 DOWN 异步执行
  `su -c "settings put secure miui_refresh_rate <preferred>"`，UP/CANCEL 后 8 s 定时
  恢复原值（新 DOWN 取消恢复计时；服务销毁时兜底恢复）。触摸频繁时保持 120，停止触摸
  8 s 后回落到用户原来的省电档。写入有 applied 缓存，避免重复写。日志
  `refresh probe preferred=.. original=..`、`raise miui_refresh_rate exit=0`、
  `restore miui_refresh_rate exit=0`。
- 首笔竞速说明：MIUI 在每笔 DOWN 约 200 ms 后按当前设置重算，su 写入约 100-150 ms，
  通常首笔就能赶在重算前生效；赶不上时第二笔起稳定 120。

### F2. sIsTaskFinished 复位（交替失败）

- `PortalHooks.resetHostTaskFinished()`：反射调用静态
  `TextContentExtensionService.setIsTaskFinished(false)`（与主机自身语义一致）。
  调用点：`replayDeferredHostCalls()` 末尾与空队列分支（每手真实 UP 都会走到）、
  `onContentReceived` 的 `afterHookedMethod`（主机处理完 257/258 或正常取词后；
  回放期间跳过，避免双重逻辑）。下一次长按即可重新取词，不再出现“一次能拖一次不能”。

### F3. 模块常驻心跳

- `RootInputService` 每次启动（onCreate/onStartCommand）用
  `AlarmManager.setAndAllowWhileIdle` 自排一个 15 分钟后的 `PendingIntent.getService`
  心跳；进程被 HyperOS 回收后最迟 15 分钟自动复活（复活后重新排下一次）。开关关闭时
  `onStartCommand` 直接 `stopSelf` 不再排心跳。`startForeground` 加 try/catch，避免
  后台启动限制把复活路径打成崩溃。
- 唤醒踢脚命令去掉不存在的 `make-uid-active`，只保留 `pidof` 为空时的
  `am start-service` 拉起传送门。

- 单测：无新增，总计保持 82。
- 待实机：哔哩哔哩连拖 5 手应全部成功；60Hz 应用拖拽期间 `dumpsys display` 应为 120，
  抬手 8 s 后回落到原值；`settings get secure miui_refresh_rate` 全程可核对。

## 1.10.12 / versionCode 105：交替失败修复回退与内容救援

- 1.10.11 的 F2（每次回调后 + UP 时无条件 `setIsTaskFinished(false)`）实机更糟：
  哔哩哔哩“只有第一手能拖出来，后面再也拖不出来”。日志证据：后续每手长按都有内容
  回调（`isedittext...` keys）但无 `suppressed Taplus float window`/`preview shown`——
  内容被 flag=true 静默丢弃；257 事件因 flag 已被清 false 而走进 `onStartPick` 分支，
  在 `contains observe_control_event return` 处早退，`quitByDefault` 永远不执行，
  管线不再复位。已回退该两处复位。
- DEX 核对补充：`quitByDefault` = `if (sIsTaskFinished) return; sIsTaskFinished=true;
  post $5`，`$5.run` 只做 `quitSplashFloatView`/`quitMainFloatView` 的动画收尾，不复位
  标志、不重启管线。因此主机“内容被吞”只能靠内容到来前清标志解决。
- 修复：`handleCallback()` 新增 `hasPickParams()`（结果带 `text_pick`/`image_pick`
  参数）分支——命中即 `resetHostTaskFinished()` 后放行，主机 `onStartPick` 照常取词；
  257/258 与拖拽后主机状态完全不动。这正是 1.10.11 日志里“内容到了却没人取”的唯一
  缺口。
- 待实机：哔哩哔哩连拖 5 手全部成功、无 257-only 空手；日志应出现
  `first event` → `preview shown` 连续 5 轮。

## 1.10.13 / versionCode 106：标志语义纠正（内容回调置 true）

- 1.10.12 实机更糟（“一下都出不来”）。主机日志给出决定性证据：每次内容回调时
  `onContentReceived sIsTaskFinished=false`——1.10.12 的救援把标志清成 false 后，主机
  按 false 语义把内容当“任务进行中”直接丢弃；且 257 反而走进 quitByDefault。
- DEX 重读纠正语义（5.1.5）：
  - `onContentReceived`：flag **true** → `onStartPick` 取词；flag false → 只处理
    257/258 的 `quitByDefault`。
  - `onStartPick` 内部第一道检查同样要求 flag **true** 才继续取词。
  - 即 `sIsTaskFinished` 实际含义是“**上一任务已结束，可以开始下一手取词**”。
    `startPick*Task()` 置 false（进行中），`cancelTask()`/`quitByDefault()` 置 true
    （结束）。1.10.10 起的所有“清 false 复位”理解全部相反，已全部回退。
- 修复：`handleCallback()` 对“非 MotionEvent 且非 257/258”的回调（即取词内容）执行
  `setIsTaskFinished(true)`（`markHostTaskPickReady()`），主机自己的 `onStartPick`
  照常取词；控制事件路由完全不动。内容回调不再依赖 1.10.12 的 `getParams` 反射检查。
- 待实机：哔哩哔哩连拖 5 手全部成功。

## 1.10.14 / versionCode 107：帧率增强窗口的针对性处理

- 1.10.13 拖拽全部成功（20:05 六连拖全部 `preview shown`/`gesture finished`，257 正常
  暂存回放）。帧率问题定位到 MIUI 的 `realControlRefreshRate` 状态机
  （`miui-services.jar` 的 `com.miui.server.mirim.policy.RefreshRatePolicy`，dex 已核对）：
  触摸触发 `UP policyStart`（120），约 0.8-7 s 后自动 `down refresh`（powerkeeper 通道
  按**应用自身限速**投票 60，与 `miui_refresh_rate` 全局设置无关）——长拖拽大部分时间
  跑在 60。UP 窗口会被 `[Extend] type=window_animation/transition/...` 事件续期
  （实测预览入场动画续了 650 ms），而静止长按不算交互、不会续期。
- 两个对症改动：
  - **长按才抬升设置**：`RootInputService` 不再每手 DOWN 就写 `miui_refresh_rate`，
    改为按住 ≥450 ms（长按判定）才抬升、UP 后 2 s 恢复。普通点按/浏览不再把面板
    钉在 120。
  - **脉冲续期**：`DragShareController` 在会话期间每 500 ms 给预览窗口跑一次
    80 ms、1.000→1.004→1.000 的不可见缩放松（`ModernOverlayWindow.pulseBoost()`，
    旧式窗口走 `previewView` 同款动画），让 WM 持续报告窗口动画、MIUI 持续续期
    UP 窗口，拖拽全程保持 120。会话结束即停，不干扰退场动画。
- 待实机：60Hz 应用里长拖拽（含中途停顿）应全程 120；松手 2 s 后回落 60；
  普通浏览保持 60。

## 1.10.15 / versionCode 108：服务起来顺手热传送门

- 1.10.14 帧率确认可用（用户确认），剩"冷启动第一把没拖出来"：双冷启动（传送门与模块
  都死）时模块侧踢脚不存在，只能等系统在长按约 0.5 s 后才拉起传送门，整链约 1.7 s
  （20:26 实测：DOWN 约 00.5 → portal 进程 01.505 → preview 02.163），用户体感失败。
- 修复：`RootInputService.onCreate()` 每次服务起来（开机 BootReceiver、15 分钟心跳闹钟、
  传送门 bind/provider 拉起、用户打开应用）都异步跑一次 `runPortalWakeKick()`
  （`pidof` 为空才 `am start-service`）。任何一次交互循环后两个进程都回到热状态，
  之后的冷启动首笔退化为单冷（模块活着、DOWN 时踢脚约 0.55 s 拉起传送门，首笔约 0.9 s）。
- 待实机：重启手机后（或闲置很久后）第一把长按拖放应在 1 秒内出预览。

## 1.10.16 / versionCode 109：唤醒命令修正（--user 0 + FGS 兜底）

- 日志核对发现 `am start-service` 一直被拒（`Error: app is in background uid null`，
  exit=255）；此前传送门"被踢起来"其实都是系统在长按时的自带启动，模块的踢脚从未
  生效过。改为 `am start-service -n ... --user 0 || am start-foreground-service -n ...
  --user 0`。
- 实测（20:32）：强停双进程 → 仅拉起模块服务 → 5 s 后 `pidof com.miui.contentextension`
  返回 13946、日志 `portal wake kick exit=0 output=Starting service: ...`——预热链路
  真正闭环。此后开机自启/心跳闹钟/绑定任一环节都会把传送门一并焐热。
- 待实机：重启手机后立即长按拖放，应在 1 秒内出预览。

## 1.11.0 / versionCode 110：全量代码审查后的正式版

三路并行审查（无障碍链路 / 分享图片链路 / 设置激活UI）+ 拖拽核心自查，约束 1/2/3/4/5/6/8/12/13/14
全部核验通过。本版修复与清理：

- 【修复】`AccessibilityScreenshotter.crop`：`copy(ARGB_8888,false)` 在源不可变时可能返回
  同一实例，随后 recycle 会回收待交付的 bitmap → 无障碍图片捕获静默失败；改为强制拷贝。
- 【修复】`RootScreenshotter.capture`：`readAll` 先于 `waitFor` 无超时阻塞读，su 挂起会
  永久卡死单线程 classifierExecutor；改为独立读线程 + 4 s 超时 destroyForcibly + 2 s join。
- 【修复】`DragShareAccessibilityService.startRuntime` 无异常保护：构造异常会从
  onServiceConnected/设置观察者/屏幕广播三个入口冒泡导致服务崩溃；加 try/catch + 回滚。
- 【修复】`openSystemShare` 图片 Intent 补齐 `setData(uri)`（约束 6 五项组合完整）。
- 【修复】`ShareLauncher.EXTRA_TITLE` 用真实文件名（原硬编码 drag-share.png）。
- 【修复】`probeRefreshSettings` 先 waitFor 后读流，防 su 挂起阻塞探测线程。
- 【删除】insert_text 全链（无生产方）：ShareImageProvider 的 insert_text RPC、
  DragShareAccessibilityService.insertTextIntoFocusedInput（顺带消除约束 14 的一处字面
  例外）、DragShareController 的 builtin:insert_text 分支、ShareTarget 特判。
- 【删除】`AccessibilityNodeSnapshot` 五个死字段（viewId/clickable/longClickable/important/
  windowLayer）及 Builder setter、service traverse 的 layer 参数；
  `AccessibilityCandidate` 死字段 editable/insideWebView；
  `DragShareAccessibilityService` 死字段 foregroundPackage/windowGeneration（onAccessibilityEvent
  变为纯被动空实现，符合约束 14）。
- 【删除】`DragShareLog.readText`/`isFileDestination`；`DragShareSettings.STYLE_CARD`
  废弃别名；Manifest 未使用权限 ROOT_INPUT 与永不生效的 WRITE_EXTERNAL_STORAGE(maxSdk 28)；
  `ShareUriToken.fileName(token)` 单参重载；`LocalImageSaver.saveLegacy/uniqueFile`
  （minSdk 33 下不可达）及其测试引用更新。
- 【文案】激活卡“未注入/Portal Root 不可用”改为“若传送门正在运行旧版本，重启设备后生效”，
  激活态不再硬编码“传送门 4.2.1”（兼容 5.1.5）。
- 【记录】ModuleActivation 探测一次性（授权弹窗超时后不重试）、RootFileSink 长驻 su stdout
  未排空、dataSync FGS 在 targetSdk 35+ 的 BOOT_COMPLETED 限制，均为已知观察项。

单测维持 82 全绿（删除项同步更新测试引用），lint 通过。交付 `hds-1.11.0.apk`。

## 1.15.1 / versionCode 115：误触发 / 预览延迟 / 拖拽刷新率

- 【修复】长按位移阈值从 `2×slop+16dp`（实测约 44px）降为标准 `8dp` slop：缓慢滑动时能在
  长按计时内被判定为滚动并取消，不再"滑慢一点就触发拖放"。
- 【修复】抓取改为**与长按并行**：长按超时立刻抓内容（B 站 Hook / 截图），只有"显示"被延迟到
  手指移动超过 8dp 之后。静止长按仍然不出菜单，但移动后预览立刻出现，不再需要先移动一大段
  再等 ~300ms 截图。
- 【修复】拖拽刷新率：`pulseBoost()` 每次脉冲都重新施加一次 surface 帧率投票（原来只做缩放
  动画），避免前台 App 投票 60Hz 时把拖拽压回 60；脉冲间隔 500ms → 300ms，长拖拽中途停顿也能
  维持 120。松手后脉冲停止，回落到 60 交给系统交互窗口自然过期（约 2s）。
- 【测试】新增 `GestureMath.movedBeyond` 单测；单测 84 全绿，`lintDebug` 通过。

交付 `hds-1.15.1.apk`。

## 1.15.0 / versionCode 114：预览出现稳定窗

- 【修复】当长按点位本来就落在胶囊（顶部）边界时，预览窗刚出现就被第一帧移动事件重新摆放，
  造成"刚出来就跳"。新增 `OVERLAY_SETTLE_MS = 800` 的稳定窗：预览出现后 800 ms 内
  `handlePointerOnMain` 不再重摆预览位置（菜单出现/收起等其它路径仍正常重摆），之后恢复跟手。
- 【优化】延续 1.14.0 的全局去重：`RootShell` 统一 `su` 调用、`OverlayWindowPolicy` 收敛为
  单一策略类、`LogUtils` 转发层删除、`PORTAL_NOTHING` 死链删除；B 站 `AppViewHook` 移除"渲染
  最深 View"的死代码路径。
- 【测试】单测 83 全绿，`lintDebug` 通过。

交付 `hds-1.15.0.apk`。

## 1.14.0 / versionCode 113：浮窗真正统一 + 全局去重

- 【统一·浮窗】无障碍抓到的内容不再由模块进程开窗，而是广播交给传送门进程，由传送门那**唯一
  一个浮窗**显示并跟手（`ACTION_ACCESSIBILITY_CONTENT` + `DragShareController.showFromHandoff`）。
  一次长按无论走哪条链路都只有一个浮窗；模块侧控制器只在"仅无障碍"模式下使用。
- 【统一·窗口策略】`OverlayWindowPolicy` 收敛为一个类：窗口类型由运行能力决定
  （`systemHosted()` = `TYPE_SYSTEM_DIALOG`，`accessibilityHosted()` =
  `TYPE_ACCESSIBILITY_OVERLAY`），原先散落在控制器里的 `"portal"/"accessibility"` 字符串判断
  改为 `deferUntilMove` / `traceToAccessibility` 两个布尔字段。
- 【统一·root 调用】新增 `RootShell`（`exec` / `run` / `readFile`），替换 9 处各自拼装的
  `ProcessBuilder("su", "-c", ...)`（`ViewDumpClient`、`PortalHooks`、`BootReceiver` 已迁移）。
- 【统一·日志】删除 `LogUtils` 这层无意义转发，直接使用 `DragShareLog`。
- 【修复】B 站纯图链路：多进程抢答（子进程 `roots=0` 却回答"无图"）→ 只有持有窗口的进程才应答；
  传送门控制器在贴纸场景下尚未创建 → `handleAccessibilityContent` 先 `applyPortalRuntime()`；
  抓图耗时导致松手后内容被丢弃 → 移交路径不再依赖本地手势状态；去掉多余的 600 ms 等待。
- 【规则】B 站内容判定：有真实 `ImageView` → 纯图；没有但节点是图像区域（视频画面）→ 无障碍
  截图；是文字容器（评论区、`关闭工作表`、`up主头像`）→ 不触发。
- 【修复】更新安装后自动用 root 重启传送门与 B 站，保证新代码（Hook、接收器）被重新加载。
- 【测试】单测 83 全绿，`lintDebug` 通过。

交付 `hds-1.14.0.apk`。

## 1.13.0 / versionCode 112：组合模式 + B 站纯图 Hook + 无障碍自动授权

- 【新增】组合内容获取模式（`CONTENT_CAPTURE_COMBINED`）：「传送门 + 无障碍」同时可用，
  传送门优先；传送门没产出内容时由无障碍接管（长按→节点树→按边界截图）。传送门捕获到
  内容会广播 `ACTION_PORTAL_PICK`，无障碍在显示前等 320 ms 再检查，避免同一次长按弹出两个浮层。
- 【新增】B 站纯图 Hook（`AppViewHook`，作用域含 `tv.danmaku.bili`）：模块通过 root 在
  B 站外部缓存目录写请求（`chmod 666`），注入进程按坐标找到 `ImageView` 用其 `Drawable`
  渲染 PNG 回传，**透明贴纸不再带出下面的封面层**；找不到 ImageView 时回传"无图"标记，
  模块立刻回退到无障碍文字/截图。
- 【新增】无障碍服务由 root 自动授权：`RootInputService` 启动时把自己追加进
  `enabled_accessibility_services`（保留其他服务），并新增 `MY_PACKAGE_REPLACED` 接收器，
  更新安装后系统自动关掉的无障碍会被重新打开，用户无需手动操作。
- 【修复】无障碍运行时复用 `RootInputService` 的 evdev 流（进程内订阅），不再开第二个读取器；
  无障碍进程补 `noteRootPointer()`，否则 `showOnMain` 会因"无输入帧"跳过浮层。
- 【修复】候选选择改为「同一点上取更小的节点」，全屏文本容器不再盖住其中的图片；
  同时更新对应单测（83 个）。
- 【限制】B 站评论区整块是自绘表面（`FrameLayout` 1200×2608，无独立 ImageView/节点），
  评论区图片无法精确提取，按用户决定不处理。

交付 `hds-1.13.0.apk`。

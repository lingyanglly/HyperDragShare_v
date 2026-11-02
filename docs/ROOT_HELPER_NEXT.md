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

## 1.15.27 / versionCode 141：拖动全程高帧 + 松手后一定回落 + 一次按压只开一次窗

【修复】`refresh cap` 中途被还原、松手后又不回落。
- 现象：`DragShare/Refresh` 日志里 `refresh cap 60Hz -> 120Hz` 之后只过了 337ms 就
  `restored to 60Hz`，整段拖动仍然是 60Hz；另一种情况是松手很久后 `miui_refresh_rate`
  仍是 120。
- 成因一：`DragShareController.finishGestureOnMain()` / `cancelGestureOnMain()` 各自调用
  `stopBoostPulse()`，而传送门为同一次按压开窗时会先收到 `control=257` 收尾，于是镜头还在
  拖就已经把提频放掉了。改法：这两个方法不再停脉冲，只有真正移除浮窗的
  `removeGestureViews()` 才 `stopBoostPulse()`；`showOnMain()` 命中“本按压已有窗口”的重复
  分支时重新 `startBoostPulse()`，把被上一次收尾放掉的提频补回来。
- 成因二（松手后仍高刷）：`RefreshRateOverride.release()` 原来在 `savedValue == null` 时
  直接 `rm -f` marker 并且**不写回设置**；一旦某次 acquire 读到“已经是峰值”或进程中途死掉，
  原始 60Hz 就永久丢失，面板被钉在 120Hz。改法：marker 文件成为唯一权威——
  `acquire()` 先读 marker，存在就以它为准重新提频；`release()` 先按 marker 还原再删 marker；
  `active == false` 时的迟到 release 也走 `restoreFromMarker()`。
- 成因三（保险）：`keepAlive()` 每次提频顺带 `touch` marker 作为心跳；新增
  `RefreshRateOverride.recoverIfStale(long staleAfterMs)`，由常驻的 `RootInputService`
  守护线程每 2s 检查 marker 的 mtime，超过 4s 没有心跳就还原并删除（覆盖“传送门进程中途被杀”
  和“上一个进程没来得及 release”）。另加 `MAX_HOLD_MS = 15_000L` 硬上限，任何异常都不会让
  面板一直停在峰值。

【修复】分享菜单“出现 → 消失 → 出现”。
- 成因：同一次按压既有传送门自己的 pick，又有无障碍的移交，两边都走到
  `DragShareController.showOnMain()`；后到的窗口会把先到的拆掉重建（日志里两对
  `requested overlay frame rate=120.00001` 相隔 1.5s）。同时无障碍侧
  `AccessibilityContentCaptureSource` 会对同一个 gesture 移交两次，第二次在传送门里
  `armReminder()` 把已经被撤掉的岛重新发出来，看着就是岛闪一下。
- 改法：① `AccessibilityContentCaptureSource` 新增 `handedOffGestureId`，同一 gesture 第二次
  进入组合模式移交分支时直接丢弃（`already handed off; duplicate content dropped`）；
  ② `DragShareController` 新增 `overlayShownUptime` 与 `HANDOFF_DEDUPE_MS = 1_200L`，
  `showFromHandoff()` 在 1.2s 内已经开过窗就跳过（`handoff skipped: this press already opened
  a window`）。

【记录】帧率上限的取证：`dumpsys SurfaceFlinger` 的
`displayManagerPolicy={appRequestRanges={render=[0.00, 60.00]}}` 说明 HyperOS 会把
`secure miui_refresh_rate` 当作窗口请求的硬上限，`preferredDisplayModeId` 和
`SurfaceControl.setFrameRate` 都突破不了，只有改这个 secure 设置有效；`miui_refresh_rate`
还会被系统按前台应用覆写（B 站前台时是 60，桌面是 120），所以必须靠心跳维持。

单测 84 全绿，`bash tools/deploy_local.sh` 交付 `hds-1.15.27.apk`。

## 1.15.28 / versionCode 142：提频改成后台线程，窗口不再被 su 卡掉

【修复】1.15.27 上"岛正常出现，但预览和分享菜单根本不出现"。
- 成因：`RefreshRateOverride` 的每个 `RootShell` 调用（`su` exec）都跑在调用线程上，
  而 `stopBoostPulse()` 挂在 `removeGestureViews()` 里，`showOnMain()` 建窗前一定会走到
  它；`release()` 在无 marker 时还要 `readFile` 一次（超时 3s）。于是主线程被 `su` 往返
  占住，建窗被推迟到手指抬起之后，表现为"这一按压完全没有窗口"。
- 改法：新增单线程守护池 `WORKER`（线程名 `hds-refresh`），`acquire` / `release` /
  `keepAlive` 只入队，实现拆到 `acquireNow` / `releaseNow` / `keepAliveNow`；
  `acquireQueued` 防重复入队，`keepAlive` 加 900ms 提交去抖。
- 验证（用户真手指，`test28.log` / `test28c.log`）：`overlay shown after the finger moved`
  → `menu shown pointerY=1299 menuTop=72` + `preview shown kind=IMAGE targets=67 ... at=369,1299`；
  重复移交被 `handoff skipped: this press already opened a window` 拦住。

【记录】`content handed to portal kind=IMAGE` 在同一毫秒出现两次，是同一句被
`DragShareLog.i` 与 `service.trace` 各打一遍，不是真的移交了两次。

【记录】帧率：`Overlay` 层相邻两帧间隔约 8.3ms（≈120Hz），
`00:57:40.0–41.0` 232 帧、`41.0–41.9` 214 帧；`miui_refresh_rate` 被旧版本钉在 120 时
`acquireNow` 走 `currentHz >= peak` 分支不写 marker，`release()` 也无从还原，
只能靠 `recoverIfStale` / `restoreStale` 或系统覆写回 60。

单测 84 全绿，`bash tools/deploy_local.sh` 交付 `hds-1.15.28.apk`。

## 1.15.23 / versionCode 137：预览入场对齐到真正的第一帧（去掉开场卡顿）

- 【现象】用户反馈「预览出现有点卡顿」。
- 【根因】`ModernOverlayWindow.showAnimated()` 原来在 `dialog.show()` 之后**立刻**启动
  220ms 的 scale/alpha 动画。窗口的 addView、Compose 首次组合、首次 measure/layout 和
  合成器的背景模糊都落在同一帧上，第一帧往往要 60–120ms 才出来，于是动画已经走到中段
  才第一次可见 —— 观感就是「卡一下再弹出」。
- 【实现】改为等真正的第一帧：`show()` 之后注册 `ViewTreeObserver.OnPreDrawListener`，
  在 onPreDraw 里才 `setAlpha(0)` / `setScale(fromScale)` 并启动动画，使动画 t=0 与首个
  可见帧对齐；同时加 `postDelayed(..., FIRST_FRAME_WAIT_ALLOWANCE_MS + 80L)` 兜底，避免
  窗口完全不出帧时停在不可见状态。等待超过 24ms 会记录
  `enter animation waited <n>ms for the first frame`。
- 【实现】`overlayView.setContentVisibleForWindowAnimation(true)` 从 `show()` 之后移到之前，
  避免把 Compose 的重组排到本该平滑的第一帧上。
- 【实现】`pulseBoost()` 的门限 `enterAnimationUntilUptime` 在等待期也保持有效
  （`+ FIRST_FRAME_WAIT_ALLOWANCE_MS = 140L`），不会在等待第一帧时取消入场动画。
- 单测维持 84 全绿。交付 `hds-1.15.23.apk`。

## 1.15.22 / versionCode 136：岛出现之前就拖动 = 放弃本次手势（改成状态机，不是计时）

- 【需求】用户要求的是因果顺序，不是等待时长：**岛必须已经出现在屏幕上**才允许拖动出预览与
  菜单；在那之前手指就已经拖动，整笔手势视为放弃。
- 【实现】`DragReadyIsland.RENDER_LATENCY_MS = 420L` 与 `static long onScreenUptime()`
  （`posted` 为真时返回 `postedUptime + RENDER_LATENCY_MS`，否则 0）。
- 【实现】`DragShareController` 新增 `private boolean movedBeforeIsland`：`trackGestureOnMain()`
  在移动超过 `gestureMoveSlopPx` 时读取 `reminderVisibleUptime()`（RPC 感知，传送门进程也正确），
  若 `onScreenUptime == 0` 或当前时间早于它，置位并打印
  `finger moved before the reminder appeared; this drag is dropped`；`ACTION_DOWN` 复位。
- 【实现】`showOnMain()` 在岛可见性检查之后新增：`movedBeforeIsland` 为真则撤岛并
  `overlay skipped: the finger moved before the reminder appeared` 返回。
- 【实现】`AccessibilityContentCaptureSource.flushDeferredShowIfMoved()` 同样判断
  `SystemClock.uptimeMillis() < DragReadyIsland.onScreenUptime()`，为真则
  `recycleIfOwned` + 撤岛 + `gestureDetector.captureFailed(id)` + 日志
  `moved before the reminder appeared; content dropped`。
- 【说明】`ISLAND_MIN_VISIBLE_MS` 回到 180L，但计时起点从 `notify()` 改为
  `onScreenUptime()`（notify + 420ms 渲染延迟），因此既保证岛真的上屏过，又不会让窗口
  无谓地等 700ms。
- 单测维持 84 全绿。交付 `hds-1.15.22.apk`。

## 1.15.21 / versionCode 135：岛至少被看见 230ms 才允许窗口接管（700ms 下限）

- 【问题】460ms 只够覆盖 notify → `addDynamicIslandView` 的上界，岛刚露头就被预览窗盖住。
- 【修复】`DragShareController.ISLAND_MIN_VISIBLE_MS` 460L → 700L（约 470ms 上屏 + 230ms 可读）。
- 单测维持 84 全绿。交付 `hds-1.15.21.apk`。

## 1.15.20 / versionCode 134：窗口必须等岛真正画出来（460ms 下限）

- 【问题】`ISLAND_MIN_VISIBLE_MS` 原为 180ms，而它计的是 `notify()` 之后的时长；实测 notify →
  SystemUI `addDynamicIslandView` 需要 340–470ms，于是手指一动、窗口就先把岛盖掉了（"直接拖也会
  出预览和分享框，岛还没出来"）。
- 【修复】`DragShareController.ISLAND_MIN_VISIBLE_MS` 180L → 460L，覆盖实测上界；窗口只可能在岛
  真实上屏之后绘制。
- 单测维持 84 全绿。交付 `hds-1.15.20.apk`。

## 1.15.19 / versionCode 133：按压前重设主机任务标志 + 陈旧会话自清 + 按压取证日志

- 【问题】连续长按时只有第一次有效：被模块接管的拖拽会让 Taplus 的"任务已完成"标志停在
  false，而它只在标志为 true 时才启动下一次 pick —— 后续按压连 pick 都不再发生（无岛、无窗）。
- 【修复】`PortalHooks.dispatchRootPointer()` 在 `ACTION_DOWN` 调 `resetHostTaskForNewPress()`
  （每次按压只调一次 `setIsTaskFinished(true)`），`ACTION_UP`/`ACTION_CANCEL` 复位去重标志。
- 【修复】同一个方法在 `ACTION_DOWN` 检查上一笔会话：`controller.isActive()` 且距上一帧
  `> 2000ms` 就 `cancelActiveSession()` 并清空延迟队列 —— 否则主机 pick 调用会被永久延迟，
  `hasReadyRootSource()` 判真时表现为"第一下能用，之后全哑"。
- 【新增日志】`press start active=<bool> deferred=<n> readyRoot=<bool> idle=<n>ms`、
  `cancelling stale drag session idle=<n>ms before the new press`。
- 【取证结论】`adb shell input` 注入的触摸**不经过内核 evdev**，因此我们的 root 触摸流看不到它，
  它只会经 MIUI motion 通道进入传送门。所以本模块的 root 路径只能由真手指验证，合成手势仅能
  验证框架层回调；此前"注入坐标与日志 point 对不上"也是这个原因，不是坐标映射缺陷。
- 单测维持 84 全绿，lint 通过。交付 `hds-1.15.19.apk`。

## 1.15.15 / versionCode 129：同一按压只发一次岛（不再重发导致缩小）

- 【问题】组合模式下传送门与无障碍会为同一次按压各请求一次岛；第二次 `notify` 同一 id 会让
  SystemUI 播放更新/收缩动画，并把 `postedUptime` 重置，180ms 计时重新开始，于是出现"岛刚出现
  就缩小"、"再拖动没有预览和菜单"。
- 【修复】`DragReadyIsland.show()` 记录 `lastParamJson`，已显示且 JSON 相同时只刷新 6s 生命
  周期（日志 `island already visible; lifetime refreshed`），不再重发通知；`dismiss()` 清空该记录。
- 【修复】`DragShareController` 的过期判定改为 `reminderExpired ## 1.15.13 / versionCode 127：岛至少停留 180ms，才允许窗口接管## 1.15.13 / versionCode 127：岛至少停留 180ms，才允许窗口接管 reminderVisibleUptime() == 0L`：
  岛此刻就在屏幕上时，"上次已过期"的结论不能套用到这次按压。

## 1.15.13 / versionCode 127：岛至少停留 180ms，才允许窗口接管

- 【问题】1.15.11 已经把"岛真的在屏幕上"变成门槛，但门槛只回答"现在可见吗"。手指在长按
  成立后几十毫秒内就开始拖动时，岛刚发出去就被窗口顶掉，用户看到的是"没看见岛就出菜单"。
- 【修复】`DragReadyIsland` 记录 `postedUptime`（`SystemClock.uptimeMillis()`，两进程同一时基），
  通过 Provider RPC 的 `EXTRA_POSTED_UPTIME` 回传给传送门；`DragShareController.showOnMain()`
  要求岛**已可见满 `ISLAND_MIN_VISIBLE_MS = 180ms`**，不足则 `postDelayed` 补足剩余时间，
  日志 `overlay waits <n>ms so the reminder is actually seen`。
- 【同版】岛请求从广播改为 Provider 直呼：`DragReadyIsland.request()` 在非模块进程时先
  `ContentResolver.call(BASE_URI, METHOD_SET_VISIBLE, ...)`（同步、可按需拉起模块进程、不受后台
  广播限制），失败才退回 `ACTION_ISLAND_BRIDGE` 广播；`IslandBridgeReceiver` 仍保留作为冷启动
  兜底。
- 行为总结：长按成立 → 岛出现 → 手指移动超过 20dp → 岛至少再留到满 180ms → 撤岛 → 预览入场
  → 90ms 后菜单长出。岛始终没出现则**本次手势不开窗**（`overlay skipped: the reminder never
  appeared for this gesture`）。

单测 84 全绿（新增逻辑不涉单测路径）。交付 `hds-1.15.13.apk`。

## 1.15.11 / versionCode 125：岛真的出现了，才放行窗口（跨进程状态查询 + 常驻接收器）

- 【根因】1.15.10 的门槛只代表"本进程已经**请求**上岛"，不代表岛真的在屏幕上。两个断点都会
  让请求凭空消失：
  1. 岛的通知属于模块进程，传送门只能广播请求；而 `ACTION_ISLAND_BRIDGE` 的接收器原来只注册
     在 `DragShareAccessibilityService` 里——纯传送门模式（或无障碍被系统停掉）时该服务没跑，
     广播无人接收，岛从未出现，但传送门自己的 `reminderArmed` 已置位，于是照常出窗。
  2. `DragReadyIsland.show()` 内部失败（通知被拦、进程被杀）只写一行日志，调用方无感。
- 【修复】新增 manifest 声明的 `IslandBridgeReceiver`（`android:exported="true"`，只认
  `com.leaf.hyperdragshare.codex.ISLAND`）：进程没起来时广播也能冷启动模块进程并把岛发出来。
- 【修复】新增状态回查：`DragReadyIsland.isPosted()` + Provider RPC
  `DragReadyIsland.METHOD_IS_POSTED = "island_posted"`（`ShareImageProvider.call`，
  `enforcePortalCaller()` 允许本 UID 与传送门）。`DragShareController.isReminderOnScreen()`
  在本进程直接读静态量、在传送门进程走这次 RPC。
- 【门槛】`showOnMain()` 改成查**真实可见性**：不可见时最多等 `REMINDER_WAIT_MS = 400ms`
  （每 `REMINDER_RETRY_MS = 60ms` 重试一次，覆盖"广播唤醒冷进程"的延迟），仍不可见就
  `log("overlay skipped: the reminder never appeared for this gesture")` 并放弃开窗。
- 【安全】该接收器 `exported` 只做"显示/隐藏一条自己的通知"，不读数据、不写设置；日志记
  `bridge request visible=` 便于核对。

单测 84 全绿。交付 `hds-1.15.11.apk`。

## 1.15.10 / versionCode 124：先有岛，才允许出窗口（提醒成为唯一前置条件）

- 【行为】用户要求的顺序是"岛先出现，手指再拖动才触发分享"；此前只是"两条链路各自决定是否
  发岛"，岛与窗口之间没有因果关系，所以会看到"岛没出现也能直接拖出菜单"。
- 【实现】`DragShareController` 新增 `reminderArmed` / `reminderArmedUptime` 与
  `armReminder()`：`show()`（传送门自己 pick，含延后分支之前）和 `showFromHandoff()`（无障碍
  移交）进入主线程的第一件事都是 `armReminder()`，它注册 `setExpiryListener` 并通过
  `DragReadyIsland.request(context, true)` 请求岛（岛是模块进程里的同一条通知，重复请求只更新）。
  无障碍本地模式在 `captureAt()` 上岛后补一行 `controller.noteReminderArmed()`（跨线程，
  走 `runOnMain`），让同进程的控制器知道这次按压已经有岛。
- 【门槛】`showOnMain()` 在 `requestId` 检查之后、任何绘制之前判断 `reminderArmed`：为假就
  `log("overlay skipped: the reminder was never armed for this gesture")` 并返回。三条入口
  （`show()` / `showFromHandoff()` / 无障碍本地 `show()`）共用这一道门，因此"没有岛就没有
  菜单"是结构保证，而不是各分支各自的巧合。
- 【时序】新增 `ISLAND_LEAD_MS = 120L`：若岛是刚刚才被 arm 的（内容在手指已经移动后才回来，
  慢抓取/跨进程移交），窗口最多等剩余的那段时间再绘制，避免"同一帧里岛被窗口顶掉"；正常路径
  （长按期间就已 arm）这笔等待为 0，因此不会给跟手增加延迟。
- 【撤岛】`clearPendingMoveShow()` 现在拆成两层：手指移动真正开窗时只调 `clearPendingPayload()`
  （保留岛与 `reminderArmed`，否则会被自己的门挡回去），`ACTION_UP`/`CANCEL` 以及
  `cancelGestureOnMain()`、`onReminderExpired()` 才撤岛并清 `reminderArmed`。

单测 84 全绿。交付 `hds-1.15.10.apk`。

## 1.15.9 / versionCode 123：岛在、菜单不来的那条分支删掉（无障碍不再让位给传送门）

- 【修复】用户报告"岛出现了，但继续拖动没有分享菜单和预览"。根因是组合模式里无障碍侧的
  `postShowIfPortalSilent()`：只要 1.5s 内见过一次传送门 pick（`isPortalPickRecent(1500)`），
  它就把自己刚抓到的内容 `recycle` 掉、只调 `gestureDetector.captureFailed()` 收场，赌"传送门
  会替这次手势开窗"。赌输的两种情形都会留下"只有岛": ① 那次 pick 属于上一次手势（连续长按时
  很常见）；② 传送门确实 pick 了，但它没抓到内容/被 `reminderExpired` 之类拒掉。于是岛亮着，
  手指怎么拖都没有窗口。
- 【修复】该方法改名 `handOffFoundContent(gestureId, content)`，删掉 `isCombinedCaptureMode()`
  与 `isPortalPickRecent()` 两个条件，**无条件**把内容交给共享窗口。去重不靠"让位"，靠传送门
  `DragShareController.showOnMain()` 自己的两道守卫：`requestId != showRequestId` 丢弃过期请求、
  `active` 时忽略重复 start —— 谁最后到谁说了算，窗口永远只有一个。顺带删掉只服务于该分支的
  `COMBINED_PORTAL_WINDOW_MS`。
- 【约束】菜单入口始终只有 `DragShareController.showOnMain()` 一条（传送门自己 pick 的走
  `show()`，无障碍移交的走 `showFromHandoff()`，两者最终都落到它），岛只是旁路提示，不在
  任何一条路径上做"先出岛才有菜单"的判断。

单测 84 全绿。交付 `hds-1.15.9.apk`。

## 1.15.8 / versionCode 122：补上"有拖放却没岛"的那条链路；岛过期就作废手势

- 【修复】组合模式 + B 站长按文字（封面标题/简介这类"传送门能抓、无障碍会跳过"的内容）时
  两条链路互相以为对方会发岛：无障碍的 `captureAt()` 只在 `!hookedApp || selection.isImage()`
  时发岛（B 站文字不满足），传送门的 `show()` 延后分支又被
  `!loadedSettings.isAccessibilityCaptureMode()` 挡住 → 拖放照常触发但全程没有岛。现在
  传送门无条件 `DragReadyIsland.request(context, true)`：岛是模块进程里的**同一条通知**，
  两条链路各自请求只会更新它，不会叠成两个。
- 【行为】岛的生命周期上限恢复 **6s**（上一版临时调到 12s）。到期后：模块进程撤岛，并通过
  `com.leaf.hyperdragshare.codex.ISLAND_EXPIRED` 广播通知传送门进程；传送门
  `DragShareController.onReminderExpired()` 置 `reminderExpired` 并 `cancelGestureOnMain()`，
  `show()` / `showFromHandoff()` 都会拒绝再开窗，`trackGestureOnMain` 在下一次 ACTION_DOWN
  时复位。语义：**提醒过期＝这次不分享**，之后怎么动都不出菜单。
- 【测试】单测 84 全绿，`assembleRelease` 通过。

交付 `hds-1.15.8.apk`。

## 1.15.7 / versionCode 121：去掉接力延迟；岛过期即取消本次手势

- 【修复】1.15.6 给预览窗加的 160ms"等岛收起"的延迟，实际观感是窗口突然闪出来、跟手
  断了一拍。现在预览窗在揭示瞬间就开始入场（`RELAY_PREVIEW_ENTER_DELAY_MS = 0`），只有
  菜单仍延后 `RELAY_MENU_ENTER_DELAY_MS = 90ms`，"预览先出、菜单后跟"的层次靠这 90ms
  维持，不再有空洞。
- 【行为】岛的生命周期上限（12s）到期时，本次手势一并作废：`DragReadyIsland` 新增
  `setExpiryListener(Runnable)`，`EXPIRE` 里撤岛后回调；无障碍侧注册 `this::cancel`
  （取消长按检测器、关闭会话），传送门侧注册 `this::cancelGestureOnMain`。此后即使手指
  继续移动也不会再弹分享菜单，符合"提醒过期就等于这次不分享"的语义。
- 【测试】单测 84 全绿，`assembleRelease` 通过。

交付 `hds-1.15.7.apk`。

## 1.15.6 / versionCode 120：岛先收、窗口后到（真正的先后关系）

- 【修复】1.15.5 的接力只是"同时开始动"：岛由系统撤销、收起动画约 200ms，而窗口的放大
  淡入立刻开始，观感上就是"岛还没收，菜单已经压上来了"。1.15.6 曾把预览入场延后
  `RELAY_ISLAND_HANDOFF_MS=160ms`，但实测反而显得窗口"闪"出来，已在 1.15.7 撤回为 0。
- 【修复】岛的生命周期上限 `MAX_LIFETIME_MS` 6s → **12s**：长按保持超过 6 秒时岛会自己
  消失，看起来就像"这次没有岛"。
- 【说明】`pulseBoost()` 在入场窗口内只重申刷新率投票，不再打断入场动画；入场窗口现在
  包含这段延迟。
- 【测试】单测 84 全绿，`assembleRelease` 通过。

交付 `hds-1.15.6.apk`。

## 1.15.5 / versionCode 119：岛 → 窗口接力过渡；岛统一由模块进程持有

- 【修复】"有时候没有岛"的第二个成因：组合模式下岛由模块进程发出，而菜单由传送门进程
  显示，`DragShareController` 里用传送门的 context 调 `NotificationManager.cancel(1001)`
  撤不掉我们自己的通知（跨进程 cancel 无效），岛只能等 6s 兜底；同时无障碍侧在传送门
  接管时又主动撤岛，导致整次手势一个岛都没有。
- 【修复】岛改为单一持有者：新增 `DragReadyIsland.request(Context, boolean)`，进程号是
  模块自己就本地 `show/dismiss`，否则广播 `com.leaf.hyperdragshare.codex.ISLAND`
  + extra `visible`，由 `DragShareAccessibilityService` 的诊断接收器在模块进程里执行。
  `DragShareController` 的四处调用（`show()` 延后分支、`clearPendingMoveShow`、
  `cancelGestureOnMain`、`showOnMain`）全部改走 `request()`。
- 【修复】交接期间不再提前撤岛：`flushDeferredShowIfMoved`、`postShowIfPortalSilent`
  和 `doShowCapturedContent` 的组合模式分支都保留岛，直到传送门窗口真正出现（它自己
  通过 `request(context,false)` 撤）；`discardDeferredShow` 则改为**手势结束无条件撤岛**，
  保证岛不会活得比手指久。
- 【优化】接力过渡：`ModernOverlayWindow.showAnimated(float initialScale, long
  startDelayMs)` 新增入场参数；预览从 `RELAY_PREVIEW_ENTER_SCALE=0.82f` 放大淡入，菜单
  从 `RELAY_MENU_ENTER_SCALE=0.92f` 且延后 `RELAY_MENU_ENTER_DELAY_MS=90ms` 长出，胶囊
  菜单同样 `CAPSULE_ENTER_SCALE=0.94f` + 90ms 延迟；`pulseBoost()` 在入场动画结束前
  只重申刷新率投票、不再 `cancel()` 动画（否则入场会被脉冲打断成一跳）。
- 【说明】"分享菜单整体搬进超级岛"经逆向判定不可行（展开态才有自定义 RemoteViews、
  准入需 SystemUI 内置白名单、岛收不到拖拽 hover/drop），细节见
  `tmp-island/REPORT-island.md`；因此改为"岛 → 窗口"的接力视觉。
- 【测试】单测 84 全绿，`assembleRelease` 通过。

交付 `hds-1.15.5.apk`。

## 1.15.4 / versionCode 118：把"已就绪"提醒提前到候选命中时刻

- 【修复】1.15.3 的岛是在"内容抓取完成、确认要延迟显示"之后才发，而纯图提取在
  `tv.danmaku.bili` 里要走一次 root 往返（实测长按到抓完约 450–520ms）。手指动得快时
  内容抓完时手指早就过了 20dp，代码直接走"立即显示"分支，岛根本不发 → 现象就是
  "有时候有、有时候没有"。现在候选一命中就在 `captureAt()` 里先发岛
  （`!hookedApp || selection.isImage()` 时），不等抓取。
- 【修复】抓取失败/被跳过（`candidate missing`、`sharing disabled`、B 站文字容器跳过、
  截屏失败、传送门已接管）这些"最终没有内容"的路径都会撤岛，避免提前发的岛留在屏幕上。
- 【说明】B 站里长按**文字**是刻意跳过的（`no ImageView and no image region; skipping`，
  站点自己的长按菜单不属于可拖拽内容），所以那种情况不会上岛也不会出菜单，属预期。
- 【测试】单测 84 全绿，`assembleRelease` 通过。

交付 `hds-1.15.4.apk`。

## 1.15.3 / versionCode 117：未移动时上超级岛提醒 + 20dp 揭示阈值

- 【新增】`DragReadyIsland`：长按已就绪、手指还没移动时，发一条普通通知并把 JSON 塞进
  `miui.focus.param`，HyperOS 会把它渲染成"超级岛"迷你胶囊（图标 + 「拖动即可分享」）。
  实测要点：`param_v2` 里**不要**给 `param_island`（给了就必须同时给合法的
  `bigIslandArea.imageTextInfoLeft.type=1|5`，否则 `IslandParamsException` 直接撤岛），
  走系统默认岛最稳；`"reopen":"reopen"` 必须显式写，否则同 id 被 cancel 后第二次上岛会静默失败；
  `manager.cancel(1001)` 会连同岛一起撤掉。
- 【新增】上岛/撤岛接线：无障碍路径在 `showCapturedContent()` 的"延迟显示"分支上岛，
  `flushDeferredShowIfMoved()`（移动）/`discardDeferredShow()`（松手）/`doShowCapturedContent()`
  （开窗）/`cancel()` 撤岛；传送门路径在 `DragShareController.show()` 的 `deferUntilMove`
  分支上岛（仅组合模式之外由传送门负责，避免双岛），`clearPendingMoveShow()` /
  `cancelGestureOnMain()` / `showOnMain()` 撤岛。
- 【修复】抓取比松手更晚结束时，原来会先建"已就绪"岛再也没人撤：现在延迟分支先判
  `gestureDetector.isCapturing(gestureId)`，无效手势直接丢弃内容；另有 6s 兜底定时撤销
  （`DragReadyIsland.MAX_LIFETIME_MS`），漏掉 `ACTION_UP` 也不会长期占岛。
- 【修改】菜单揭示阈值统一到 `GestureMath.DRAG_SHOW_SLOP_DP = 20f`（原 8dp 太灵敏、
  24dp 只用于传送门），两条捕获路径共用同一数值。
- 【新增】诊断广播：`ACTION_ISLAND_PROBE` / `ACTION_ISLAND_PROBE_OFF`（可带 `param` 直接覆盖
  JSON，改文案不必重新编译）、`ACTION_LOG_COPY`（把 root 只读日志复制到
  `/sdcard/Download/hds.log`），并可在诊断时临时把日志级别提到 INFO 打回 logcat。
- 【测试】单测 84 全绿，`assembleRelease` 通过。

交付 `hds-1.15.3.apk`。

## 1.15.2 / versionCode 116：快速滑动松手后的残留预览

- 【修复】抓取与移交可能在手指抬起之后才完成，原来照样会开窗：预览停在松手位置，直到
  `stuckSessionCheck`（约 1.7s）超时才消失。`AccessibilityContentCaptureSource.doShowCapturedContent()`
  现在先用 `gestureDetector.isCapturing(gestureId)` 判断手势是否还有效，已抬起就直接丢弃内容。
- 【修复】传送门侧 `DragShareController.showOnMain()` 增加同一语义的守卫：最近一次指针事件是
  `ACTION_UP` / `ACTION_CANCEL` 时跳过建窗，覆盖"内容比手指晚到"的路径，不再依赖看门狗兜底。
- 【测试】单测 84 全绿，`assembleRelease` 通过。

交付 `hds-1.15.2.apk`。

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

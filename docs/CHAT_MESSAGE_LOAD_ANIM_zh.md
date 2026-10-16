# 聊天消息加载动画优化

目标：**完整**进出场（淡入 + 位移 + 微缩放 / Spring），历史加载、新消息、首屏全覆盖；仅在关闭动画、显式降级或快速滚动时整批跳过。大批量时前 N 条完整播放，其余 snap，避免「一超限就全无动画」。

---

## 1. 主要修改文件

| 文件 | 角色 |
| --- | --- |
| `ui/recyclerview/ChatListItemAnimator.java` | **核心**：RecyclerView ItemAnimator；历史淡入+上移+缩放、新消息 Spring+Y、完整批量分发 |
| `ui/ChatActivity.java` | 挂载 animator；`setShouldAnimateEnterFromBottom`；首屏 `maybePlayInitialMessagesAppear`；`notifyItemRangeInserted` 保留 animator |
| `ui/Components/chat/layouts/ChatActivitySideControlsButtonsLayout.java` | 下滑新消息按钮：FastOutSlowIn ~260ms |
| `ui/Components/DialogsItemAnimator.java` | 对话列表插入：每条完整 fade+rise |
| `docs/CHAT_MESSAGE_LOAD_ANIM_zh.md` | 本文档 |

关闭路径：设置 → 动画 / `view_animations`。

---

## 2. 关键路径

### 历史消息（上滑加载）

`setShouldAnimateEnterFromBottom(false)` → `animateHistoryAddImpl`：

- Alpha `0 → 1`
- TranslationY `+12dp → 0`
- Scale `0.92 → 1`
- **240ms**，**FastOutSlowIn**
- 错峰 delay 上限约 **96ms**

### 新消息（底部插入）

`setShouldAnimateEnterFromBottom(true)` → `animateAddImpl(holder, height)`：

- **SpringAnimation** `TRANSLATION_Y`（stiffness≈700，无回弹）
- **ViewPropertyAnimator** Alpha `0.4 → 1`、Scale `0.94 → 1`
- 时长体感约 **200–300ms**

### 首次进入聊天

`maybePlayInitialMessagesAppear()`：可见 `ChatMessageCell` / `ChatActionCell` 错峰淡入 + 缩放 + 10dp 上移（不因低端机整段跳过；仍受 `animationsEnabled` 与条数上限约束）。

### 批量策略（完整优先）

| 条件 | 行为 |
| --- | --- |
| `view_animations == false` | 整批跳过 |
| `setAnimationsDegraded(true)` | 整批跳过 |
| `isFastScrollAnimationRunning()` | 整批跳过 |
| 条数 > `maxAnimatedAdditions()` | **前 N 条完整动画**，其余 snap（N：低端 10 / 中端 18 / 高端 28） |

---

## 3. 推荐参数

| 场景 | API | 时长 | 插值 |
| --- | --- | --- | --- |
| 历史 / 首屏 / 删除 | ViewPropertyAnimator | 240 / 180ms | FastOutSlowIn |
| 新消息 Y | SpringAnimation | ~200–300ms | stiffness 700 |
| 新消息 α/scale | ViewPropertyAnimator | 240ms | FastOutSlowIn |
| 下滑按钮 | BoolAnimator | 260ms | FastOutSlowIn |

---

## 4. 编译与测试

```bash
./gradlew :TMessagesProj_App:assembleAfatRelease
# 或
./gradlew :TMessagesProj_App:assembleArm64Release
```

1. 长聊天上滑 → 可见行完整淡入上移缩放，非整页闪现  
2. 停在历史收/发新消息 → 底部 Spring 插入 + 按钮柔和弹出  
3. 冷启动进聊天 → 首屏错峰渐显（含缩放）  
4. 超大批量 → 前若干条仍有完整动画，滚动不卡  
5. 关闭「动画」→ 无进出场  

# 聊天消息加载动画优化

目标：历史消息上滑加载、新消息插入、首次进聊天时更自然；大批量或低端机自动降级，不影响滚动。

---

## 1. 主要修改文件

| 文件 | 角色 |
| --- | --- |
| `ui/recyclerview/ChatListItemAnimator.java` | **核心**：RecyclerView ItemAnimator；历史淡入+上移、新消息底部插入+微缩放淡入、性能降级 |
| `ui/ChatActivity.java` | 创建/挂载 animator；`processNewMessages` 设 `setShouldAnimateEnterFromBottom`；首屏 `maybePlayInitialMessagesAppear`；`ChatActivityAdapter.notifyItemRangeInserted` 大批量跳过动画 |
| `ui/Components/chat/layouts/ChatActivitySideControlsButtonsLayout.java` | 下滑新消息按钮 / 计数：更柔和的 `EASE_OUT_QUINT` |
| `docs/CHAT_MESSAGE_LOAD_ANIM_zh.md` | 本文档 |

相关（一般不用改）：

- `ChatActivityAdapter`（`ChatActivity` 内部类）— `notifyItem*` 开关 ItemAnimator
- `ChatMessageCell` / `ChatActionCell` — cell 本身
- 设置项 `view_animations`（`SharedConfig.animationsEnabled()`）

---

## 2. 关键位置与思路

### 历史消息（上滑加载更早）

`messagesDidLoad` 里先：

```java
chatListItemAnimator.setShouldAnimateEnterFromBottom(false);
```

再 `notifyItemRangeInserted` → ItemAnimator `animateAdd` → `runAlphaEnterTransition` → `animateAddImpl`：

- Alpha `0 → 1`
- TranslationY `+10dp → 0`（轻微上移）
- Scale `0.94 → 1`
- 时长 **240ms**，插值 **`CubicBezierInterpolator.EASE_OUT_QUINT`**
- 错峰 delay 上限约 **72ms**

### 新消息（底部插入）

`processNewMessages(..., animatedFromBottom=true)`：

```java
chatListItemAnimator.setShouldAnimateEnterFromBottom(true);
```

→ `runMessageEnterTransition` → `animateAddImpl(holder, addedItemsHeight)`：

- 整体上移 `addedItemsHeight → 0`
- 同时 Alpha `0.55 → 1`、Scale `0.96 → 1`
- 时长仍约 **240ms**，`EASE_OUT_QUINT`

用户在看历史时，右下角「新消息 / 下滑」按钮走 `BoolAnimator` + `EASE_OUT_QUINT`（约 260ms）。

### 首次进入聊天

`onTransitionAnimationEnd(isOpen)` 调用 `maybePlayInitialMessagesAppear()`：

- 对当前可见的 `ChatMessageCell` / `ChatActionCell` 做一次错峰淡入+8dp 上移
- 每个实例只播一次；清聊天数据（`clearChatData(full)`）会重置

### 性能降级

`ChatListItemAnimator.shouldSkipAddAnimations(count)`：

| 条件 | 行为 |
| --- | --- |
| `view_animations == false` | 跳过 |
| 低端机 | 最多动画 **4** 条，超出 snap |
| 中端 / 高端 | 上限 **10** / **16** |
| `isFastScrollAnimationRunning()` | 跳过 |
| `notifyItemRangeInserted` 批量过大 | Adapter 直接 `setItemAnimator(null)` |

---

## 3. 推荐动画参数

| 场景 | 方式 | 时长 | 插值器 |
| --- | --- | --- | --- |
| 历史加载 | fade + translateY + soft scale | 240ms | `EASE_OUT_QUINT` |
| 新消息 | translateY 整块上推 + fade + micro-scale | 240ms | `EASE_OUT_QUINT` |
| 首屏 | 可见 cell 错峰 fade + 8dp | 240ms + 18ms×index | `EASE_OUT_QUINT` |
| 下滑按钮 | alpha + scale 0.7→1 + translateY | ~260ms | `EASE_OUT_QUINT` |

说明：上游原 `DEFAULT_INTERPOLATOR` 自定义贝塞尔已改为更常见的 `EASE_OUT_QUINT`（`.23, 1, .32, 1`），手感更「现代聊天」、不易拖沓。关闭路径：设置 → 动画 / `view_animations`。

---

## 4. 编译与测试

```bash
git submodule update --init --recursive
cp local.properties.example local.properties   # sdk / 签名 / API

./gradlew :TMessagesProj_App:assembleArm64Release
# 或仅验证 Java：
./gradlew :TMessagesProj:compileReleaseJavaWithJavac
```

**建议真机用例：**

1. 打开长聊天，上滑加载历史 → 新行应淡入并轻微上移，非整页闪现  
2. 停在历史处发/收新消息 → 右下角按钮柔和弹出；点回底部插入应平滑  
3. 冷启动进聊天 → 首屏可见消息有短渐显（约 200–300ms）  
4. 低端机或关闭「动画」→ 应无明显卡顿，动画自动减少/关闭  
5. 快速甩动滚动 → 加载时不应掉帧  

---

## 5. 上游同步注意

合并 DrKLO/Telegram 时若改动了 `ChatListItemAnimator.animateAdd*` / `runAlphaEnterTransition` / `ChatActivityAdapter.notifyItemRangeInserted`，请重新带上本分叉的 fade/rise、降级与首屏逻辑。

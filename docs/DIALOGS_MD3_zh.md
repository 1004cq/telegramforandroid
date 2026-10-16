# 聊天列表 Material Design 3 / Material You 优化指南

对照界面：顶部标题、搜索框、**文件夹标签**、**聊天列表**、**底部导航**。  
原则：Telegram 自绘为主，**点状靠拢 MD3**，不全盘换成 Material 组件（否则与上游冲突极大）。

---

## 1. 主要文件位置

| 区域 | 文件 | 说明 |
| --- | --- | --- |
| 聊天列表页 | `ui/DialogsActivity.java` | 组装 ActionBar、搜索、`FilterTabsView`、列表、`MainTabs` 边距 |
| 文件夹标签 | `ui/Components/FilterTabsView.java` | 文件夹 Chip/Tab、选中指示条、滑动联动 |
| 列表行 | `ui/Cells/DialogCell.java` | 头像、标题、副标题、时间、未读徽章排版 |
| 列表动画 | `ui/Components/DialogsItemAnimator.java` | 插入 / 删除 / 移动 ItemAnimator |
| 底部导航 | `ui/MainTabsActivity.java`、`MainTabsLayout.java`、`Components/glass/GlassTabView.java` | 底部 Glass Tab + 选中动画 |
| 主题色 | `ui/ActionBar/Theme.java`、`ThemeAccent` | 强调色 / 壁纸取色（向 Material You 靠拢的入口） |
| 三行布局开关 | `messenger/SharedConfig.useThreeLinesLayout` | 设置里「聊天列表三行」 |

XML：该界面几乎全是 **Java 自绘**，没有传统 `activity_dialogs.xml` 可改。

---

## 2. 分项建议与已做 CQ 改动

### 2.1 文件夹标签 → Chip / Tab + 选中动画

**改哪里：** `FilterTabsView`

| 点 | 建议 |
| --- | --- |
| 外形 | 指示条圆角 ≈ 半高，做成 pill/chip（已调至 ~16dp） |
| 内边距 | `TAB_PADDING_WIDTH` / `TAB_INTERNAL_PADDING` 略增，标签更「芯片」 |
| 选中动画 | `animationRunnable` 时长 ~260ms + **FastOutSlowIn** |
| 文本 | 选中/未选中色继续走 `actionBarTabActiveText` / `UnactiveText`，切换用 `ColorUtils.blendARGB`（已有） |

**勿做：** 换成 `com.google.android.material.tabs.TabLayout`（会丢滑动切页、锁定文件夹等上游行为）。

### 2.2 聊天列表排版（间距 / 头像 / 标题 / 时间 / 未读）

**改哪里：** `DialogCell`

| 字段 | 作用 | CQ 微调 |
| --- | --- | --- |
| `avatarStart` | 头像左边距 | 11 → **12** |
| `messagePaddingStart` | 标题/消息起始 | 72 → **74** |
| `heightDefault` / `heightThreeLines` | 行高 | 70/76 → **74/80** |
| 未读徽章 | `dialogs_countPaint` + `countLayout` 绘制 | 保持圆角徽章，色用 `Theme.key_chats_unreadCounter` |
| 时间 | `dialogs_timePaint` 右上 | 勿与标题抢宽，继续 `timeLeft` 计算 |

更「Material You」可读性：可在设置默认打开 `SharedConfig.useThreeLinesLayout`（三行：名 / 消息 / 状态），但属产品偏好，未强制改默认。

### 2.3 底部导航 → Material 3 反馈

**改哪里：** `GlassTabView` + `MainTabsLayout`

| 点 | 建议 |
| --- | --- |
| 选中动画 | `BoolAnimator` 时长 **260ms**，插值 **EASE_OUT_QUINT**（接近 FastOutSlowIn） |
| 视觉 | 选中态 scale + 背景 pill（Glass 已有）；图标/文字加粗切换已有 |
| 布局 | `MainTabsLayout` 按宽度多 pass 收缩 padding，保留即可 |

**勿做：** 换成 `BottomNavigationView` Material 组件（与 Liquid Glass / 多账号入口冲突）。

### 2.4 列表滚动与插入动画（冲 120fps）

**改哪里：** `DialogsItemAnimator`

| 点 | 建议 |
| --- | --- |
| API | 优先 **ViewPropertyAnimator** |
| 插值 | **FastOutSlowIn** `PathInterpolator(0.4,0,0.2,1)` |
| 时长 | add ≈ **220ms**，move/remove ≈ **200ms**（落在 200–300ms） |
| 插入 | alpha + 轻微 `translationY`（已加 ~6dp） |
| 大批量 | 已有 `mPendingAdditions.size() > 2` 降级逻辑，保持 |
| 120fps | 避免每帧 `notifyDataSetChanged`；动画 ≤250ms；不在 bind 里开重动画 |

硬件加速：窗口默认即可；**不要**给每个 `DialogCell` 开 `LAYER_TYPE_HARDWARE`。

### 2.5 Material You 动态颜色

Telegram **没有**直接接 `DynamicColors.applyToActivitiesIfAvailable`。现有路径：

1. **壁纸取色强调色**（`Theme` / accent）— 最接近 Material You  
2. Android 12+ 可后续加：读 `android.R.color.system_accent1_*`，写入当前 `ThemeAccent`（建议单独 PR，需日/夜间两套）  
3. 列表色键：`chats_name`、`chats_message`、`chats_date`、`chats_unreadCounter`、`actionBarTab*`

CQ 本批未强改系统色，避免破坏用户自定义主题。

---

## 3. 关键代码入口（对照）

```java
// DialogsActivity — 创建文件夹标签
filterTabsView = new FilterTabsView(context, resourceProvider) { ... };

// FilterTabsView — Chip 指示条
selectorDrawable.setCornerRadii(...); // ~16dp pill
animationTime += dt / 260.0f;         // 选中时长

// DialogCell — 行高 / 边距
heightDefault = 74; messagePaddingStart = 74; avatarStart = 12;

// DialogsItemAnimator — 插入
view.animate().alpha(1).translationY(0)
    .setDuration(220).setInterpolator(FastOutSlowIn);

// GlassTabView — 底栏选中
new BoolAnimator(..., CubicBezierInterpolator.EASE_OUT_QUINT, 260);
```

---

## 4. 建议推进顺序

1. 合入本批视觉/动画 polish，真机看文件夹切换 + 列表插入  
2. （可选）默认三行布局或设置项引导  
3. Android 12+ 系统强调色 → ThemeAccent  
4. 对话列表 `notify*` 热点再 profile（Systrace）

---

## 5. 编译验证

```bash
./gradlew :TMessagesProj:compileReleaseJavaWithJavac
./gradlew :TMessagesProj_App:assembleArm64Release
```

真机：文件夹快速横滑、新消息插入列表、底栏切换、浅/深色主题。

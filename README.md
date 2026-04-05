# 电话私密增强版
<img alt="Logo" src="graphics/icon.webp" width="120" />

这是基于 Fossify Phone 修改后的个人版本，重点放在通话记录隐私保护和电话界面优化。

## 版本说明

这个仓库展示的是我修改后的版本，不是原版仓库首页说明。

本版本主要目标：

- 保护私密联系人的通话记录
- 尽量避免私密通话长期留在系统 `CallLog`
- 增加旧通话记录迁移能力
- 优化拨号盘、通话记录列表和主界面视觉效果

## 主要修改内容

### 1. 私密通话记录保护

- 新增私密通话记录本地存储
- 私密联系人通话可从系统 `CallLog` 迁移到应用私有存储
- 通话结束后自动检查最近记录并执行保护逻辑
- 删除、恢复、清空通话记录时同时兼容系统记录和私有记录

### 2. 旧通话记录迁移

- 设置页新增私密通话记录保护开关
- 设置页新增旧通话记录迁移入口
- 已经存在于系统中的私密联系人通话记录可以一键迁移

### 3. 通知安全加强

- 收紧来电通知相关 `PendingIntent`
- 改为更严格的不可变方式，减少被外部利用的风险

### 4. 界面优化

- 拨号盘字体、字距、按钮留白做了优化
- 通话记录列表改成更清晰的卡片式样
- 主界面和列表区域增加更合理的边距与层次感

## 关键修改入口

- `app/src/main/kotlin/org/fossify/phone/helpers/RecentsHelper.kt`
- `app/src/main/kotlin/org/fossify/phone/helpers/PrivateCallHistoryStore.kt`
- `app/src/main/kotlin/org/fossify/phone/services/CallService.kt`
- `app/src/main/kotlin/org/fossify/phone/activities/SettingsActivity.kt`
- `app/src/main/kotlin/org/fossify/phone/helpers/CallNotificationManager.kt`
- `app/src/main/res/layout/activity_settings.xml`

## 发行说明

GitHub Release 中上传的是当前修改版构建产物。

注意：

- 当前 Release 附件为 `unsigned` APK
- 原因是当前构建环境没有正式签名证书
- 如果需要可直接安装的正式版，需要再使用你自己的签名证书重新打包

## 仓库说明

- 默认分支：`private-ui-edition`
- 这个分支保存的是我当前这套隐私增强和界面优化修改
- 原版 Fossify 项目请以官方仓库为准

<div align="center">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" width="30%">
<img alt="App image" src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" width="30%">
</div>

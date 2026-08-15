# 百度输入法OPPO定制版增强

这是一个面向`com.baidu.input_oppo`的LSPosed/Xposed模块，用于恢复和扩展百度输入法OPPO定制版中的剪贴板、快捷短语和复制文本行为。

> **重要说明：本仓库不是原始源码。**
>
> 原项目的原始源码已经丢失。本次公开的工程，是根据宿主输入法安装包进行静态逆向分析、DEX/资源调用关系重建、DexKit特征迁移和功能回归后恢复出来的工程。当前仓库中的代码、重构逻辑、兼容层和构建脚本均由AI完成，不是丢失前的原作者源码，也不应被描述为原始源代码的恢复副本。

## 本次恢复版内容

- 升级到DexKit`2.2.0`，并完成新API迁移。
- 使用DexKit行为特征、资源特征和调用关系定位宿主方法，不把当前混淆类名作为跨版本契约。
- 保留剪贴板计数与剪贴板历史相关Hook。
- 解除复制文本约7000字符的截断限制，使复制阶段保留原始文本。
- 解除快捷短语新增数量限制。输入法顶部快捷短语加号在数量超过100条时仍走宿主原有的悬浮添加流程，不再被强制送入快捷短语管理Activity。
- 解除快捷短语确认时的300字限制，同时保留宿主保存、刷新和文件写入流程。
- 修复历史快捷短语编辑目标解析，编辑非零索引条目时更新原条目，不覆盖最近新增的序号0条目。
- 支持不同宿主版本的三参数、四参数导航方法，以及直接列表和`HashMap`内列表两种数量数据结构。

## 搜索按钮Hook的移除

旧版本曾包含键盘工具栏搜索按钮屏蔽Hook。本次已经从入口、调用链、实现和DexKit查询中完整移除，不是简单停用。

移除原因：新版本输入法已经将相关能力调整为AI相关功能，并且宿主自身已经允许用户调整搜索按钮是否显示。继续在模块中强行阉割搜索按钮会改变用户对宿主功能的自主设置，因此本恢复版不再拦截或隐藏该按钮。

## 构建环境

- Android Gradle Plugin：`9.3.1`
- Gradle Wrapper：`9.7.0`
- compileSdk：`37`
- targetSdk：`33`
- minSdk：`23`
- DexKit：`2.2.0`
- AndroidX Core KTX：`1.19.0`
- AppCompat：`1.8.0`
- Material：`1.14.0`
- ConstraintLayout：`2.2.2`

## 构建

工程使用公开构建配置。仓库不包含私有签名密钥；本地构建时未提供签名配置会生成未签名Release APK。若需要自行签名，按Android标准流程签名即可。

```bash
./gradlew :app:assembleRelease
```

ARM64环境如果需要显式指定兼容`compileSdk 37`的`aapt2`：

```bash
AAPT2_OVERRIDE=/path/to/aapt2 ./tools/build-release.sh
```

若要使用自己的签名文件，可以通过环境变量提供，不要把密码或密钥提交到仓库：

```bash
SIGNING_STORE_PASSWORD='...' \
SIGNING_KEY_ALIAS='...' \
SIGNING_KEY_PASSWORD='...' \
./gradlew :app:assembleRelease
```

## 安装包

可直接使用GitHub Releases中的APK资产：

- `BaiduInputOppoEnhance-universal-dexkit-release.apk`

安装前请确认设备已安装并启用LSPosed/Xposed框架，并将本模块作用域设为百度输入法OPPO定制版。Release中的APK为发布构建产物；源码仓库不提交私有签名密钥。

## 验证范围

本工程包含以下验证脚本和证据目录：

```bash
python3 tools/verify-upgrade.py
```

验证内容包括DexKit查询迁移、旧搜索Hook清理、资源与DEX结构、Xposed入口、构建结果和发布产物静态条件。实际宿主行为还需要在目标设备上按Release说明中的场景回归。

## 致谢

- [DexKit](https://github.com/LuckyPray/DexKit)
- [LSPosed](https://github.com/LSPosed/LSPosed)

## 维护边界

由于本工程是根据安装包逆向恢复并由AI重新编写的代码，宿主升级后仍可能出现新的混淆、调用图或资源布局差异。遇到兼容问题时，应优先提交：宿主版本号、DexKit候选日志、相关Hook日志和可复现步骤；不要将当前工程视为百度输入法的原始源码或官方实现。

## 项目地址

<https://github.com/Sharl210/fuckinginput.coloros.baidu>

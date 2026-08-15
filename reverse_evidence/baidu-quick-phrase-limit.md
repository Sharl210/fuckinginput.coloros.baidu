# 百度输入法快捷短语上限证据

## 目标行为

目标是解除快捷短语数量达到100条时的新增入口阻断，同时保留正常的新增、保存和刷新流程。不能只屏蔽提示，因为原逻辑在提示分支同时跳过了新增页导航。

## 已定位调用链

宿主包：`com.baidu.input_oppo`。

### 通用管理页

`com.baidu.input.lazy.i->onClick(android.view.View)`的“添加”分支具有以下稳定特征：

- `View.getId()`后按添加入口分派；
- 读取快捷短语列表并调用`ArrayList.size()`；
- 使用常量`0x64`（100）；
- 达到阈值时调用同一类的无参提示方法，提示资源为`sym_collection_tip`；
- 未达到阈值时调用导航方法，参数为动作`0x52`和键值`"2"`。

### OPPO管理页

`com.baidu.input.ImeOppoLazyCorpusActivity->onOptionsItemSelected(android.view.MenuItem)`存在同一语义分支：

- 菜单项资源名为`add`；
- 读取列表大小并比较`0x64`；
- 超限调用提示方法；
- 正常路径调用动作`0x52`、键值`"2"`的新增页导航。

### 排除项

相同资源`sym_collection_tip`也被`com.baidu.jhe->G()`使用，但该类属于表情/符号收藏管理，不属于快捷短语链路，因此不匹配、不Hook，避免扩大行为范围。

## 实现策略

模块通过DexKit 2.2.0按上述签名、常量、调用关系和字段访问特征定位方法，不依赖`i`、`jhe`等混淆类名。达到阈值且点击添加入口时：

1. 从候选方法的`usingFields`快照解析实际列表字段；
2. 临时替换列表对象，使该调用链读取到99条，继续执行宿主原有新增对话框分支；
3. 同时从该候选方法的`invokes`解析无参提示辅助方法，仅在数量分支执行期间抑制Toast；
4. 方法结束后立即恢复真实列表，不触碰快捷短语保存、异步任务、广播刷新或文件存储；
5. 非快捷短语候选和表情/符号收藏候选不进入Hook。


## Toast资源追踪补充

资源`string/sym_collection_tip`被作为数量超限提示的稳定资源锚点。模块启动解析宿主APK时，先用独立`AssetManager`加载`apkPath`并从资源表静态解析该资源ID；随后把资源ID、100、`View.getId()`、`ArrayList.size()`和同类无参void调用关系交给DexKit查询。资源解析失败时退回到同一调用图的100条结构特征，不从运行时界面猜测资源ID。

命中后：

- 临时掩盖数量列表的`size`，使原方法继续走新增对话框分支；
- 对该数量分支直接调用的同类无参void提示辅助方法安装条件Hook，仅在该数量分支执行期间抑制Toast；
- 方法结束立即恢复真实列表；
- 不Hook同名资源在表情/符号收藏链上的`jhe->G()`，避免误伤。

可观察证据：`quick-phrase-limit-toast-resource id=...`、`quick-phrase-limit-toast-caller candidates=...`、`quick-phrase limit toast caller intercepted masked=...`、`quick-phrase limit toast helper suppressed: ...`和`quick-phrase limit toast bypassed`。


## 输入法顶部加号的精确分支

对8.5.302.769宿主静态读取确认：`resource:0x7f0a0776`的名称为`iv_add_corpus`。`Lw50/r`实现`View.OnClickListener`，其`onClick(View)`对该控件ID执行：读取按分类索引的`ArrayList`，`size >= 0x64`时调用`Lt90/r.k(Application, 0x50, "true", null)`；正常新增路径调用同一导航方法`Lt90/r.k(Application, 0x52, "1", null)`。因此当前精确Hook不再修改Toast或伪造列表：先静态解析`iv_add_corpus`和导航方法，再只在该点击回调期间把导航参数`0x50/"true"`重写为`0x52/"1"`，让宿主原有悬浮添加路径继续执行。

该改动不触碰快捷短语保存方法、异步任务、广播刷新或加密/文件存储。剪贴板的`Record`入库链路同样不在Hook范围内。

## 编辑目标修复

确认方法的宿主静态语义为：新增分支将条目插入列表0，编辑分支使用确认对象保存的原文本标识`c`和索引`d`更新原条目。当前保存器不再使用`usingFields`中第一个int字段作为编辑索引；编辑时先用原文本标识在当前列表条目中定位，若候选索引对应条目文本一致则优先使用该索引，否则使用文本唯一匹配结果；无法解析目标时停止保存，不覆盖列表头部。新增分支仍只插入列表0。

## 跨版本通杀匹配修复

不再把`0x50/"true"`或`0x52/"1"`当作跨版本固定契约。输入法加号Hook只依赖DexKit静态特征：`iv_add_corpus`资源名、`onClick(View)`、100条常量、快捷短语集合读取和同一导航调用关系；导航方法支持当前APK的三参数或四参数形式。超限判断使用的集合可能是直接`ArrayList`字段，也可能是`HashMap`中的`ArrayList`值，当前调用期间两种容器均可临时包装并在返回后恢复，使宿主自行走该版本真实的正常新增分支。

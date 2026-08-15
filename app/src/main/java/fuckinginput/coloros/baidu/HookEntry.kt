package fuckinginput.coloros.baidu

import android.content.Context
import android.content.DialogInterface
import android.text.InputFilter
import android.view.MenuItem
import android.view.View
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xposed entry for the OPPO-customized Baidu input method.
 *
 * All host methods are located from behavior and bytecode relationships. The
 * obfuscated class and method names are deliberately not part of the matching
 * contract.
 */
class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    companion object {
        private const val TARGET_PACKAGE = "com.baidu.input_oppo"
        private const val QUICK_PHRASE_ACTION = 0x52
        private const val QUICK_PHRASE_LIMIT = 100
        private const val QUICK_PHRASE_KEY = "2"
        private const val QUICK_PHRASE_KEY_INPUT = "1"
        private val copyFilterArmed = AtomicBoolean(false)
        private val quickPhraseLimitArmed = ThreadLocal.withInitial { false }

        @Volatile
        var modulePath: String = ""

        fun log(message: String) {
            XposedBridge.log("BaiduInputEnhance: $message")
        }

        init {
            System.loadLibrary("dexkit")
        }
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        val apkPath = lpparam.appInfo.sourceDir
        val hostClassLoader = lpparam.classLoader
        runCatching {
            DexKitBridge.create(apkPath).use { bridge ->
                val navigator = resolveQuickPhraseNavigator(bridge, hostClassLoader)
                hookClipboardCount(bridge, hostClassLoader)
                hookClipboardCopyNormalizer(bridge, hostClassLoader)
                hookClipboardHistoryFilter(bridge, hostClassLoader)
                val limitToastResourceId = resolveStaticResourceId(
                    apkPath,
                    "string",
                    "sym_collection_tip",
                )
                val addCorpusResourceId = resolveStaticResourceId(
                    apkPath,
                    "id",
                    "iv_add_corpus",
                )
                hookQuickPhraseInputAddDialog(
                    bridge,
                    hostClassLoader,
                    addCorpusResourceId,
                )
                hookQuickPhraseLimitToastCaller(bridge, hostClassLoader, limitToastResourceId)
                hookQuickPhraseEntryPoints(bridge, hostClassLoader, navigator)
                hookQuickPhraseDialogEntry(bridge, hostClassLoader)
                hookQuickPhraseConfirmation(bridge, hostClassLoader)
                hookQuickwords1Method(bridge, hostClassLoader)
                hookQuickwords2Method(bridge, hostClassLoader)
            }
        }.onFailure { error ->
            log("DexKit setup failed: ${error.stackTraceToString()}")
        }
    }

    private fun hookClipboardCount(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ) {
        val candidates = findMethods(bridge, "clipboard-count") {
            findFirst = true
            matcher {
                returnType("int")
                usingStrings(
                    listOf(
                        "select count(%1\$s) from %2\$s",
                        "clipboard",
                        "_id",
                    ),
                    StringMatchType.Equals,
                    true,
                )
            }
        }
        hookFirst(candidates, "clipboard-count", hostClassLoader) {
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // Preserve the original module behavior: expose the count as enabled.
                    param.result = 1
                }
            }
        }
    }

    private fun hookClipboardCopyNormalizer(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ) {
        val direct = findMethods(bridge, "clipboard-copy-normalizer-direct") {
            matcher {
                returnType("java.lang.String")
                paramTypes("java.lang.String")
                usingNumbers(listOf(7000))
                addInvoke("Ljava/lang/String;->length()I")
                addInvoke("Landroid/text/InputFilter\$LengthFilter;->filter(Ljava/lang/CharSequence;IILandroid/text/Spanned;II)Ljava/lang/CharSequence;")
            }
        }
        if (direct.isNotEmpty()) {
            hookAll(direct, "clipboard-copy-normalizer", hostClassLoader) {
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val source = param.args.firstOrNull() as? String ?: return
                        param.result = source
                        log("clipboard-copy normalizer bypassed length=${source.length}")
                    }
                }
            }
            return
        }

        val listeners = findMethods(bridge, "clipboard-copy-listener") {
            matcher {
                returnType("void")
                paramCount(0)
                usingStrings(
                    listOf("bdoppoime_ClipManager", "accepted clip content"),
                    StringMatchType.Contains,
                    false,
                )
                addInvoke("Ljava/lang/CharSequence;->length()I")
                addInvoke("Ljava/lang/String;->toString()Ljava/lang/String;")
            }
        }
        listeners.distinctBy { it.descriptor }.forEach { listener ->
            val normalizerData = runCatching {
                listener.invokes.firstOrNull {
                    it.returnTypeName == "java.lang.String" &&
                        it.paramTypeNames == listOf("java.lang.String") &&
                        it.className != "java.lang.String"
                }
            }.getOrNull()
            val normalizer = normalizerData?.let {
                runCatching { it.getMethodInstance(hostClassLoader) }.getOrNull()
            }
            if (normalizer != null) {
                XposedBridge.hookMethod(normalizer, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val source = param.args.firstOrNull() as? String ?: return
                        param.result = source
                        log("clipboard-copy normalizer bypassed length=${source.length}")
                    }
                })
                log("clipboard-copy-normalizer hooked: ${normalizerData.descriptor}")
            }
            hook(listener, "clipboard-copy-listener", hostClassLoader, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    copyFilterArmed.set(true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    copyFilterArmed.set(false)
                }
            })
        }
        hookClipboardFrameworkFilter(hostClassLoader)
    }

    private fun hookClipboardFrameworkFilter(hostClassLoader: ClassLoader) {
        val lengthFilterClass = runCatching {
            hostClassLoader.loadClass("android.text.InputFilter\$LengthFilter")
        }.getOrElse { InputFilter.LengthFilter::class.java }
        val hooked = XposedBridge.hookAllMethods(
            lengthFilterClass,
            "filter",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!copyFilterArmed.get()) return
                    val source = param.args.firstOrNull() as? CharSequence ?: return
                    if (source.length <= 7000) return
                    param.result = source.toString()
                    log("clipboard-copy bypassed length=${source.length}")
                }
            },
        )
        log("clipboard-copy-framework-filter methods=${hooked.size}")
    }

    private fun hookClipboardHistoryFilter(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ) {
        val candidates = findMethods(bridge, "clipboard-history-filter") {
            matcher {
                returnType("void")
                paramTypes("java.util.List")
                usingNumbers(listOf(14000))
                addInvoke("Ljava/util/List;->iterator()Ljava/util/Iterator;")
                addInvoke("Ljava/util/Iterator;->remove()V")
                addInvoke("Ljava/lang/String;->length()I")
                addInvoke {
                    returnType("java.lang.String")
                    paramCount(0)
                }
            }
        }
        hookAll(candidates, "clipboard-history", hostClassLoader) {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val input = param.args.firstOrNull() as? List<*> ?: return
                    val copy = ArrayList<Any?>(input.size)
                    copy.addAll(input)
                    param.args[0] = NoRemoveList(copy)
                    log("clipboard-history bypassed removals size=${input.size}")
                }
            }
        }
    }

    private fun resolveStaticResourceId(
        apkPath: String,
        resourceType: String,
        resourceName: String,
    ): Int {
        return runCatching {
            val assets = android.content.res.AssetManager::class.java
                .getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            val addAssetPath = assets.javaClass.getMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
            val cookie = (addAssetPath.invoke(assets, apkPath) as Number).toInt()
            if (cookie == 0) return@runCatching 0
            val resources = android.content.res.Resources(
                assets,
                android.util.DisplayMetrics(),
                android.content.res.Configuration(),
            )
            resources.getIdentifier(resourceName, resourceType, TARGET_PACKAGE)
        }.onFailure {
            log("static resource resolve failed type=$resourceType name=$resourceName: ${it.message}")
        }.getOrDefault(0)
            .also { id -> log("static resource id type=$resourceType name=$resourceName id=$id") }
    }
    private fun hookQuickPhraseEntryPoints(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
        navigator: QuickPhraseNavigator,
    ) {
        val common = findMethods(bridge, "quick-phrase-click") {
            matcher {
                name("onClick")
                returnType("void")
                paramTypes("android.view.View")
                usingNumbers(listOf(QUICK_PHRASE_LIMIT, 0x112, QUICK_PHRASE_ACTION))
                usingStrings(listOf(QUICK_PHRASE_KEY), StringMatchType.Equals, false)
                addInvoke("Landroid/view/View;->getId()I")
                addInvoke("Ljava/util/ArrayList;->size()I")
            }
        }
        hookAll(common, "quick-phrase-click", hostClassLoader) { method ->
            val fields = snapshotFieldRefs(method)
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args.firstOrNull() as? View ?: return
                    if (common.size > 1 && !isAddAction(view)) return
                    val owner = param.thisObject ?: return
                    val masked = maskQuickPhraseList(fields, owner, hostClassLoader)
                    if (masked.isNotEmpty()) {
                        param.setObjectExtra("quick-phrase-click-mask", masked)
                        log("quick-phrase dialog entry list masked count=${masked.size}")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    @Suppress("UNCHECKED_CAST")
                    val masked = param.getObjectExtra("quick-phrase-click-mask") as? List<ListSlot>
                    restoreQuickPhraseList(masked)
                    if (!masked.isNullOrEmpty()) {
                        log("quick-phrase dialog entry bypassed")
                    }
                }
            }
        }

        val addButtons = findMethods(bridge, "quick-phrase-add-button") {
            matcher {
                name("onClick")
                returnType("void")
                paramTypes("android.view.View")
                usingNumbers(listOf(QUICK_PHRASE_ACTION))
                usingStrings(listOf(QUICK_PHRASE_KEY), StringMatchType.Equals, false)
                addInvoke("Landroid/view/View;->getId()I")
                addInvoke {
                    returnType("boolean")
                    paramTypes("android.content.Context", "byte", "java.lang.String")
                    paramCount(3)
                }
            }
        }
        hookAll(addButtons, "quick-phrase-add-button", hostClassLoader) { method ->
            val fields = snapshotFieldRefs(method)
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args.firstOrNull() as? View ?: return
                    if (addButtons.size > 1 && !isAddAction(view)) return
                    val owner = param.thisObject ?: return
                    val masked = maskQuickPhraseList(fields, owner, hostClassLoader)
                    if (masked.isNotEmpty()) {
                        param.setObjectExtra("quick-phrase-button-mask", masked)
                        log("quick-phrase dialog entry list masked count=${masked.size}")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    @Suppress("UNCHECKED_CAST")
                    val masked = param.getObjectExtra("quick-phrase-button-mask") as? List<ListSlot>
                    restoreQuickPhraseList(masked)
                    if (!masked.isNullOrEmpty()) {
                        log("quick-phrase dialog entry bypassed")
                    }
                }
            }
        }

        val addButtonsDirect = findMethods(bridge, "quick-phrase-add-button-direct") {
            matcher {
                name("onClick")
                returnType("void")
                paramTypes("android.view.View")
                usingNumbers(listOf(QUICK_PHRASE_ACTION))
                usingStrings(listOf(QUICK_PHRASE_KEY), StringMatchType.Equals, false)
                addInvoke("Landroid/view/View;->getId()I")
                addInvoke {
                    returnType("boolean")
                    paramTypes(
                        "android.content.Context",
                        "byte",
                        "java.lang.String",
                        "android.os.Bundle",
                    )
                    paramCount(4)
                }
            }
        }
        hookAll(addButtonsDirect, "quick-phrase-add-button-direct", hostClassLoader) { method ->
            val fields = snapshotFieldRefs(method)
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args.firstOrNull() as? View ?: return
                    if (addButtonsDirect.size > 1 && !isAddAction(view)) return
                    val owner = param.thisObject ?: return
                    val masked = maskQuickPhraseList(fields, owner, hostClassLoader)
                    if (masked.isNotEmpty()) {
                        param.setObjectExtra("quick-phrase-button-direct-mask", masked)
                        log("quick-phrase dialog entry list masked count=${masked.size}")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    @Suppress("UNCHECKED_CAST")
                    val masked = param.getObjectExtra("quick-phrase-button-direct-mask") as? List<ListSlot>
                    restoreQuickPhraseList(masked)
                    if (!masked.isNullOrEmpty()) {
                        log("quick-phrase dialog entry bypassed")
                    }
                }
            }
        }

        val menu = findMethods(bridge, "quick-phrase-menu") {
            matcher {
                name("onOptionsItemSelected")
                returnType("boolean")
                paramTypes("android.view.MenuItem")
                usingNumbers(listOf(QUICK_PHRASE_LIMIT, 0x112, QUICK_PHRASE_ACTION))
                usingStrings(listOf(QUICK_PHRASE_KEY), StringMatchType.Equals, false)
                addInvoke("Landroid/view/MenuItem;->getItemId()I")
                addInvoke("Ljava/util/ArrayList;->size()I")
            }
        }
        hookAll(menu, "quick-phrase-menu", hostClassLoader) {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val item = param.args.firstOrNull() as? MenuItem ?: return
                    val context = param.thisObject as? Context ?: return
                    if (!isAddAction(item, context)) return
                    if (navigator.open(context)) {
                        param.result = true
                        log("quick-phrase add navigation bypassed entry=menu")
                    }
                }
            }
        }
    }

    private fun hookQuickPhraseInputAddDialog(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
        addCorpusResourceId: Int,
    ) {
        fun queryNavigatorCall(paramCount: Int, label: String): List<MethodData> {
            if (addCorpusResourceId == 0) return emptyList()
            return findMethods(bridge, label) {
                findFirst = true
                matcher {
                    name("onClick")
                    returnType("void")
                    paramTypes("android.view.View")
                    usingNumbers(listOf(addCorpusResourceId, QUICK_PHRASE_LIMIT))
                    addInvoke("Landroid/view/View;->getId()I")
                    addInvoke("Ljava/util/ArrayList;->size()I")
                    addInvoke {
                        returnType("boolean")
                        if (paramCount == 4) {
                            paramTypes(
                                "android.content.Context",
                                "byte",
                                "java.lang.String",
                                "android.os.Bundle",
                            )
                        } else {
                            paramTypes(
                                "android.content.Context",
                                "byte",
                                "java.lang.String",
                            )
                        }
                        this.paramCount(paramCount)
                    }
                }
            }
        }
        val strict = (
            queryNavigatorCall(4, "quick-phrase-input-add-dialog-4arg") +
                queryNavigatorCall(3, "quick-phrase-input-add-dialog-3arg")
            ).distinctBy { it.descriptor }
        val candidates = strict.ifEmpty {
            val structural4 = findMethods(bridge, "quick-phrase-input-add-dialog-structural-4arg") {
                findFirst = true
                matcher {
                    name("onClick")
                    returnType("void")
                    paramTypes("android.view.View")
                    usingNumbers(listOf(QUICK_PHRASE_LIMIT))
                    addInvoke("Landroid/view/View;->getId()I")
                    addInvoke("Ljava/util/ArrayList;->size()I")
                    addInvoke {
                        returnType("boolean")
                        paramTypes(
                            "android.content.Context",
                            "byte",
                            "java.lang.String",
                            "android.os.Bundle",
                        )
                        paramCount(4)
                    }
                }
            }
            val structural3 = findMethods(bridge, "quick-phrase-input-add-dialog-structural-3arg") {
                findFirst = true
                matcher {
                    name("onClick")
                    returnType("void")
                    paramTypes("android.view.View")
                    usingNumbers(listOf(QUICK_PHRASE_LIMIT))
                    addInvoke("Landroid/view/View;->getId()I")
                    addInvoke("Ljava/util/ArrayList;->size()I")
                    addInvoke {
                        returnType("boolean")
                        paramTypes(
                            "android.content.Context",
                            "byte",
                            "java.lang.String",
                        )
                        paramCount(3)
                    }
                }
            }
            (structural4 + structural3).distinctBy { it.descriptor }
        }
        hookAll(candidates, "quick-phrase-input-add-dialog", hostClassLoader) { method ->
            val fields = snapshotFieldRefs(method)
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args.firstOrNull() as? View ?: return
                    if (addCorpusResourceId != 0 && candidates.size > 1 && view.id != addCorpusResourceId) return
                    val owner = param.thisObject ?: return
                    val maskedLists = maskQuickPhraseList(fields, owner, hostClassLoader)
                    val maskedMapLists = maskQuickPhraseMapLists(fields, owner, hostClassLoader)
                    if (maskedLists.isNotEmpty() || maskedMapLists.isNotEmpty()) {
                        param.setObjectExtra("quick-phrase-input-list-mask", maskedLists)
                        param.setObjectExtra("quick-phrase-input-map-mask", maskedMapLists)
                        log(
                            "quick-phrase input add statically resolved " +
                                "directLists=${maskedLists.size} mapLists=${maskedMapLists.size}",
                        )
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    @Suppress("UNCHECKED_CAST")
                    val maskedLists = param.getObjectExtra("quick-phrase-input-list-mask") as? List<ListSlot>
                    @Suppress("UNCHECKED_CAST")
                    val maskedMapLists = param.getObjectExtra("quick-phrase-input-map-mask") as? List<MapListSlot>
                    restoreQuickPhraseList(maskedLists)
                    restoreQuickPhraseMapLists(maskedMapLists)
                    if (!maskedLists.isNullOrEmpty() || !maskedMapLists.isNullOrEmpty()) {
                        log("quick-phrase input add normal branch released")
                    }
                }
            }
        }
    }

    private fun hookQuickPhraseLimitToastCaller(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
        limitToastResourceId: Int,
    ) {
        log("quick-phrase-limit-toast-resource static id=$limitToastResourceId")
        val resourceToastMethods = if (limitToastResourceId != 0) {
            findMethods(bridge, "quick-phrase-toast-resource") {
                matcher {
                    returnType("void")
                    usingNumbers(listOf(limitToastResourceId))
                    addInvoke {
                        declaredClass("android.widget.Toast")
                        name("makeText")
                        returnType("android.widget.Toast")
                        paramCount(3)
                    }
                }
            }
        } else {
            emptyList()
        }
        val resourceCallers = resourceToastMethods.flatMap { toastMethod ->
            findMethods(bridge, "quick-phrase-toast-resource-caller") {
                matcher {
                    returnType("void")
                    usingNumbers(listOf(QUICK_PHRASE_LIMIT))
                    addInvoke(toastMethod.descriptor)
                }
            }
        }.distinctBy { it.descriptor }
        if (resourceToastMethods.isNotEmpty()) {
            log("quick-phrase-toast-resource-methods=${resourceToastMethods.size}")
        }
        val resourcePageMethods = resourceToastMethods.flatMap { toastMethod ->
            findMethods(bridge, "quick-phrase-toast-page-caller") {
                matcher {
                    name("setPage")
                    returnType("void")
                    paramTypes("int")
                    addInvoke(toastMethod.descriptor)
                }
            }
        }.distinctBy { it.descriptor }
        val upstreamOnClick = collectOnClickCallers(resourceCallers + resourcePageMethods)
        (resourceCallers + resourcePageMethods + upstreamOnClick)
            .distinctBy { it.descriptor }
            .forEach { method ->
                log("quick-phrase-toast-chain method=${method.descriptor}")
            }
        val candidates = (resourceCallers + resourcePageMethods + upstreamOnClick)
            .distinctBy { it.descriptor }
            .ifEmpty {
            findMethods(bridge, "quick-phrase-limit-toast-caller-structural") {
                matcher {
                    returnType("void")
                    usingNumbers(listOf(QUICK_PHRASE_LIMIT))
                    addInvoke("Ljava/util/ArrayList;->size()I")
                    addInvoke {
                        returnType("void")
                        paramCount(0)
                    }
                }
            }
        }
        hookAll(candidates, "quick-phrase-limit-toast-caller", hostClassLoader) { method ->
            val fields = snapshotFieldRefs(method)
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject ?: return
                    val masked = maskQuickPhraseList(fields, owner, hostClassLoader)
                    if (masked.isNotEmpty()) {
                        param.setObjectExtra("quick-phrase-limit-toast-mask", masked)
                        quickPhraseLimitArmed.set(true)
                        log("quick-phrase limit toast caller intercepted masked=${masked.size}")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    @Suppress("UNCHECKED_CAST")
                    val masked = param.getObjectExtra("quick-phrase-limit-toast-mask") as? List<ListSlot>
                    restoreQuickPhraseList(masked)
                    quickPhraseLimitArmed.set(false)
                    if (!masked.isNullOrEmpty()) {
                        log("quick-phrase limit toast bypassed")
                    }
                }
            }
        }
        hookQuickPhraseToastHelpers(candidates, hostClassLoader)
    }

    private fun collectOnClickCallers(roots: List<MethodData>): List<MethodData> {
        val result = ArrayList<MethodData>()
        val visited = HashSet<String>()

        fun visit(method: MethodData, depth: Int) {
            if (depth > 2 || !visited.add(method.descriptor)) return
            runCatching { method.callers }.getOrDefault(emptyList()).forEach { caller ->
                if (caller.name == "onClick" && caller.paramTypeNames == listOf("android.view.View")) {
                    result += caller
                } else {
                    visit(caller, depth + 1)
                }
            }
        }
        roots.forEach { visit(it, 0) }
        return result.distinctBy { it.descriptor }
    }
    private fun hookQuickPhraseToastHelpers(
        callers: List<MethodData>,
        hostClassLoader: ClassLoader,
    ) {
        val helpers = callers.flatMap { caller ->
            runCatching {
                caller.invokes.filter {
                    it.returnTypeName == "void" && it.paramTypeNames.isEmpty()
                }
            }.getOrDefault(emptyList())
        }.distinctBy { it.descriptor }
        helpers.forEach { helper ->
            hook(helper, "quick-phrase-limit-toast-helper", hostClassLoader, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (quickPhraseLimitArmed.get() != true) return
                    param.result = null
                    log("quick-phrase limit toast helper suppressed: ${helper.descriptor}")
                }
            })
        }
        if (helpers.isNotEmpty()) {
            log("quick-phrase-limit-toast-helpers=${helpers.size}")
        }
    }
    private fun hookQuickPhraseDialogEntry(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ) {
        val candidates = findMethods(bridge, "quick-phrase-dialog-entry") {
            matcher {
                name("onClick")
                returnType("void")
                paramTypes("android.view.View")
                usingNumbers(listOf(QUICK_PHRASE_LIMIT))
                addInvoke("Landroid/view/View;->getId()I")
                addInvoke("Ljava/util/ArrayList;->size()I")
            }
        }
        hookAll(candidates, "quick-phrase-dialog-entry", hostClassLoader) { method ->
            val fields = snapshotFieldRefs(method)
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args.firstOrNull() as? View ?: return
                    if (candidates.size > 1 && !isAddAction(view)) return
                    val owner = param.thisObject ?: return
                    val slots = maskQuickPhraseList(fields, owner, hostClassLoader)
                    if (slots.isNotEmpty()) {
                        param.setObjectExtra("quick-phrase-dialog-entry-mask", slots)
                        log("quick-phrase dialog entry list masked count=${slots.size}")
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    @Suppress("UNCHECKED_CAST")
                    val masked = param.getObjectExtra("quick-phrase-dialog-entry-mask")
                        as? List<ListSlot>
                    restoreQuickPhraseList(masked)
                    if (!masked.isNullOrEmpty()) {
                        log("quick-phrase dialog entry bypassed")
                    }
                }
            }
        }
    }
    private fun hookQuickPhraseConfirmation(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ) {
        val strict = findMethods(bridge, "quick-phrase-confirmation-strict") {
            findFirst = true
            matcher {
                name("onClick")
                returnType("void")
                paramTypes("android.content.DialogInterface", "int")
                usingNumbers(listOf(300))
                usingStrings(
                    listOf("dialog", "couiInputView"),
                    StringMatchType.Equals,
                    false,
                )
                addInvoke("Ljava/lang/String;->length()I")
                addInvoke("Landroid/content/DialogInterface;->dismiss()V")
            }
        }
        val candidates = strict.ifEmpty {
            findMethods(bridge, "quick-phrase-confirmation-structural") {
                findFirst = true
                matcher {
                    name("onClick")
                    returnType("void")
                    paramTypes("android.content.DialogInterface", "int")
                    usingNumbers(listOf(300))
                    addInvoke("Ljava/lang/String;->length()I")
                    addInvoke("Landroid/content/DialogInterface;->dismiss()V")
                    addInvoke("Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;")
                }
            }
        }
        val methodData = candidates.firstOrNull() ?: return
        val saver = QuickPhraseSaver.from(methodData, hostClassLoader)
        hook(methodData, "quick-phrase-confirmation", hostClassLoader, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.getOrNull(1) as? Int != -1) return
                val owner = param.thisObject ?: return
                val dialog = param.args.firstOrNull() as? android.content.DialogInterface ?: return
                val text = readQuickPhraseText(owner) ?: return
                if (text.length <= 300) return
                if (saver.save(owner, text)) {
                    dialog.dismiss()
                    param.result = null
                    log("quick-phrase-confirmation bypassed length=${text.length}")
                }
            }
        })
        log("quick-phrase-saver methods=${saver.resolvedMethodCount}")
    }

    private fun readQuickPhraseText(owner: Any): String? {
        val input = findObjectWithMethod(owner, "getEditText") ?: return null
        val editText = runCatching {
            input.javaClass.getMethod("getEditText").invoke(input)
        }.getOrNull() ?: return null
        return runCatching {
            editText.javaClass.getMethod("getText").invoke(editText)?.toString()
        }.getOrNull()
    }

    private fun findObjectWithMethod(owner: Any, methodName: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull() ?: return@forEach
                if (value.javaClass.methods.any { it.name == methodName && it.parameterCount == 0 }) {
                    return value
                }
            }
            type = type.superclass
        }
        return null
    }

    private class QuickPhraseSaver private constructor(
        private val categoryId: Method?,
        private val categoryField: FieldRef?,
        private val editFields: List<FieldRef>,
        private val categoryPath: Method?,
        private val corpusPath: Method?,
        private val loadCorpus: Method?,
        private val saveCorpus: Method?,
        private val entryClassName: String?,
        private val managerFactory: Method?,
        private val managerRefresh: Method?,
        private val hostClassLoader: ClassLoader,
    ) {
        val resolvedMethodCount: Int
            get() = listOf(
                categoryId,
                categoryPath,
                corpusPath,
                loadCorpus,
                saveCorpus,
                managerFactory,
                managerRefresh,
            ).count { it != null }

        companion object {
            fun from(method: MethodData, hostClassLoader: ClassLoader): QuickPhraseSaver {
                val invokes = runCatching { method.invokes }.getOrDefault(emptyList())
                val fields = runCatching {
                    method.usingFields.map { using ->
                        val field = using.field
                        FieldRef(
                            className = field.className,
                            fieldName = field.fieldName,
                            typeName = field.typeName,
                            isStatic = Modifier.isStatic(field.modifiers),
                        )
                    }
                }.getOrDefault(emptyList())
                fun pickData(predicate: (MethodData) -> Boolean): MethodData? =
                    invokes.firstOrNull(predicate)
                fun reflect(data: MethodData?): Method? = data?.let {
                    runCatching { it.getMethodInstance(hostClassLoader) }.getOrNull()
                }

                val staticFieldTypes = fields.filter { it.isStatic }.map { it.typeName }.toSet()
                val categoryIdData = pickData {
                    it.returnTypeName == "int" && it.paramTypeNames.isEmpty() &&
                        staticFieldTypes.contains(it.className)
                }
                val categoryPathData = pickData {
                    it.returnTypeName == "java.lang.String" &&
                        it.paramTypeNames == listOf("int") &&
                        it.usingStrings.any { value -> value.contains("lazy/") }
                }
                val corpusPathData = pickData {
                    it.returnTypeName == "java.lang.String" &&
                        it.paramTypeNames == listOf("int") &&
                        it.usingStrings.any { value -> value.contains("corpus.ini") }
                }
                val loadData = pickData {
                    it.paramTypeNames == listOf("java.lang.String", "boolean") &&
                        it.returnTypeName != "void"
                }
                val holderType = loadData?.returnTypeName
                val saveData = pickData {
                    it.returnTypeName == "boolean" &&
                        it.paramTypeNames.size == 2 &&
                        it.paramTypeNames[0] == "java.lang.String" &&
                        holderType != null && it.paramTypeNames[1] == holderType
                }
                val voidNoArgClasses = invokes.filter {
                    it.returnTypeName == "void" && it.paramTypeNames.isEmpty()
                }.map { it.className }.toSet()
                val managerData = pickData {
                    it.returnTypeName != "void" && it.paramTypeNames.isEmpty() &&
                        voidNoArgClasses.contains(it.returnTypeName)
                }
                val managerClass = managerData?.returnTypeName
                val managerRefreshData = pickData {
                    it.className == managerClass &&
                        it.returnTypeName == "void" && it.paramTypeNames.isEmpty()
                }
                val entryData = pickData {
                    it.name == "<init>" && it.paramTypeNames.isEmpty() &&
                        !it.className.startsWith("android") &&
                        !it.className.startsWith("java")
                }
                return QuickPhraseSaver(
                    categoryId = reflect(categoryIdData),
                    categoryField = fields.firstOrNull {
                        it.isStatic && categoryIdData != null && it.typeName == categoryIdData.className
                    },
                    editFields = fields.filter {
                        !it.isStatic && (it.typeName == "java.lang.String" || it.typeName == "int")
                    },
                    categoryPath = reflect(categoryPathData),
                    corpusPath = reflect(corpusPathData),
                    loadCorpus = reflect(loadData),
                    saveCorpus = reflect(saveData),
                    entryClassName = entryData?.className,
                    managerFactory = reflect(managerData),
                    managerRefresh = reflect(managerRefreshData),
                    hostClassLoader = hostClassLoader,
                )
            }
        }

        fun save(owner: Any, text: String): Boolean {
            val idMethod = categoryId ?: return false
            val pathMethod = categoryPath ?: return false
            val corpusMethod = corpusPath ?: return false
            val loadMethod = loadCorpus ?: return false
            val saveMethod = saveCorpus ?: return false
            return runCatching {
                val idReceiver = if (Modifier.isStatic(idMethod.modifiers)) {
                    null
                } else {
                    categoryField?.let { field ->
                        findField(field.className, field.fieldName)?.get(null)
                    }
                } ?: return false
                val id = (idMethod.invoke(idReceiver) as Number).toInt()
                val categoryPathValue = pathMethod.invoke(null, id) as String
                val holder = loadMethod.invoke(null, categoryPathValue, true) ?: return false
                val listField = findCollectionField(holder) ?: return false
                @Suppress("UNCHECKED_CAST")
                val list = (listField.get(holder) as? MutableList<Any?>) ?: return false
                val resolvedEditFields = editFields.mapNotNull { ref ->
                    findField(ref.className, ref.fieldName)
                }
                val editMarker = resolvedEditFields.firstOrNull { it.type == String::class.java }
                    ?.get(owner) as? String
                val requestedIndexes = resolvedEditFields
                    .filter { it.type == Integer.TYPE }
                    .mapNotNull { field ->
                        runCatching { (field.get(owner) as Number).toInt() }.getOrNull()
                    }
                if (editMarker == null) {
                    val entryClass = entryClassName?.let {
                        runCatching { hostClassLoader.loadClass(it) }.getOrNull()
                    } ?: list.firstOrNull()?.javaClass ?: return false
                    val entry = entryClass.getDeclaredConstructor().newInstance()
                    findStringField(entry.javaClass)?.set(entry, text)
                    list.add(0, entry)
                } else {
                    val index = resolveEditIndex(list, editMarker, requestedIndexes)
                    log(
                        "quick-phrase edit target markerLength=${editMarker.length} " +
                            "requested=$requestedIndexes resolved=$index listSize=${list.size}",
                    )
                    val entry = index?.let { list.getOrNull(it) } ?: return false
                    findStringField(entry.javaClass)?.set(entry, text)
                }
                val corpusPathValue = corpusMethod.invoke(null, id) as String
                val saved = saveMethod.invoke(null, corpusPathValue, holder) as? Boolean == true
                if (saved && managerFactory != null && managerRefresh != null) {
                    val manager = managerFactory.invoke(null)
                    managerRefresh.invoke(manager)
                }
                saved
            }.onFailure { log("quick-phrase-saver failed: ${it.stackTraceToString()}") }
                .getOrDefault(false)
        }

        private fun resolveEditIndex(
            list: List<Any?>,
            marker: String,
            requestedIndexes: List<Int>,
        ): Int? {
            val requestedMatch = requestedIndexes.firstOrNull { index ->
                index in list.indices && readEntryText(list[index]) == marker
            }
            if (requestedMatch != null) return requestedMatch
            val markerMatch = list.indexOfFirst { entry -> readEntryText(entry) == marker }
            return markerMatch.takeIf { it >= 0 }
        }

        private fun readEntryText(entry: Any?): String? {
            if (entry == null) return null
            return runCatching { findStringField(entry.javaClass)?.get(entry) as? String }.getOrNull()
        }
        private fun findField(typeName: String, name: String): java.lang.reflect.Field? {
            val type = runCatching { hostClassLoader.loadClass(typeName) }.getOrNull() ?: return null
            return findDeclaredField(type, name)
        }


        private fun findDeclaredField(type: Class<*>, name: String): java.lang.reflect.Field? {
            var current: Class<*>? = type
            while (current != null) {
                val field = runCatching { current.getDeclaredField(name) }.getOrNull()
                if (field != null) return field.apply { isAccessible = true }
                current = current.superclass
            }
            return null
        }

        private fun findCollectionField(owner: Any): java.lang.reflect.Field? {
            var type: Class<*>? = owner.javaClass
            while (type != null) {
                val field = type.declaredFields.firstOrNull {
                    java.util.Collection::class.java.isAssignableFrom(it.type)
                }
                if (field != null) return field.apply { isAccessible = true }
                type = type.superclass
            }
            return null
        }

        private fun findStringField(type: Class<*>): java.lang.reflect.Field? {
            var current: Class<*>? = type
            while (current != null) {
                val field = current.declaredFields.firstOrNull { it.type == String::class.java }
                if (field != null) return field.apply { isAccessible = true }
                current = current.superclass
            }
            return null
        }
    }

    private fun resolveQuickPhraseNavigator(
        bridge: DexKitBridge,
        hostClassLoader: ClassLoader,
    ): QuickPhraseNavigator {
        val fourArg = findMethods(bridge, "quick-phrase-navigator-4arg") {
            findFirst = true
            matcher {
                returnType("boolean")
                paramTypes(
                    "android.content.Context",
                    "byte",
                    "java.lang.String",
                    "android.os.Bundle",
                )
                modifiers(Modifier.STATIC)
                usingStrings(listOf("key_uid"), StringMatchType.Equals, false)
            }
        }
        val candidates = fourArg.ifEmpty {
            findMethods(bridge, "quick-phrase-navigator-3arg") {
                findFirst = true
                matcher {
                    returnType("boolean")
                    paramTypes(
                        "android.content.Context",
                        "byte",
                        "java.lang.String",
                    )
                    modifiers(Modifier.STATIC)
                    addCaller {
                        usingNumbers(listOf(0x50, 0x52))
                    }
                }
            }
        }
        val method = candidates.firstOrNull()?.let {
            runCatching { it.getMethodInstance(hostClassLoader) }
                .onFailure { log("quick-phrase-navigator resolve failed: ${it.message}") }
                .getOrNull()
        }
        log(
            "quick-phrase-navigator candidates=${candidates.size}, " +
                "resolved=${method != null}, params=${method?.parameterTypes?.size ?: 0}",
        )
        return QuickPhraseNavigator(method)
    }

    private fun hookQuickwords1Method(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val method = findMethods(bridge, "quickwords-1") {
            findFirst = true
            matcher {
                name("onTextChanged")
                usingStrings(listOf("couiInputView"), StringMatchType.Equals, true)
            }
        }.firstOrNull()
        hookFirst(method?.let(::listOf).orEmpty(), "quickwords-1", hostClassLoader) {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            }
        }
    }

    private fun hookQuickwords2Method(bridge: DexKitBridge, hostClassLoader: ClassLoader) {
        val strict = findMethods(bridge, "quickwords-2-strict") {
            findFirst = true
            matcher {
                usingNumbers(listOf(300))
                returnType("boolean")
                declaredClass {
                    usingStrings(listOf("couiInputView"), StringMatchType.Equals, true)
                }
            }
        }
        val method = (strict.ifEmpty {
            findMethods(bridge, "quickwords-2-fallback") {
                findFirst = true
                matcher {
                    name("onTextChanged")
                    returnType("void")
                    usingNumbers(listOf(300))
                    usingStrings(listOf("couiInputView"), StringMatchType.Equals, true)
                }
            }
        }).firstOrNull()
        hookFirst(method?.let(::listOf).orEmpty(), "quickwords-2", hostClassLoader) {
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val method = param.method as? Method
                    param.result = if (method?.returnType == Boolean::class.javaPrimitiveType) true else null
                }
            }
        }
    }

    private fun findMethods(
        bridge: DexKitBridge,
        label: String,
        init: org.luckypray.dexkit.query.FindMethod.() -> Unit,
    ): List<MethodData> {
        return runCatching { bridge.findMethod(init) }
            .onFailure { log("$label query failed: ${it.message}") }
            .getOrDefault(emptyList())
            .also { log("$label candidates=${it.size}") }
    }

    private fun hookFirst(
        candidates: List<MethodData>,
        label: String,
        hostClassLoader: ClassLoader,
        callback: () -> XC_MethodHook,
    ) {
        val candidate = candidates.firstOrNull() ?: return
        if (candidates.size > 1) {
            log("$label ambiguous; selecting ${candidate.descriptor}")
        }
        hook(candidate, label, hostClassLoader, callback())
    }

    private fun hookAll(
        candidates: List<MethodData>,
        label: String,
        hostClassLoader: ClassLoader,
        callback: (MethodData) -> XC_MethodHook,
    ) {
        candidates.distinctBy { it.descriptor }.forEach { candidate ->
            hook(candidate, label, hostClassLoader, callback(candidate))
        }
    }

    private fun hook(
        data: MethodData,
        label: String,
        hostClassLoader: ClassLoader,
        callback: XC_MethodHook,
    ) {
        runCatching {
            XposedBridge.hookMethod(data.getMethodInstance(hostClassLoader), callback)
            log("$label hooked: ${data.descriptor}")
        }.onFailure { error ->
            log("$label hook failed (${data.descriptor}): ${error.stackTraceToString()}")
        }
    }

    private class NoRemoveList<T>(values: Collection<T>) : ArrayList<T>(values) {
        override fun iterator(): MutableIterator<T> {
            val delegate = super.iterator()
            return object : MutableIterator<T> {
                override fun hasNext(): Boolean = delegate.hasNext()
                override fun next(): T = delegate.next()
                override fun remove() = Unit
            }
        }

        override fun listIterator(): MutableListIterator<T> {
            val delegate = super.listIterator()
            return object : MutableListIterator<T> {
                override fun hasNext(): Boolean = delegate.hasNext()
                override fun next(): T = delegate.next()
                override fun hasPrevious(): Boolean = delegate.hasPrevious()
                override fun previous(): T = delegate.previous()
                override fun nextIndex(): Int = delegate.nextIndex()
                override fun previousIndex(): Int = delegate.previousIndex()
                override fun add(element: T) = delegate.add(element)
                override fun set(element: T) = delegate.set(element)
                override fun remove() = Unit
            }
        }
    }
    private data class FieldRef(
        val className: String,
        val fieldName: String,
        val typeName: String,
        val isStatic: Boolean,
    )

    private data class ListSlot(
        val holder: Any,
        val field: java.lang.reflect.Field,
        val original: ArrayList<Any?>,
    )

    private data class MapListSlot(
        val map: MutableMap<Any?, Any?>,
        val key: Any?,
        val original: ArrayList<Any?>,
    )

    private class BelowLimitList<T>(values: Collection<T>) : ArrayList<T>(values) {
        override val size: Int
            get() = minOf(super.size, QUICK_PHRASE_LIMIT - 1)
    }

    private fun snapshotFieldRefs(method: MethodData): List<FieldRef> {
        return runCatching {
            method.usingFields.map { using ->
                val field = using.field
                FieldRef(
                    className = field.className,
                    fieldName = field.fieldName,
                    typeName = field.typeName,
                    isStatic = Modifier.isStatic(field.modifiers),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun findListSlots(
        fields: List<FieldRef>,
        receiver: Any,
        hostClassLoader: ClassLoader,
    ): List<ListSlot> {
        val result = ArrayList<ListSlot>()
        val visited = IdentityHashMap<Any, Boolean>()

        fun walk(holder: Any, depth: Int) {
            if (depth > 3 || visited.put(holder, true) != null) return
            fields.forEach { field ->
                val declaringClass = runCatching { hostClassLoader.loadClass(field.className) }.getOrNull()
                    ?: return@forEach
                if (!declaringClass.isAssignableFrom(holder.javaClass)) return@forEach
                val reflected = findDeclaredField(declaringClass, field.fieldName)
                    ?: return@forEach
                val target = if (field.isStatic) null else holder
                val value = runCatching { reflected.get(target) }.getOrNull() ?: return@forEach
                @Suppress("UNCHECKED_CAST")
                if (value is ArrayList<*>) {
                    result += ListSlot(holder, reflected, value as ArrayList<Any?>)
                } else if (value !is String && !value.javaClass.isPrimitive) {
                    walk(value, depth + 1)
                }
            }
        }
        walk(receiver, 0)
        return result.distinctBy { "${System.identityHashCode(it.holder)}:${it.field.name}" }
    }

    private fun maskQuickPhraseList(
        fields: List<FieldRef>,
        owner: Any,
        hostClassLoader: ClassLoader,
    ): List<ListSlot> {
        return findListSlots(fields, owner, hostClassLoader)
            .filter { it.original.size >= QUICK_PHRASE_LIMIT }
            .mapNotNull { slot ->
                runCatching {
                    slot.field.set(slot.holder, BelowLimitList(slot.original))
                    slot
                }.getOrNull()
            }
    }

    private fun restoreQuickPhraseList(masked: List<ListSlot>?) {
        masked?.forEach { slot ->
            runCatching { slot.field.set(slot.holder, slot.original) }
        }
    }

    private fun maskQuickPhraseMapLists(
        fields: List<FieldRef>,
        receiver: Any,
        hostClassLoader: ClassLoader,
    ): List<MapListSlot> {
        val result = ArrayList<MapListSlot>()
        val visited = IdentityHashMap<Any, Boolean>()

        fun walk(holder: Any, depth: Int) {
            if (depth > 3 || visited.put(holder, true) != null) return
            fields.forEach { field ->
                val declaringClass = runCatching { hostClassLoader.loadClass(field.className) }.getOrNull()
                    ?: return@forEach
                if (!declaringClass.isAssignableFrom(holder.javaClass)) return@forEach
                val reflected = findDeclaredField(declaringClass, field.fieldName)
                    ?: return@forEach
                val target = if (field.isStatic) null else holder
                val value = runCatching { reflected.get(target) }.getOrNull() ?: return@forEach
                if (value is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val map = value as MutableMap<Any?, Any?>
                    map.entries.toList().forEach { entry ->
                        @Suppress("UNCHECKED_CAST")
                        val list = entry.value as? ArrayList<Any?> ?: return@forEach
                        if (list.size < QUICK_PHRASE_LIMIT) return@forEach
                        runCatching {
                            map[entry.key] = BelowLimitList(list)
                            result += MapListSlot(map, entry.key, list)
                        }
                    }
                } else if (value is ArrayList<*>) {
                    // Direct ArrayList fields are handled by maskQuickPhraseList.
                } else if (!value.javaClass.isPrimitive && value !is String) {
                    walk(value, depth + 1)
                }
            }
        }
        walk(receiver, 0)
        return result.distinctBy { "${System.identityHashCode(it.map)}:${it.key}" }
    }

    private fun restoreQuickPhraseMapLists(masked: List<MapListSlot>?) {
        masked?.forEach { slot ->
            runCatching { slot.map[slot.key] = slot.original }
        }
    }

    private fun findDeclaredField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) return field.apply { isAccessible = true }
            current = current.superclass
        }
        return null
    }
    private fun isAddAction(view: View): Boolean {
        val name = runCatching { view.resources.getResourceEntryName(view.id) }
            .getOrDefault("")
            .lowercase()
        if (
            name == "add" || name == "add_bottom" ||
            name.contains("add") || name.contains("quick") ||
            name.contains("phrase") || name.contains("corpus") || name.contains("lazy")
        ) return true
        val text = listOf(view.contentDescription, view.tag)
            .joinToString(" ") { it?.toString().orEmpty() }
            .lowercase()
        return text.contains("添加") || text.contains("add") ||
            text.contains("新增") || text.contains("快捷") ||
            text.contains("短语") || text.contains("quick") ||
            text.contains("phrase") || text.contains("corpus")
    }

    private fun isAddAction(item: MenuItem, context: Context): Boolean {
        val title = item.title?.toString().orEmpty().lowercase()
        if (title.contains("添加") || title.contains("add") || title.contains("新增")) return true
        val name = runCatching {
            context.resources.getResourceEntryName(item.itemId)
        }.getOrDefault("").lowercase()
        return name == "add" || name.contains("add")
    }

    private class QuickPhraseNavigator(private val method: Method?) {
        fun open(context: Context): Boolean =
            open(context, QUICK_PHRASE_ACTION.toByte(), QUICK_PHRASE_KEY)

        fun open(context: Context, action: Byte, key: String): Boolean {
            val target = method ?: return false
            return runCatching {
                if (target.parameterTypes.size == 3) {
                    (target.invoke(null, context, action, key) as? Boolean) == true
                } else {
                    (target.invoke(null, context, action, key, null) as? Boolean) == true
                }
            }.onFailure { log("quick-phrase navigator invocation failed: ${it.stackTraceToString()}") }
                .getOrDefault(false)
        }
    }
}

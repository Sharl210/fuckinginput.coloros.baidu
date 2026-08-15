# 构建升级说明

## 版本基线

- Android Gradle Plugin：9.3.1
- Gradle Wrapper：9.7.0
- DexKit：2.2.0（Maven Central）
- Kotlin：使用AGP 9的内置Kotlin编译支持，JVM目标11
- compileSdk：37
- targetSdk：33（保留宿主兼容行为）
- minSdk：23

## ARM64构建

Google发布的Linux构建工具通常提供x86_64版`aapt2`。ARM64环境需要提供能够解析compileSdk 37资源表的`aapt2`，并通过环境变量传入：

```bash
AAPT2_OVERRIDE=/path/to/aapt2 ./tools/build-release.sh
```

脚本会校验该文件可执行，并将其交给AGP。不要用只能解析旧Android平台的系统`aapt2`替代它。

## 验证边界

当前工程环境已完成clean release、R8、Lint、DEX/资源/签名和静态特征审计；没有可调用的Xposed宿主设备，因此未伪造Hook注册日志或运行时行为通过结论。安装到目标宿主后，应按`reverse_evidence/final-release-audit.txt`补做动态回归。

DexKit 2.2保留查询入口，但查询匹配器的属性写入API已改为显式函数调用；Hook解析改用`MethodData.getMethodInstance(ClassLoader)`，不再在模块内重复实现基本类型和数组类型映射。查询结果只按行为特征和调用关系使用，混淆类名不属于契约。

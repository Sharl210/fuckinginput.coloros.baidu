# Xposed loads this class by the name stored in assets/xposed_init.
-keep class fuckinginput.coloros.baidu.HookEntry { *; }

# DexKit's AAR contributes its own JNI keep rules. Keep the public bridge name
# and native members explicit so a rebuilt APK remains compatible with libdexkit.so.
-keepclasseswithmembers,includedescriptorclasses class org.luckypray.dexkit.** {
    native <methods>;
    long token;
}

-dontwarn de.robv.android.xposed.**

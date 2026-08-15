#!/usr/bin/env python3
"""Static release-contract checks for the upgraded module."""
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HOOK = ROOT / "app/src/main/java/fuckinginput/coloros/baidu/HookEntry.kt"
BUILD = ROOT / "app/build.gradle"
ROOT_BUILD = ROOT / "build.gradle"
WRAPPER = ROOT / "gradle/wrapper/gradle-wrapper.properties"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
APK = ROOT / "app/build/outputs/apk/release/app-release.apk"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise AssertionError(f"missing {label}: {needle}")


def main() -> int:
    hook = HOOK.read_text(encoding="utf-8")
    build = BUILD.read_text(encoding="utf-8")
    root_build = ROOT_BUILD.read_text(encoding="utf-8")
    wrapper = WRAPPER.read_text(encoding="utf-8")
    manifest = MANIFEST.read_text(encoding="utf-8")

    require(root_build, "com.android.application' version '9.3.1'", "AGP version")
    require(wrapper, "gradle-9.7.0-bin.zip", "Gradle wrapper version")
    for dep in (
        "androidx.core:core-ktx:1.19.0",
        "androidx.appcompat:appcompat:1.8.0",
        "com.google.android.material:material:1.14.0",
        "androidx.constraintlayout:constraintlayout:2.2.2",
        "org.luckypray:dexkit:2.2.0",
    ):
        require(build, dep, dep)
    require(build, "compileSdk 37", "compile SDK")
    require(build, "targetSdk 33", "target SDK preservation")
    require(build, "jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11", "Kotlin compiler target")

    if re.search(r"keyboardSearch|keyboard_search", hook, re.IGNORECASE):
        raise AssertionError("obsolete search-button blocker remains in HookEntry")
    for forbidden in (
        "ImeLazyCorpusAddActivity",
        "ImeOppoLazyCorpusActivity",
        "LazyCorpusManger$DefaultLazy",
        "w50.t",
        "Ldu/",
        "Lw50/",
        "Ltl/",
    ):
        if forbidden in hook:
            raise AssertionError(f"single-version host anchor remains in HookEntry: {forbidden}")
    for feature in (
        "clipboard-count",
        "select count(%1\\$s) from %2\\$s",
        "clipboard-copy-listener",
        "clipboard-copy-normalizer",
        "NoRemoveList",
        "hookAllMethods",
        "clipboard-history-filter",
        "quick-phrase-click",
        "quick-phrase-add-button",
        "quick-phrase-add-button-direct",
        "quick-phrase-menu",
        "quick-phrase-dialog-entry",
        "quick-phrase-navigator-3arg",
        "quick-phrase-input-add-dialog-3arg",
        "quick-phrase-input-add-dialog",
        "iv_add_corpus",
        "quick-phrase-toast-resource",
        "quick-phrase-toast-resource-caller",
        "quick-phrase-toast-page-caller",
        "quick-phrase-limit-toast-caller",
        "quick-phrase-limit-toast-helper",
        "sym_collection_tip",
        "BelowLimitList",
        "findListSlots",
        "quick-phrase edit target",
        "QuickPhraseSaver",
        "quick-phrase-saver",
        "quickwords-2-fallback",
        "quick-phrase-confirmation",
        "QUICK_PHRASE_LIMIT = 100",
        "usingNumbers(listOf(7000))",
        "usingNumbers(listOf(14000))",
        "getMethodInstance(hostClassLoader)",
    ):
        require(hook, feature, feature)
    if "searchPackages(\"com\")" in hook:
        raise AssertionError("DexKit queries still restrict discovery to com package")
    if "<uses-sdk" in manifest:
        raise AssertionError("AGP 9 main manifest still owns SDK versions")

    if not APK.is_file():
        raise AssertionError(f"release APK not found: {APK}")
    with zipfile.ZipFile(APK) as zf:
        names = set(zf.namelist())
        require("\n".join(names), "assets/xposed_init", "Xposed entry asset")
        require("\n".join(names), "lib/arm64-v8a/libdexkit.so", "DexKit arm64 library")
        dex = zf.read("classes.dex")
        dex_text = dex.decode("latin-1", errors="ignore")
        if re.search(r"keyboardSearch|keyboard_search", dex_text, re.IGNORECASE):
            raise AssertionError("obsolete search-button marker reached classes.dex")
        for marker in ("accepted clip content", "clipboard-copy-normalizer", "quick-phrase-confirmation"):
            if marker not in dex_text:
                raise AssertionError(f"feature marker missing from classes.dex: {marker}")

    print("UPGRADE_STATIC_AUDIT=PASS")
    print(f"APK={APK}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, zipfile.BadZipFile) as exc:
        print(f"UPGRADE_STATIC_AUDIT=FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)

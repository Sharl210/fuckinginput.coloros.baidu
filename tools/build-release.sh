#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" ]]; then
    echo "ANDROID_SDK_ROOT或ANDROID_HOME未设置。" >&2
    exit 2
fi
export ANDROID_SDK_ROOT

GRADLE_ARGS=(
    clean
    :app:assembleRelease
    --no-daemon
    --console=plain
    -Dorg.gradle.jvmargs=-Xmx1536m\ -Xms256m
    -Dorg.gradle.workers.max=1
)

# AGP's bundled Linux aapt2 is x86_64. On ARM64, pass an API-37-capable
# native/emulated binary explicitly through AAPT2_OVERRIDE. A stale system
# aapt2 may parse older platforms but cannot link the current compileSdk.
AAPT2_OVERRIDE="${AAPT2_OVERRIDE:-${ANDROID_AAPT2_OVERRIDE:-}}"
if [[ -n "$AAPT2_OVERRIDE" ]]; then
    if [[ ! -x "$AAPT2_OVERRIDE" ]]; then
        echo "AAPT2_OVERRIDE不是可执行文件: $AAPT2_OVERRIDE" >&2
        exit 2
    fi
    GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE")
elif [[ "$(uname -m)" =~ ^(aarch64|arm64)$ ]] && [[ -x /usr/bin/aapt2 ]]; then
    echo "警告：使用系统aapt2；若API链接失败，请设置AAPT2_OVERRIDE为支持compileSdk 37的aapt2。" >&2
    GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2")
fi

# 部分容器会让Gradle客户端立即收到EOF并提前中断daemon。保持stdin打开，
# 最终只采用Gradle进程本身的退出码。
(timeout 1800 tail -f /dev/null) | ./gradlew "${GRADLE_ARGS[@]}"
status=${PIPESTATUS[1]}

if [[ $status -eq 0 ]]; then
    echo "APK: $ROOT/app/build/outputs/apk/release/app-release.apk"
fi
exit "$status"

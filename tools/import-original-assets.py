#!/usr/bin/env python3
"""从原APK复制无法从DEX语义还原的原始二进制资源。

用法：python3 tools/import-original-assets.py /path/to/original.apk
脚本只读取原APK，目标写入当前恢复工程。
"""

from __future__ import annotations

import argparse
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"

MAPPING = {
    "res/8E2.ogg": ("raw/oppo_key_sound_1.ogg", 472),
    "res/8E1.ogg": ("raw/oppo_key_sound_2.ogg", 1790),
    "res/8E.ogg": ("raw/oppo_key_sound_3.ogg", 1402),
    "res/2-.webp": ("mipmap-hdpi/ic_launcher.webp", 1404),
    "res/YB.webp": ("mipmap-mdpi/ic_launcher.webp", 982),
    "res/pY.webp": ("mipmap-xhdpi/ic_launcher.webp", 1900),
    "res/Vd.webp": ("mipmap-xxhdpi/ic_launcher.webp", 2884),
    "res/xC.webp": ("mipmap-xxxhdpi/ic_launcher.webp", 3844),
    "res/3c.webp": ("mipmap-hdpi/ic_launcher_round.webp", 2898),
    "res/co.webp": ("mipmap-mdpi/ic_launcher_round.webp", 1772),
    "res/iP.webp": ("mipmap-xhdpi/ic_launcher_round.webp", 3918),
    "res/7E.webp": ("mipmap-xxhdpi/ic_launcher_round.webp", 5914),
    "res/iN.webp": ("mipmap-xxxhdpi/ic_launcher_round.webp", 7778),
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error(f"APK不存在：{args.apk}")

    with zipfile.ZipFile(args.apk) as apk:
        names = set(apk.namelist())
        missing = sorted(set(MAPPING) - names)
        if missing:
            raise SystemExit("原APK缺少预期条目：" + ", ".join(missing))

        for source, (relative_target, expected_size) in MAPPING.items():
            data = apk.read(source)
            if len(data) != expected_size:
                raise SystemExit(
                    f"{source}大小异常：{len(data)}，预期{expected_size}"
                )
            target = RES / relative_target
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            print(f"{source} -> {target.relative_to(ROOT)} ({len(data)} bytes)")

    print("原始二进制资源导入完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

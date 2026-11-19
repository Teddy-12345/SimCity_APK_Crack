from pathlib import Path
import json
import lzma
import struct
import zipfile


ROOT = Path(__file__).resolve().parents[2]
BASE_APK = ROOT / "build" / "SimCity.apk"
INTERMEDIATE = ROOT / "build" / "ui-cleanup"
OUTPUT_APK = ROOT / "build" / "SimCity-ui-unsigned.apk"
LIBRARY = INTERMEDIATE / "libsimcityui.so"
DEX = INTERMEDIATE / "dex" / "classes.dex"
PATCH_MANIFEST = ROOT / "patches" / "ui-cleanup-native.json"

PATCHES = [
    (0x1968DFC, "4cf8ff54", "c2ffff17", "Keep offline city playable after the cached online timestamp expires"),
    (0x71D1C8, "758e4f94", "1f2003d5", "Suppress live city player ID text"),
    (0x71D22C, "5c8e4f94", "1f2003d5", "Suppress live city version text"),
    (0x3DF72C, "e003032a", "76000014", "Skip white EA logo rendering"),
    (0x3DFAB8, "39845c94", "1f2003d5", "Suppress loading player ID drawing"),
    (0x3DFB30, "1b845c94", "1f2003d5", "Suppress loading version drawing"),
    (0x1628D84, "865f1394", "1f2003d5", "Suppress city player ID drawing"),
    (0x1628E14, "625f1394", "1f2003d5", "Suppress city version drawing"),
]
FRAME_CAP_OFFSET = 0x11F2
FRAME_CAP_BEFORE = b"Limit = 30"
FRAME_CAP_AFTER = b"Limit = 0 "


def main():
    for required in (BASE_APK, LIBRARY, DEX):
        if not required.is_file():
            raise FileNotFoundError(f"Required build input missing: {required}")

    PATCH_MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_APK.parent.mkdir(parents=True, exist_ok=True)
    applied = []
    with zipfile.ZipFile(BASE_APK) as src, zipfile.ZipFile(OUTPUT_APK, "w") as dst:
        for entry in src.infolist():
            if entry.filename.startswith("META-INF/"):
                continue
            data = src.read(entry.filename)
            if entry.filename == "classes16.dex":
                data = DEX.read_bytes()
            elif entry.filename == "assets/SimCity_aarch64.s3e":
                raw = bytearray(lzma.decompress(data))
                for offset, before, after, reason in PATCHES:
                    file_offset = offset + 0xBBD4E
                    expected = bytes.fromhex(before)
                    actual = raw[file_offset:file_offset + len(expected)]
                    if actual != expected:
                        raise ValueError(
                            f"Unexpected ARM64 bytes at {offset:#x}: {actual.hex()}"
                        )
                    raw[file_offset:file_offset + len(expected)] = bytes.fromhex(after)
                    applied.append({
                        "offset": hex(offset),
                        "before": before,
                        "after": after,
                        "reason": reason,
                    })
                current_limit = raw[FRAME_CAP_OFFSET:FRAME_CAP_OFFSET + len(FRAME_CAP_BEFORE)]
                if current_limit != FRAME_CAP_BEFORE:
                    raise ValueError(
                        f"Unexpected default frame limit: {current_limit!r}"
                    )
                raw[FRAME_CAP_OFFSET:FRAME_CAP_OFFSET + len(FRAME_CAP_BEFORE)] = FRAME_CAP_AFTER
                applied.append({
                    "offset": hex(FRAME_CAP_OFFSET),
                    "before": FRAME_CAP_BEFORE.decode("ascii"),
                    "after": FRAME_CAP_AFTER.decode("ascii"),
                    "reason": "Remove the fixed 30 FPS cap; let Android display pacing set the effective rate",
                })
                packed = bytearray(lzma.compress(
                    bytes(raw),
                    format=lzma.FORMAT_ALONE,
                    filters=[{
                        "id": lzma.FILTER_LZMA1,
                        "dict_size": 65536,
                        "lc": 3,
                        "lp": 0,
                        "pb": 2,
                    }],
                ))
                packed[5:13] = struct.pack("<Q", len(raw))
                data = bytes(packed)
            dst.writestr(entry, data)

        dst.write(LIBRARY, "lib/arm64-v8a/libsimcityui.so", compress_type=zipfile.ZIP_STORED)

    PATCH_MANIFEST.write_text(json.dumps(applied, indent=2), encoding="utf-8")
    print(f"Built {OUTPUT_APK}")
    print(f"Applied {len(applied)} ARM64 patches")


if __name__ == "__main__":
    main()

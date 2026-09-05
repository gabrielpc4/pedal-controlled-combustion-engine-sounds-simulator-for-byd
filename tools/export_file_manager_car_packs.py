#!/usr/bin/env python3
"""Create a self-contained BYD delivery folder without car-pack installer APKs.

The dashboard APK is copied once for USB sideloading. Car-bank archives are split into small
file-manager batches, so the BYD only needs temporary space for one batch while the dashboard
validates and moves it into private app storage.
"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "fmod_bank_packs"
OUTPUT = ROOT / "manual_car_pack_bundles"
RELEASE_APK_DIRECTORY = ROOT / "mobile" / "build" / "outputs" / "apk" / "separate" / "release"
STANDALONE_RELEASE_DIRECTORIES = {
    "original": ROOT / "mobile" / "build" / "outputs" / "apk" / "original" / "release",
    "modded": ROOT / "mobile" / "build" / "outputs" / "apk" / "modded" / "release",
}
STANDALONE_APPLICATION_IDS = {
    "original": "com.gabrielpc.enginesoundsimulator.original",
    "modded": "com.gabrielpc.enginesoundsimulator.modded",
}
ORIGINAL_GROUP = "original_cars_pack"
MODDED_GROUP = "modded_car_packs"
ANDROID_IMPORT_PATH = (
    "Internal storage/Android/data/com.gabrielpc.enginesoundsimulator/files/fmod-bank-import/"
)


def selected_packs(index: dict[str, object], group: str) -> list[dict[str, object]]:
    packs = index.get("packs")
    if not isinstance(packs, list):
        raise RuntimeError("Bank-pack index has no packs list")
    selected = [
        pack
        for pack in packs
        if isinstance(pack, dict) and pack.get("group") == group and pack.get("active") is True
    ]
    if group == MODDED_GROUP:
        # Every modded car still uses the immutable shared original common banks.
        selected.extend(
            pack
            for pack in packs
            if isinstance(pack, dict) and pack.get("dependency") is True and pack.get("active") is True
        )
    # Shared dependencies must be imported first. The remaining cars stay in deterministic order.
    return sorted(
        selected,
        key=lambda pack: (
            not bool(pack.get("dependency")),
            str(pack["group"]),
            str(pack["id"]),
        ),
    )


def pack_source(pack: dict[str, object]) -> Path:
    asset = pack.get("asset")
    if not isinstance(asset, str):
        raise RuntimeError(f"Invalid pack index entry: {pack}")
    source = SOURCE / asset
    if not source.is_file():
        raise RuntimeError(f"Missing generated bank archive: {source}")
    return source


def split_batches(packs: list[dict[str, object]], maximum_bytes: int) -> list[list[dict[str, object]]]:
    batches: list[list[dict[str, object]]] = []
    current: list[dict[str, object]] = []
    current_bytes = 0
    for pack in packs:
        pack_bytes = pack_source(pack).stat().st_size
        if current and current_bytes + pack_bytes > maximum_bytes:
            batches.append(current)
            current = []
            current_bytes = 0
        current.append(pack)
        current_bytes += pack_bytes
    if current:
        batches.append(current)
    return batches


def write_bank_batch(
    destination: Path,
    *,
    title: str,
    group: str,
    batch_number: int,
    batch_count: int,
    packs: list[dict[str, object]],
) -> int:
    import_root = destination / "fmod-bank-import"
    total_bytes = 0
    for pack in packs:
        source = pack_source(pack)
        pack_group = pack.get("group")
        if not isinstance(pack_group, str):
            raise RuntimeError(f"Invalid pack index entry: {pack}")
        target = import_root / pack_group / source.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        total_bytes += source.stat().st_size
    shutil.copy2(SOURCE / "index.json", import_root / "index.json")
    (destination / "COPY_TO_BYD_INTERNAL_STORAGE.txt").write_text(
        "\n".join(
            [
                f"{title} FILE-MANAGER BANK BATCH {batch_number:02d} OF {batch_count:02d}",
                "",
                "1. Install and open Engine Sounds Simulator once, then close it.",
                "2. In the BYD file manager, open the internal-storage folder:",
                "   Internal storage/Android/data/com.gabrielpc.enginesoundsimulator/files/",
                "3. Copy this batch's entire fmod-bank-import folder into that parent folder.",
                "4. The final destination must be exactly:",
                f"   {ANDROID_IMPORT_PATH}",
                "5. Open Engine Sounds Simulator while parked. It validates and imports this batch.",
                "   Successful archives are removed automatically from fmod-bank-import to free space.",
                "6. Repeat with the next batch only after the import-complete message appears.",
                "",
                "Start with BATCH_01. It contains the shared banks that every selected car needs.",
                f"Package group: {group}",
                f"Archives in this batch: {len(packs)}",
                f"Copied bytes: {total_bytes}",
                "",
                "Do not copy these files into /data/user/0. That is private system storage and a",
                "normal file manager cannot use it. The Android/data path above is the supported",
                "staging path. Do not copy the dashboard APK to this path either.",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    return total_bytes


def copy_group_batches(
    index: dict[str, object],
    group: str,
    maximum_bytes: int,
) -> tuple[Path, int, int, int]:
    name = "ORIGINAL_CARS" if group == ORIGINAL_GROUP else "MODDED_CARS"
    destination = OUTPUT / "AUDIO_PACKS" / name
    if destination.exists():
        # This folder is generated and ignored by Git. Replacing it keeps the manifest and batch
        # boundaries coherent when car packs change, without touching generated source packs.
        shutil.rmtree(destination)
    packs = selected_packs(index, group)
    batches = split_batches(packs, maximum_bytes)
    total_bytes = 0
    for index_in_group, batch in enumerate(batches, start=1):
        batch_directory = destination / f"BATCH_{index_in_group:02d}"
        total_bytes += write_bank_batch(
            batch_directory,
            title=name.replace("_", " "),
            group=group,
            batch_number=index_in_group,
            batch_count=len(batches),
            packs=batch,
        )
    return destination, len(batches), len(packs), total_bytes


def resolve_dashboard_apk(configured_path: Path | None) -> Path:
    if configured_path is not None:
        apk = configured_path.resolve()
        if not apk.is_file():
            raise RuntimeError(f"Dashboard APK does not exist: {apk}")
        return apk
    candidates = sorted(RELEASE_APK_DIRECTORY.glob("*.apk"), key=lambda path: path.stat().st_mtime)
    if not candidates:
        raise RuntimeError(
            "No release APK found. Run ./gradlew :mobile:assembleSeparateRelease first, "
            "or pass --dashboard-apk PATH."
        )
    return candidates[-1]


def copy_dashboard_apk(apk: Path) -> Path:
    destination = OUTPUT / "DASHBOARD_APK"
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)
    copied_apk = destination / apk.name
    shutil.copy2(apk, copied_apk)
    (destination / "INSTALL_DASHBOARD_APK.txt").write_text(
        "\n".join(
            [
                "ENGINE SOUNDS SIMULATOR DASHBOARD APK",
                "",
                "This is the one APK to install on the BYD system. The separate car-pack installer",
                "APKs are no longer required.",
                "",
                "1. Copy this APK to a USB drive.",
                "2. Use the BYD third-party-app installation route enabled on your vehicle to install it.",
                "3. Do not copy this APK to Android/data; that path is only for the audio-pack batches.",
                "4. If Android reports that an update is incompatible because the installed app has a",
                "   different signing certificate, uninstall the older dashboard first, then install this one.",
                "",
                "After the dashboard is installed, use AUDIO_PACKS/*/BATCH_*/COPY_TO_BYD_INTERNAL_STORAGE.txt",
                "to add cars through the BYD file manager.",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    return copied_apk


def copy_standalone_apks() -> list[Path]:
    destination = OUTPUT / "STANDALONE_APKS"
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)
    copied: list[Path] = []
    for flavor, directory in STANDALONE_RELEASE_DIRECTORIES.items():
        candidates = sorted(directory.glob("*.apk"), key=lambda path: path.stat().st_mtime)
        if not candidates:
            raise RuntimeError(
                f"No {flavor} release APK found. Run "
                f"./gradlew :mobile:assemble{flavor.capitalize()}Release first."
            )
        apk = candidates[-1]
        copied_apk = destination / apk.name
        shutil.copy2(apk, copied_apk)
        copied.append(copied_apk)
    (destination / "INSTALL_STANDALONE_APKS.txt").write_text(
        "\n".join(
            [
                "ENGINE SOUNDS STANDALONE APKS",
                "",
                "Install only one catalog app, or install both if you want both catalogs.",
                "Each APK is self-contained and does not require file-manager bank imports.",
                "",
                f"Original cars: {STANDALONE_APPLICATION_IDS['original']}",
                f"Modded cars: {STANDALONE_APPLICATION_IDS['modded']}",
                "",
                "Use the BYD third-party-app installation route enabled on your vehicle.",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    return copied


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--groups",
        choices=("all", "original", "modded"),
        default="all",
        help="Which audio-pack bundles to create (default: all)",
    )
    parser.add_argument(
        "--batch-size-mib",
        type=int,
        default=512,
        help="Maximum staged audio size per BYD copy batch (default: 512 MiB)",
    )
    parser.add_argument(
        "--dashboard-apk",
        type=Path,
        help="Release APK to include; defaults to the newest mobile release APK",
    )
    parser.add_argument(
        "--include-standalone-apks",
        action="store_true",
        help="Also copy Original and Modded standalone release APKs into STANDALONE_APKS/",
    )
    arguments = parser.parse_args()
    if arguments.batch_size_mib <= 0:
        parser.error("--batch-size-mib must be positive")
    index_path = SOURCE / "index.json"
    if not index_path.is_file():
        raise RuntimeError("Generate fmod_bank_packs first with tools/build_fmod_bank_packs.py")
    index = json.loads(index_path.read_text(encoding="utf-8"))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for obsolete_directory in (OUTPUT / "ORIGINAL_CARS", OUTPUT / "MODDED_CARS"):
        # The pre-batch exporter used these two roots. They are generated, ignored artifacts, so
        # remove only that obsolete delivery shape before producing the bounded replacement.
        if obsolete_directory.exists():
            shutil.rmtree(obsolete_directory)
    dashboard_apk = copy_dashboard_apk(resolve_dashboard_apk(arguments.dashboard_apk))
    standalone_apks: list[Path] = []
    if arguments.include_standalone_apks:
        standalone_apks = copy_standalone_apks()
    groups = {
        "all": (ORIGINAL_GROUP, MODDED_GROUP),
        "original": (ORIGINAL_GROUP,),
        "modded": (MODDED_GROUP,),
    }[arguments.groups]
    maximum_bytes = arguments.batch_size_mib * 1024 * 1024
    results = [copy_group_batches(index, group, maximum_bytes) for group in groups]
    (OUTPUT / "README.txt").write_text(
        "This delivery folder has one signed DASHBOARD_APK for USB sideloading and no car-pack "
        "installer APKs. Use DASHBOARD_APK/INSTALL_DASHBOARD_APK.txt first. Then choose "
        "AUDIO_PACKS/ORIGINAL_CARS or AUDIO_PACKS/MODDED_CARS, begin at BATCH_01, and follow the "
        "COPY_TO_BYD_INTERNAL_STORAGE.txt in every batch. To install both catalogs, complete both "
        "sets of batches. Each batch is deliberately capped so it can be deleted after import "
        "instead of requiring the whole catalog twice on the BYD storage volume."
        + (
            " STANDALONE_APKS/ contains self-contained Original and Modded dashboards when "
            "--include-standalone-apks was used."
            if standalone_apks
            else ""
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Prepared dashboard APK: {dashboard_apk}")
    for copied_apk in standalone_apks:
        print(f"Prepared standalone APK: {copied_apk}")
    for destination, batch_count, pack_count, total_bytes in results:
        print(
            f"Prepared {destination}: {batch_count} batches, {pack_count} archives, "
            f"{total_bytes / (1024 * 1024):.1f} MiB"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

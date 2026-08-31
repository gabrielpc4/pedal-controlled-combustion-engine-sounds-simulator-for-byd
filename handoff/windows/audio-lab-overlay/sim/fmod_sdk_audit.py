"""Audit the shipped FMOD authoring project without copying its media."""

from __future__ import annotations

import hashlib
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any


class FmodSdkAuditError(ValueError):
    pass


def _properties(element: ET.Element) -> dict[str, list[str]]:
    return {
        item.attrib["name"]: [value.text or "" for value in item.findall("value")]
        for item in element.findall("property")
    }


def _relationships(element: ET.Element) -> dict[str, list[str]]:
    return {
        item.attrib["name"]: [value.text or "" for value in item.findall("destination")]
        for item in element.findall("relationship")
    }


def _one(properties: dict[str, list[str]], name: str, default: str | None = None) -> str | None:
    values = properties.get(name)
    return values[0] if values else default


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _point(objects: dict[str, ET.Element], identifier: str) -> dict[str, float]:
    properties = _properties(objects[identifier])
    result = {
        "x": float(_one(properties, "position", "0") or 0),
        "y": float(_one(properties, "value", "0") or 0),
    }
    shape = _one(properties, "curveShape")
    if shape is not None:
        result["curveShape"] = float(shape)
    return result


def _curve(objects: dict[str, ET.Element], identifier: str) -> list[dict[str, float]]:
    point_ids = _relationships(objects[identifier]).get("automationPoints", [])
    return sorted((_point(objects, point_id) for point_id in point_ids), key=lambda item: item["x"])


def _fade(objects: dict[str, ET.Element], identifier: str) -> list[dict[str, float]]:
    relationships = _relationships(objects[identifier])
    points = [
        _point(objects, point_id)
        for key in ("startPoint", "endPoint")
        for point_id in relationships.get(key, [])
    ]
    return sorted(points, key=lambda item: item["x"])


def _authored_role(group_name: str) -> str:
    tokens = re.findall(r"[a-z0-9]+", group_name.casefold())
    if "load" in tokens:
        return "EXCLUDED_LOAD"
    if "coast" in tokens and ("exh" in tokens or "exhaust" in tokens):
        return "EXHAUST"
    if "coast" in tokens:
        return "COAST"
    return "UNCLASSIFIED"


def _audio_files(project: Path) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for metadata in sorted((project / "Metadata" / "AudioFile").glob("*.xml")):
        root = ET.parse(metadata).getroot()
        for element in root.findall("object"):
            if element.attrib.get("class") != "AudioFile":
                continue
            properties = _properties(element)
            asset_path = _one(properties, "assetPath")
            if not asset_path:
                continue
            source = project / "Assets" / Path(*asset_path.split("/"))
            if not source.is_file():
                raise FmodSdkAuditError(f"missing SDK source asset: {source}")
            result[element.attrib["id"]] = {
                "path": asset_path,
                "sha256": _sha256_file(source),
                "frequencyKhz": float(_one(properties, "frequencyInKHz", "0") or 0),
                "channels": int(_one(properties, "channelCount", "0") or 0),
                "durationSeconds": float(_one(properties, "length", "0") or 0),
            }
    return result


def audit_shipped_fmod_authoring(assetto_root: Path) -> dict[str, Any]:
    """Return exact authoring metadata available in AC's public SDK project."""

    project = (
        assetto_root.resolve()
        / "sdk"
        / "audio"
        / "ac_fmod_sdk_1_9"
    )
    if not (project / "ac_fmod_sdk_1_9.fspro").is_file():
        raise FmodSdkAuditError(f"shipped FMOD SDK project is absent: {project}")
    audio_files = _audio_files(project)
    events: list[dict[str, Any]] = []
    for metadata in sorted((project / "Metadata" / "Event").glob("*.xml")):
        root = ET.parse(metadata).getroot()
        objects = {element.attrib["id"]: element for element in root.findall("object")}
        event = next(
            (element for element in objects.values() if element.attrib.get("class") == "Event"),
            None,
        )
        if event is None:
            continue
        event_name = _one(_properties(event), "name")
        if event_name not in {"engine_int", "engine_ext"}:
            continue
        event_relationships = _relationships(event)
        parameter_by_curve: dict[str, str] = {}
        for parameter_id in event_relationships.get("parameters", []):
            parameter = objects[parameter_id]
            parameter_name = _one(_properties(parameter), "name")
            if parameter_name is None:
                continue
            for curve_id in _relationships(parameter).get("automationCurves", []):
                parameter_by_curve[curve_id] = parameter_name
        groups: list[dict[str, Any]] = []
        for group_id in event_relationships.get("groupTracks", []):
            group_track = objects[group_id]
            group_relationships = _relationships(group_track)
            mixer_id = group_relationships.get("mixerGroup", [None])[0]
            if mixer_id is None:
                continue
            group_name = _one(_properties(objects[mixer_id]), "name", "") or ""
            automation: dict[str, list[dict[str, float]]] = {}
            for automation_id in group_relationships.get("automationTracks", []):
                for curve_id in _relationships(objects[automation_id]).get("automationCurves", []):
                    parameter = parameter_by_curve.get(curve_id)
                    if parameter is not None:
                        automation[parameter] = _curve(objects, curve_id)
            instruments: list[dict[str, Any]] = []
            for module_id in group_relationships.get("modules", []):
                module = objects[module_id]
                if module.attrib.get("class") != "SingleSound":
                    continue
                properties = _properties(module)
                relationships = _relationships(module)
                audio_id = relationships.get("audioFile", [None])[0]
                if audio_id not in audio_files:
                    raise FmodSdkAuditError(f"unresolved SDK audio relationship {audio_id}")
                root_rpm: float | None = None
                for modulator_id in relationships.get("modulators", []):
                    modulator = objects[modulator_id]
                    if modulator.attrib.get("class") == "AutopitchModulator":
                        root_value = _one(_properties(modulator), "root")
                        root_rpm = float(root_value) if root_value is not None else None
                fades: dict[str, list[dict[str, float]]] = {}
                for relationship_name, output_name in (
                    ("fadeInCurve", "fadeIn"),
                    ("fadeOutCurve", "fadeOut"),
                ):
                    identifiers = relationships.get(relationship_name, [])
                    if identifiers:
                        fades[output_name] = _fade(objects, identifiers[0])
                start = float(_one(properties, "start", "0") or 0)
                length = float(_one(properties, "length", "0") or 0)
                instruments.append(
                    {
                        "id": module_id,
                        "audio": audio_files[audio_id],
                        "looping": _one(properties, "looping", "false") == "true",
                        "rootRpm": root_rpm,
                        "regionStartRpm": start,
                        "regionEndRpm": start + length,
                        "baseGainDb": float(_one(properties, "volume", "0") or 0),
                        "rpmFades": fades,
                    }
                )
            groups.append(
                {
                    "name": group_name,
                    "manifestRole": _authored_role(group_name),
                    "gainAutomationDb": automation,
                    "instruments": instruments,
                }
            )
        events.append(
            {
                "name": event_name,
                "metadataSha256": _sha256_file(metadata),
                "groups": groups,
            }
        )
    excluded = sum(
        len(group["instruments"])
        for event in events
        for group in event["groups"]
        if group["manifestRole"] == "EXCLUDED_LOAD"
    )
    allowed = sum(
        len(group["instruments"])
        for event in events
        for group in event["groups"]
        if group["manifestRole"] in {"COAST", "EXHAUST"}
    )
    return {
        "schemaVersion": 1,
        "project": str(project),
        "authoringVersion": "Studio.01.08.00",
        "events": sorted(events, key=lambda item: item["name"]),
        "findings": {
            "engineEvents": len(events),
            "allowedSourceInstruments": allowed,
            "excludedLoadSourceInstruments": excluded,
            "eventLevelCaptureGuaranteesRoleExclusion": False,
            "curveShapeMetadataPresent": any(
                "curveShape" in point
                for event in events
                for group in event["groups"]
                for points in group["gainAutomationDb"].values()
                for point in points
            ),
            "scope": "Tatuus/Abarth SDK template only; other official banks do not ship authoring XML.",
        },
    }

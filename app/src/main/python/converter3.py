#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import base64
import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# NOTE: keep reserved prefixes for tolerant XML parsing
_RESERVED_PREFIXES = {"xml", "xmlns"}  # нельзя объявлять как xmlns:xml / xmlns:xmlns


def parse_xml_tolerant(path: Path) -> ET.ElementTree:
    data = path.read_text(encoding="utf-8", errors="replace")
    try:
        return ET.ElementTree(ET.fromstring(data))
    except ET.ParseError as e:
        if "unbound prefix" not in str(e):
            raise

        # Префиксы в тегах: <x:tag ...> </x:tag>
        tag_prefixes = set(re.findall(r"<\/?\s*([A-Za-z_][\w.-]*):", data))

        # Префиксы в атрибутах: xsi:schemaLocation="..." (но НЕ xmlns:ql="...")
        attr_prefixes = set(
            p
            for p in re.findall(r"\s([A-Za-z_][\w.-]*):[A-Za-z_][\w.-]*\s*=", data)
            if p not in _RESERVED_PREFIXES
        )

        prefixes = sorted((tag_prefixes | attr_prefixes) - _RESERVED_PREFIXES)

        # Уже объявленные xmlns:*
        declared = set(re.findall(r"\sxmlns:([A-Za-z_][\w.-]*)=", data))
        declared |= _RESERVED_PREFIXES

        missing = [p for p in prefixes if p not in declared]
        if not missing:
            raise

        def inject(m: re.Match) -> str:
            head = m.group(0)
            extra = "".join(f' xmlns:{p}="urn:autofix:{p}"' for p in missing)
            return head[:-1] + extra + ">"

        data2 = re.sub(r"<[^>]*>", inject, data, count=1)
        return ET.ElementTree(ET.fromstring(data2))


GPX_NS = "http://www.topografix.com/GPX/1/1"
ET.register_namespace("", GPX_NS)

CIRCLE_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8" viewBox="0 0 8 8"><circle cx="4" cy="4" r="3" fill="#888"/></svg>'
CIRCLE_SVG_B64 = base64.b64encode(CIRCLE_SVG.encode("utf-8")).decode("ascii")

_HEX_COLOR_RE = re.compile(r"^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")


def make_style(color: str = "#00FFFF") -> str:
    c = (color or "").strip() or "#00FFFF"
    # One MapCSS style per MS file (applies to all objects in the file).
    return f"""node {{
    text: eval(tag("name"));
    details-text: eval(tag("name"));
    details-description: eval(tag("name"));
    text-color: black;
    font-stroke-width: 5px;
    font-stroke-color: yellow;
    icon-image: eval(data("{CIRCLE_SVG_B64}"));
    icon-scale: 1;
    icon-tint: {c};
}}
line {{
    color: {c};
    width: 3px;
}}""".strip()


def style_from_arg(style: str | None) -> str | None:
    """Normalize style argument.

    Accepted inputs:
      - None => None
      - ""   => "" (special meaning: keep existing style on append)
      - "#RRGGBB" or "#AARRGGBB" => expanded to full MapCSS via make_style()
      - otherwise => returned as-is (assumed to already be MapCSS)
    """
    if style is None:
        return None
    s = str(style).strip()
    if s == "":
        return ""
    if _HEX_COLOR_RE.fullmatch(s):
        return make_style(s)
    return s


DEFAULT_STYLE = make_style("#00FFFF")

# Grid-ish names: A1, a-01, AA 12, ah_23, etc.
GRID_RE = re.compile(r"^\s*([A-Za-z]+)\s*[-_ ]*\s*(\d+)\s*$")


@dataclass
class NamedPoint:
    name: str
    lat: float
    lon: float
    ele: float = 0.0
    time_iso: Optional[str] = None
    desc: Optional[str] = None
    sym: Optional[str] = None


@dataclass
class Track:
    name: str
    # list of segments; each segment is list of points
    segments: List[List[NamedPoint]]


# ----------------- small helpers -----------------
def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _text(el: Optional[ET.Element]) -> Optional[str]:
    if el is None or el.text is None:
        return None
    t = el.text.strip()
    return t if t else None


def detect_format(path: Path) -> str:
    low = path.name.lower()
    if low.endswith(".gpx"):
        return "gpx"
    if low.endswith(".ms"):
        return "ms"

    # fallback: sniff
    head = path.read_text(encoding="utf-8", errors="ignore")[:2000].lower()
    if "<gpx" in head:
        return "gpx"
    if "<custommapsource" in head or "<geojson" in head:
        return "ms"
    raise ValueError(f"Unknown format: {path}")


def _find_text(parent: ET.Element, local_name: str) -> Optional[str]:
    for child in parent:
        tag = child.tag
        if "}" in tag:
            tag = tag.split("}", 1)[1]
        if tag == local_name:
            return _text(child)
    return None


def _parse_point_element(el: ET.Element, is_wpt: bool) -> NamedPoint:
    lat = float(el.attrib["lat"])
    lon = float(el.attrib["lon"])

    ele = 0.0
    ele_text = _find_text(el, "ele")
    if ele_text:
        try:
            ele = float(ele_text)
        except ValueError:
            ele = 0.0

    t = _find_text(el, "time")
    name = _find_text(el, "name") or ("" if not is_wpt else "wpt")
    desc = _find_text(el, "desc")
    sym = _find_text(el, "sym")
    return NamedPoint(name=name, lat=lat, lon=lon, ele=ele, time_iso=t, desc=desc, sym=sym)


def parse_gpx_full(path: Path) -> Tuple[str, List[NamedPoint], List[Track]]:
    tree = parse_xml_tolerant(path)
    root = tree.getroot()

    # name/title
    nm = _text(root.find(f"{{{GPX_NS}}}metadata/{{{GPX_NS}}}name")) or path.stem
    if not nm:
        nm = path.stem

    wpts: List[NamedPoint] = []
    tracks: List[Track] = []

    for w in root.findall(f"{{{GPX_NS}}}wpt"):
        lat = float(w.attrib.get("lat") or 0.0)
        lon = float(w.attrib.get("lon") or 0.0)
        name = _text(w.find(f"{{{GPX_NS}}}name")) or "wpt"
        ele = float(_text(w.find(f"{{{GPX_NS}}}ele")) or 0.0)
        t = _text(w.find(f"{{{GPX_NS}}}time"))
        desc = _text(w.find(f"{{{GPX_NS}}}desc")) or _text(w.find(f"{{{GPX_NS}}}cmt"))
        sym = _text(w.find(f"{{{GPX_NS}}}sym"))
        wpts.append(NamedPoint(name=name, lat=lat, lon=lon, ele=ele, time_iso=t, desc=desc, sym=sym))

    for trk in root.findall(f"{{{GPX_NS}}}trk"):
        tname = _text(trk.find(f"{{{GPX_NS}}}name")) or "track"
        segments: List[List[NamedPoint]] = []
        for seg in trk.findall(f"{{{GPX_NS}}}trkseg"):
            pts: List[NamedPoint] = []
            for p in seg.findall(f"{{{GPX_NS}}}trkpt"):
                lat = float(p.attrib.get("lat") or 0.0)
                lon = float(p.attrib.get("lon") or 0.0)
                ele = float(_text(p.find(f"{{{GPX_NS}}}ele")) or 0.0)
                t = _text(p.find(f"{{{GPX_NS}}}time"))
                pts.append(NamedPoint(name="", lat=lat, lon=lon, ele=ele, time_iso=t))
            if pts:
                segments.append(pts)
        if segments:
            tracks.append(Track(name=tname, segments=segments))

    return nm, wpts, tracks


def build_gpx_full(title: str, wpts: List[NamedPoint], tracks: List[Track]) -> ET.ElementTree:
    root = ET.Element(f"{{{GPX_NS}}}gpx", {"version": "1.1", "creator": "GPX2MS"})
    meta = ET.SubElement(root, f"{{{GPX_NS}}}metadata")
    ET.SubElement(meta, f"{{{GPX_NS}}}name").text = title

    for p in wpts:
        w = ET.SubElement(root, f"{{{GPX_NS}}}wpt", {"lat": str(p.lat), "lon": str(p.lon)})
        ET.SubElement(w, f"{{{GPX_NS}}}name").text = p.name
        ET.SubElement(w, f"{{{GPX_NS}}}ele").text = str(p.ele)
        if p.time_iso:
            ET.SubElement(w, f"{{{GPX_NS}}}time").text = p.time_iso
        if p.desc:
            ET.SubElement(w, f"{{{GPX_NS}}}desc").text = p.desc
        if p.sym:
            ET.SubElement(w, f"{{{GPX_NS}}}sym").text = p.sym

    for t in tracks:
        trk = ET.SubElement(root, f"{{{GPX_NS}}}trk")
        ET.SubElement(trk, f"{{{GPX_NS}}}name").text = t.name
        for seg in t.segments:
            seg_el = ET.SubElement(trk, f"{{{GPX_NS}}}trkseg")
            for p in seg:
                pt = ET.SubElement(seg_el, f"{{{GPX_NS}}}trkpt", {"lat": str(p.lat), "lon": str(p.lon)})
                ET.SubElement(pt, f"{{{GPX_NS}}}ele").text = str(p.ele)
                if p.time_iso:
                    ET.SubElement(pt, f"{{{GPX_NS}}}time").text = p.time_iso

    return ET.ElementTree(root)


# ----------------- MS parsing/building -----------------
def parse_ms_full(path: Path) -> Tuple[str, List[NamedPoint], List[Track], Optional[str]]:
    tree = ET.parse(path)
    root = tree.getroot()

    title_el = root.find("name")
    title = title_el.text.strip() if (title_el is not None and title_el.text) else path.stem

    style_el = root.find("style")
    style_text = style_el.text if style_el is not None else None

    geo_el = root.find("geojson")
    if geo_el is None or geo_el.text is None:
        raise ValueError("No <geojson> found")

    gj = json.loads(geo_el.text.strip())
    features = gj.get("features", [])

    wpts: List[NamedPoint] = []
    tracks: List[Track] = []

    for feat in features:
        if not isinstance(feat, dict):
            continue
        geom = feat.get("geometry") or {}
        props = feat.get("properties") or {}
        gtype = geom.get("type")

        if gtype == "Point":
            coords = geom.get("coordinates") or []
            if len(coords) < 2:
                continue
            lon, lat = float(coords[0]), float(coords[1])
            ele = float(coords[2]) if len(coords) >= 3 else 0.0
            name = str(props.get("name") or "wpt")
            t = props.get("time")
            desc = props.get("desc")
            sym = props.get("sym")
            wpts.append(NamedPoint(name=name, lat=lat, lon=lon, ele=ele, time_iso=t, desc=desc, sym=sym))

        elif gtype == "MultiLineString":
            coords = geom.get("coordinates") or []
            times = props.get("times") or None
            name = str(props.get("name") or "track")
            segments: List[List[NamedPoint]] = []
            for si, seg in enumerate(coords):
                pts: List[NamedPoint] = []
                seg_times = None
                if times and isinstance(times, list) and si < len(times):
                    seg_times = times[si]
                for pi, c in enumerate(seg or []):
                    if not c or len(c) < 2:
                        continue
                    lon, lat = float(c[0]), float(c[1])
                    ele = float(c[2]) if len(c) >= 3 else 0.0
                    t_iso = None
                    if seg_times and isinstance(seg_times, list) and pi < len(seg_times):
                        t_iso = seg_times[pi]
                    pts.append(NamedPoint(name="", lat=lat, lon=lon, ele=ele, time_iso=t_iso))
                if pts:
                    segments.append(pts)
            if segments:
                tracks.append(Track(name=name, segments=segments))

    return title, wpts, tracks, style_text


def _make_lines_orth(wpts: List[NamedPoint]) -> List[List[List[float]]]:
    # stub: preserve existing behavior
    return []


def _make_lines_snake(wpts: List[NamedPoint]) -> List[List[List[float]]]:
    # stub: preserve existing behavior
    return []


def build_ms_full(
        title: str,
        wpts: List[NamedPoint],
        tracks: List[Track],
        line_mode: str = "none",  # grid helper for WAYPOINTS only: none|orth|snake|both
        style_text: str = DEFAULT_STYLE,
) -> ET.ElementTree:
    root = ET.Element("customMapSource", {"overlay": "true"})
    ET.SubElement(root, "name").text = title

    features: List[Dict[str, Any]] = []

    # Optional grid lines based on waypoint names
    lm = (line_mode or "none").lower()
    if lm in ("orth", "snake", "both"):
        lines: List[List[List[float]]] = []
        if lm in ("orth", "both"):
            lines.extend(_make_lines_orth(wpts))
        if lm in ("snake", "both"):
            lines.extend(_make_lines_snake(wpts))
        if lines:
            features.append(
                {
                    "type": "Feature",
                    "properties": {"name": "grid"},
                    "geometry": {"type": "MultiLineString", "coordinates": lines},
                }
            )

    # Tracks -> MultiLineString, with times if present
    for tr in tracks:
        coords: List[List[List[float]]] = []
        times_out: List[List[Optional[str]]] = []
        any_time = False
        for seg in tr.segments:
            seg_coords: List[List[float]] = []
            seg_times: List[Optional[str]] = []
            for p in seg:
                seg_coords.append([p.lon, p.lat, p.ele])
                seg_times.append(p.time_iso)
                if p.time_iso:
                    any_time = True
            if seg_coords:
                coords.append(seg_coords)
                times_out.append(seg_times)

        props: Dict[str, Any] = {"name": tr.name}
        if any_time:
            props["times"] = times_out

        features.append(
            {"type": "Feature", "properties": props, "geometry": {"type": "MultiLineString", "coordinates": coords}}
        )

    # Waypoints -> Points
    for p in wpts:
        props: Dict[str, Any] = {"name": p.name}
        if p.time_iso:
            props["time"] = p.time_iso
        if p.desc:
            props["desc"] = p.desc
        if p.sym:
            props["sym"] = p.sym
        features.append(
            {"type": "Feature", "properties": props, "geometry": {"type": "Point", "coordinates": [p.lon, p.lat, p.ele]}}
        )

    gj = {"type": "FeatureCollection", "features": features}
    geo_el = ET.SubElement(root, "geojson")
    geo_el.text = json.dumps(gj, ensure_ascii=False)

    if style_text is not None and style_text != "":
        st = ET.SubElement(root, "style")
        st.text = style_text

    return ET.ElementTree(root)


# ----------------- Public API (import-friendly) -----------------
@dataclass
class ConvertResult:
    message: str
    added_count: int = 0
    skipped_duplicates: int = 0


def _norm_f(x: float, nd: int) -> float:
    try:
        return round(float(x), nd)
    except Exception:
        return 0.0


def _wpt_key(p: NamedPoint) -> tuple:
    return (
        str(p.name or ""),
        _norm_f(p.lat, 7),
        _norm_f(p.lon, 7),
        _norm_f(p.ele, 2),
        str(p.time_iso or ""),
        str(p.desc or ""),
        str(p.sym or ""),
    )


def _track_key(t: Track) -> tuple:
    segs = []
    for seg in t.segments or []:
        segs.append(tuple(_wpt_key(p) for p in (seg or [])))
    return (str(t.name or ""), tuple(segs))


def _dedup_merge(
        existing_wpts: List[NamedPoint],
        existing_tracks: List[Track],
        new_wpts: List[NamedPoint],
        new_tracks: List[Track],
) -> tuple[list[NamedPoint], list[Track], int, int]:
    wpt_seen = set(_wpt_key(p) for p in existing_wpts)
    trk_seen = set(_track_key(t) for t in existing_tracks)

    merged_wpts = list(existing_wpts)
    merged_tracks = list(existing_tracks)

    added = 0
    skipped = 0

    for p in new_wpts:
        k = _wpt_key(p)
        if k in wpt_seen:
            skipped += 1
            continue
        wpt_seen.add(k)
        merged_wpts.append(p)
        added += 1

    for t in new_tracks:
        k = _track_key(t)
        if k in trk_seen:
            skipped += 1
            continue
        trk_seen.add(k)
        merged_tracks.append(t)
        added += 1

    return merged_wpts, merged_tracks, added, skipped


def convert_file(
        input_path: str | Path,
        output_path: str | Path,
        *,
        to: Optional[str] = None,
        line_mode: str = "none",
        style: str = DEFAULT_STYLE,
        append: bool = False,
) -> ConvertResult:
    """Import-friendly conversion entry point.

    Keeps CLI behavior intact, but can be used from Android/GUI without spawning a subprocess.
    - input_path/output_path: filesystem paths
    - to: 'gpx' or 'ms' (None = opposite of input)
    - append:
        * Only supported for GPX -> MS.
        * If True and output exists and is MS, merges (dedup) existing objects with new objects.

    Returns ConvertResult(message, added_count, skipped_duplicates).
    """
    inp = Path(input_path)
    out = Path(output_path)

    src = detect_format(inp)
    dst = to if to else ("ms" if src == "gpx" else "gpx")

    style_norm = style_from_arg(style)

    if src == "gpx" and dst == "ms":
        title_new, wpts_new, tracks_new = parse_gpx_full(inp)

        if append:
            # Append into existing MS if present, else behave like convert
            if out.exists():
                try:
                    title_old, wpts_old, tracks_old, style_old = parse_ms_full(out)
                except Exception:
                    title_old, wpts_old, tracks_old, style_old = out.stem, [], [], None
            else:
                title_old, wpts_old, tracks_old, style_old = out.stem, [], [], None

            # Dedup merge
            merged_wpts, merged_tracks, added, skipped = _dedup_merge(wpts_old, tracks_old, wpts_new, tracks_new)

            # Title handling: keep existing title if present
            title_final = title_old if title_old else title_new

            # Style handling:
            # - style_norm None or "" => keep existing style (style_old)
            # - otherwise => override with style_norm (already normalized/expanded)
            chosen = style_old if (style_norm is None or style_norm == "") else style_norm
            style_final = chosen if (chosen not in (None, "")) else DEFAULT_STYLE

            tree = build_ms_full(title_final, merged_wpts, merged_tracks, line_mode=line_mode, style_text=style_final)
            tree.write(out, encoding="utf-8", xml_declaration=True)

            msg = f"Append: +{added}, duplicates skipped: {skipped}"
            return ConvertResult(message=msg, added_count=added, skipped_duplicates=skipped)

        # Normal convert (overwrite)
        style_final = (style_norm if (style_norm is not None and style_norm != "") else DEFAULT_STYLE)
        tree = build_ms_full(title_new, wpts_new, tracks_new, line_mode=line_mode, style_text=style_final)
        tree.write(out, encoding="utf-8", xml_declaration=True)
        return ConvertResult(message="Done.", added_count=(len(wpts_new) + len(tracks_new)), skipped_duplicates=0)

    if src == "ms" and dst == "gpx":
        title, wpts, tracks, _style = parse_ms_full(inp)
        tree = build_gpx_full(title, wpts, tracks)
        tree.write(out, encoding="utf-8", xml_declaration=True)
        return ConvertResult(message="Done.", added_count=(len(wpts) + len(tracks)), skipped_duplicates=0)

    raise ValueError(f"Unsupported conversion: {src} -> {dst}")


# ----------------- CLI -----------------
def main():
    ap = argparse.ArgumentParser(description="GPX <-> MS converter")
    ap.add_argument("input", help="Input file (.gpx or .ms)")
    ap.add_argument("output", help="Output file (.ms or .gpx)")
    ap.add_argument("--to", choices=["gpx", "ms"], default=None, help="Force output format")
    ap.add_argument("--append", action="store_true", help="Append GPX into existing MS (dedup enabled)")
    ap.add_argument("--line-mode", default="none", help="Grid helper for waypoints: none|orth|snake|both")
    ap.add_argument("--style", default=DEFAULT_STYLE, help="MapCSS style OR hex color (#RRGGBB/#AARRGGBB)")
    args = ap.parse_args()

    res = convert_file(
        args.input,
        args.output,
        to=args.to,
        line_mode=args.line_mode,
        style=args.style,
        append=args.append,
    )

    # CLI output (keep simple)
    print(res.message)


def append_gpx_into_ms(
        existing_ms_path,
        gpx_path,
        out_ms_path=None,
        line_mode: str = "none",
        style_text=None,
        keep_existing_title: bool = True,
):
    """
    Backward-compatible function used by Android bridge.py (Chaquopy).

    Returns: (added_waypoints_count, added_tracks_count)
    """
    out_path = Path(out_ms_path) if out_ms_path is not None else Path(existing_ms_path)
    gpx_path = Path(gpx_path)

    # Count BEFORE
    before_wpts = before_tracks = 0
    if out_path.exists():
        try:
            _t, w_old, tr_old, _st = parse_ms_full(out_path)
            before_wpts = len(w_old)
            before_tracks = len(tr_old)
        except Exception:
            # broken MS -> treat as empty
            before_wpts = 0
            before_tracks = 0

    # style_text semantics from bridge.py:
    # - None => keep existing style if present
    # - ""   => keep existing style
    # - "#RRGGBB"/"#AARRGGBB" => apply that color (expanded in converter)
    # - full MapCSS string => override style
    style_arg = "" if style_text is None else style_text

    # Do append via the new API
    convert_file(
        gpx_path,
        out_path,
        to="ms",
        line_mode=line_mode,
        style=style_arg,
        append=True,
    )

    # Count AFTER
    _t2, w_new, tr_new, _st2 = parse_ms_full(out_path)
    added_wpts = max(0, len(w_new) - before_wpts)
    added_tracks = max(0, len(tr_new) - before_tracks)
    return added_wpts, added_tracks


if __name__ == "__main__":
    main()
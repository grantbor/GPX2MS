#!/usr/bin/env python3
# -*- coding: utf-8 -*-

from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any
import re
from pathlib import Path
import xml.etree.ElementTree as ET
import base64

_RESERVED_PREFIXES = {"xml", "xmlns"}  # нельзя объявлять как xmlns:xml / xmlns:xmlns

def parse_xml_tolerant(path: Path) -> ET.ElementTree:
    data = path.read_text(encoding="utf-8", errors="replace")

    try:
        return ET.ElementTree(ET.fromstring(data))
    except ET.ParseError as e:
        if "unbound prefix" not in str(e):
            raise

    # Префиксы в тегах: <ql:key>, </gpxx:TrackExtension> ...
    tag_prefixes = set(re.findall(r"<\/?\s*([A-Za-z_][\w.-]*):", data))

    # Префиксы в атрибутах: xsi:schemaLocation="..." (но НЕ xmlns:ql="...")
    # Для этого берём только такие атрибуты, где префикс != xmlns
    attr_prefixes = set(
        p for p in re.findall(r"\s([A-Za-z_][\w.-]*):[A-Za-z_][\w.-]*\s*=", data)
        if p not in _RESERVED_PREFIXES
    )

    prefixes = sorted((tag_prefixes | attr_prefixes) - _RESERVED_PREFIXES)

    # Уже объявленные xmlns:*
    declared = set(re.findall(r"\sxmlns:([A-Za-z_][\w.-]*)=", data))
    declared |= _RESERVED_PREFIXES  # на всякий случай

    missing = [p for p in prefixes if p not in declared]
    if not missing:
        raise  # странно, но пусть падает как раньше

    # Вставляем недостающие xmlns:* в корневой <gpx ...>
    def inject(m: re.Match) -> str:
        head = m.group(0)
        extra = "".join(f' xmlns:{p}="urn:autofix:{p}"' for p in missing)
        return head[:-1] + extra + ">"

    data2 = re.sub(r"<gpx\b[^>]*>", inject, data, count=1)
    return ET.ElementTree(ET.fromstring(data2))



GPX_NS = "http://www.topografix.com/GPX/1/1"
ET.register_namespace("", GPX_NS)

CIRCLE_SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8" viewBox="0 0 8 8"><circle cx="4" cy="4" r="3" fill="#888"/></svg>'
CIRCLE_SVG_B64 = base64.b64encode(CIRCLE_SVG.encode("utf-8")).decode("ascii")

DEFAULT_STYLE = (
    'node { text: eval(tag("name")); details-text: eval(tag("name")); '
    'details-description: eval(tag("name")); text-color:black; font-stroke-width:5px; '
    'font-stroke-color:yellow; '
    f'icon-image: eval(data("{CIRCLE_SVG_B64}")); '
    'icon-scale:1; icon-tint:#00FFFF;} '
    'line { color:#00FFFF; width:3px; }'
)

# Grid-ish names: A1, a-01, AA 12, ah_23, etc.
NAME_RE = re.compile(r"^\s*([A-Za-z]+)\s*[-_ ]*\s*(\d+)\s*$")


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


def split_grid_name(name: str) -> Optional[Tuple[str, int]]:
    m = NAME_RE.match(name)
    if not m:
        return None
    return m.group(1).upper(), int(m.group(2))


def col_to_index(col: str) -> int:
    n = 0
    for ch in col.upper():
        if not ("A" <= ch <= "Z"):
            return 10**9
        n = n * 26 + (ord(ch) - ord("A") + 1)
    return n


def sort_key_name(name: str):
    sp = split_grid_name(name)
    if not sp:
        return (10**9, 10**9, name)
    col, row = sp
    return (row, col_to_index(col), name)


def detect_format(path: Path) -> str:
    txt = path.read_text(encoding="utf-8", errors="ignore")
    if "<gpx" in txt:
        return "gpx"
    if "<customMapSource" in txt and "<geojson" in txt:
        return "ms"
    raise ValueError("Unknown format (no <gpx> or <customMapSource><geojson>).")


# ----------------- GPX parsing -----------------

def _find_text(parent: ET.Element, tag_local: str) -> Optional[str]:
    el = parent.find("{%s}%s" % (GPX_NS, tag_local))
    if el is not None and el.text and el.text.strip():
        return el.text.strip()
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

    # title from metadata/name if exists
    title = path.stem
    meta = root.find("{%s}metadata" % GPX_NS)
    if meta is not None:
        mname = _find_text(meta, "name")
        if mname:
            title = mname

    # waypoints
    wpts: List[NamedPoint] = []
    for wpt in root.findall("{%s}wpt" % GPX_NS):
        p = _parse_point_element(wpt, is_wpt=True)
        if not p.name:
            p.name = "wpt"
        wpts.append(p)

    # tracks
    tracks: List[Track] = []
    for trk in root.findall("{%s}trk" % GPX_NS):
        trk_name = _find_text(trk, "name") or "track"
        segments: List[List[NamedPoint]] = []
        for seg in trk.findall("{%s}trkseg" % GPX_NS):
            seg_pts: List[NamedPoint] = []
            for trkpt in seg.findall("{%s}trkpt" % GPX_NS):
                p = _parse_point_element(trkpt, is_wpt=False)
                # trkpt обычно без name; сохраняем пустым
                seg_pts.append(p)
            if seg_pts:
                segments.append(seg_pts)
        if segments:
            tracks.append(Track(name=trk_name, segments=segments))

    return title, wpts, tracks


# ----------------- GPX building -----------------

def build_gpx_full(title: str, wpts: List[NamedPoint], tracks: List[Track]) -> ET.ElementTree:
    root = ET.Element("{%s}gpx" % GPX_NS, {"version": "1.1", "creator": "format_converter"})
    meta = ET.SubElement(root, "{%s}metadata" % GPX_NS)
    ET.SubElement(meta, "{%s}name" % GPX_NS).text = title
    ET.SubElement(meta, "{%s}time" % GPX_NS).text = now_iso()

    # wpt
    for p in sorted(wpts, key=lambda x: sort_key_name(x.name)):
        wpt = ET.SubElement(root, "{%s}wpt" % GPX_NS, {"lat": str(p.lat), "lon": str(p.lon)})
        if p.ele != 0.0:
            ET.SubElement(wpt, "{%s}ele" % GPX_NS).text = str(p.ele)
        ET.SubElement(wpt, "{%s}time" % GPX_NS).text = p.time_iso or now_iso()
        ET.SubElement(wpt, "{%s}name" % GPX_NS).text = p.name
        if p.desc:
            ET.SubElement(wpt, "{%s}desc" % GPX_NS).text = p.desc
        if p.sym:
            ET.SubElement(wpt, "{%s}sym" % GPX_NS).text = p.sym

    # trk
    for tr in tracks:
        trk = ET.SubElement(root, "{%s}trk" % GPX_NS)
        ET.SubElement(trk, "{%s}name" % GPX_NS).text = tr.name
        for seg_pts in tr.segments:
            seg = ET.SubElement(trk, "{%s}trkseg" % GPX_NS)
            for p in seg_pts:
                trkpt = ET.SubElement(seg, "{%s}trkpt" % GPX_NS, {"lat": str(p.lat), "lon": str(p.lon)})
                if p.ele != 0.0:
                    ET.SubElement(trkpt, "{%s}ele" % GPX_NS).text = str(p.ele)
                if p.time_iso:
                    ET.SubElement(trkpt, "{%s}time" % GPX_NS).text = p.time_iso

    return ET.ElementTree(root)


# ----------------- Grid line generation (optional) -----------------

def _group_grid_points(points: List[NamedPoint]):
    rows: Dict[int, List[NamedPoint]] = {}
    cols: Dict[str, List[NamedPoint]] = {}
    for p in points:
        sp = split_grid_name(p.name)
        if not sp:
            continue
        col, row = sp
        rows.setdefault(row, []).append(p)
        cols.setdefault(col, []).append(p)

    for r in list(rows.keys()):
        rows[r] = sorted(rows[r], key=lambda x: col_to_index(split_grid_name(x.name)[0]))  # type: ignore[index]
    for c in list(cols.keys()):
        cols[c] = sorted(cols[c], key=lambda x: split_grid_name(x.name)[1])  # type: ignore[index]
    return rows, cols


def _make_lines_orth(points: List[NamedPoint]) -> List[List[List[float]]]:
    rows, cols = _group_grid_points(points)
    lines: List[List[List[float]]] = []
    for row in sorted(rows.keys()):
        pts = rows[row]
        if len(pts) >= 2:
            lines.append([[pt.lon, pt.lat, 0.0] for pt in pts])
    for col in sorted(cols.keys(), key=col_to_index):
        pts = cols[col]
        if len(pts) >= 2:
            lines.append([[pt.lon, pt.lat, 0.0] for pt in pts])
    return lines


def _make_lines_snake(points: List[NamedPoint]) -> List[List[List[float]]]:
    rows, _ = _group_grid_points(points)
    if not rows:
        return []
    min_row = min(rows.keys())
    max_row = max(rows.keys())

    snake: List[List[float]] = []
    ltr = True
    for row in range(min_row, max_row + 1):
        row_pts = rows.get(row, [])
        if not row_pts:
            continue
        pts = row_pts if ltr else list(reversed(row_pts))
        for pt in pts:
            snake.append([pt.lon, pt.lat, 0.0])
        ltr = not ltr

    if len(snake) < 2:
        return []
    return [snake]


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
            t = props.get("time")  # optional single time
            desc = props.get("desc")
            sym = props.get("sym")
            wpts.append(NamedPoint(name=name, lat=lat, lon=lon, ele=ele, time_iso=t, desc=desc, sym=sym))

        elif gtype in ("LineString", "MultiLineString"):
            trk_name = str(props.get("name") or props.get("track_name") or "track")
            segs: List[List[NamedPoint]] = []

            times = props.get("times")  # optional list (for LineString) or list-of-lists (for MultiLineString)

            if gtype == "LineString":
                coords = geom.get("coordinates") or []
                seg_pts: List[NamedPoint] = []
                for i, c in enumerate(coords):
                    if not (isinstance(c, (list, tuple)) and len(c) >= 2):
                        continue
                    lon, lat = float(c[0]), float(c[1])
                    ele = float(c[2]) if len(c) >= 3 else 0.0
                    t = None
                    if isinstance(times, list) and i < len(times):
                        t = times[i]
                    seg_pts.append(NamedPoint(name="", lat=lat, lon=lon, ele=ele, time_iso=t))
                if seg_pts:
                    segs.append(seg_pts)

            else:  # MultiLineString
                mcoords = geom.get("coordinates") or []
                for si, coords in enumerate(mcoords):
                    seg_pts: List[NamedPoint] = []
                    seg_times = None
                    if isinstance(times, list) and si < len(times) and isinstance(times[si], list):
                        seg_times = times[si]
                    for i, c in enumerate(coords or []):
                        if not (isinstance(c, (list, tuple)) and len(c) >= 2):
                            continue
                        lon, lat = float(c[0]), float(c[1])
                        ele = float(c[2]) if len(c) >= 3 else 0.0
                        t = None
                        if isinstance(seg_times, list) and i < len(seg_times):
                            t = seg_times[i]
                        seg_pts.append(NamedPoint(name="", lat=lat, lon=lon, ele=ele, time_iso=t))
                    if seg_pts:
                        segs.append(seg_pts)

            if segs:
                tracks.append(Track(name=trk_name, segments=segs))

    return title, wpts, tracks, style_text


def build_ms_full(
        title: str,
        wpts: List[NamedPoint],
        tracks: List[Track],
        line_mode: str = "none",   # grid helper for WAYPOINTS only: none|orth|snake|both
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
            features.append({
                "type": "Feature",
                "properties": {"name": "grid"},
                "geometry": {"type": "MultiLineString", "coordinates": lines}
            })

    # Tracks -> MultiLineString, with times if present
    for tr in tracks:
        coords: List[List[List[float]]] = []
        times_out: List[List[Optional[str]]] = []
        any_time = False

        for seg in tr.segments:
            seg_coords: List[List[float]] = []
            seg_times: List[Optional[str]] = []
            for p in seg:
                seg_coords.append([p.lon, p.lat, float(p.ele or 0.0)])
                seg_times.append(p.time_iso)
                if p.time_iso:
                    any_time = True
            if seg_coords:
                coords.append(seg_coords)
                times_out.append(seg_times)

        props: Dict[str, Any] = {"name": tr.name, "kind": "track"}
        if any_time:
            props["times"] = times_out  # list-of-lists aligned to coordinates

        if coords:
            features.append({
                "type": "Feature",
                "properties": props,
                "geometry": {"type": "MultiLineString", "coordinates": coords}
            })

    # Waypoints -> Points
    for p in sorted(wpts, key=lambda x: sort_key_name(x.name)):
        props: Dict[str, Any] = {"name": p.name, "kind": "wpt"}
        if p.time_iso:
            props["time"] = p.time_iso
        if p.desc:
            props["desc"] = p.desc
        if p.sym:
            props["sym"] = p.sym

        features.append({
            "type": "Feature",
            "properties": props,
            "geometry": {"type": "Point", "coordinates": [p.lon, p.lat, float(p.ele or 0.0)]}
        })

    geo = {"type": "FeatureCollection", "generator": "format_converter", "features": features}

    geo_el = ET.SubElement(root, "geojson")
    geo_el.text = json.dumps(geo, ensure_ascii=False, indent=2)

    if style_text != "":
        st = ET.SubElement(root, "style")
        st.text = style_text

    return ET.ElementTree(root)

# ----------------- Dedup helpers (GeoJSON features) -----------------

def _round_num(x: Any, ndigits: int = 7) -> Any:
    """Round floats consistently; leave other types as-is."""
    try:
        if isinstance(x, float):
            return round(x, ndigits)
        if isinstance(x, int):
            return x
    except Exception:
        pass
    return x


def _norm_coords(coords: Any) -> Any:
    """Normalize coordinates recursively (Point -> [lon,lat,...], MultiLineString -> nested lists)."""
    if isinstance(coords, (list, tuple)):
        return [_norm_coords(c) for c in coords]
    return _round_num(coords)


def _norm_props(props: Any) -> Dict[str, Any]:
    """Normalize only stable keys that matter for deduplication."""
    if not isinstance(props, dict):
        return {}

    # Keys that define identity for MS features.
    keys = ("kind", "name", "track_name", "time", "times", "desc", "sym")

    out: Dict[str, Any] = {}
    for k in keys:
        if k in props:
            v = props.get(k)
            if k == "times":
                out[k] = _norm_coords(v)
            else:
                out[k] = v
    return out


def _feature_signature(feat: Any) -> Optional[str]:
    """Build a stable signature for a GeoJSON Feature. Returns None if malformed."""
    if not isinstance(feat, dict):
        return None

    geom = feat.get("geometry")
    props = feat.get("properties")

    if not isinstance(geom, dict):
        return None

    sig_obj = {
        "type": geom.get("type"),
        "coords": _norm_coords(geom.get("coordinates")),
        "props": _norm_props(props),
    }

    try:
        return json.dumps(sig_obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    except Exception:
        return None


def _dedup_features(existing_features: List[Any], new_features: List[Any]) -> Tuple[List[Any], int]:
    """Filter new_features removing those already present in existing_features."""
    seen: set[str] = set()
    for f in existing_features:
        s = _feature_signature(f)
        if s is not None:
            seen.add(s)

    filtered: List[Any] = []
    removed = 0
    for f in new_features:
        s = _feature_signature(f)
        if s is None:
            filtered.append(f)  # safer than dropping
            continue
        if s in seen:
            removed += 1
            continue
        seen.add(s)
        filtered.append(f)

    return filtered, removed

def append_gpx_into_ms(
        existing_ms_path: Path,
        gpx_path: Path,
        out_ms_path: Optional[Path] = None,
        line_mode: str = "none",
        style_text: Optional[str] = None,
        keep_existing_title: bool = True,
) -> Tuple[int, int]:

    """
    Append conversion result of GPX into an existing .ms file.

    - Reads existing .ms
    - Parses its <geojson> FeatureCollection
    - Converts GPX -> new Feature list
    - Appends new features to existing features
    - Writes back to the same file (or to out_ms_path if provided)

    style_text:
      - None  -> keep existing <style> as is
      - ""    -> remove <style>
      - other -> replace/set <style> to given text
    """

    if out_ms_path is None:
        out_ms_path = existing_ms_path

    if not existing_ms_path.exists():
        raise FileNotFoundError(f"MS file not found: {existing_ms_path}")
    if not gpx_path.exists():
        raise FileNotFoundError(f"GPX file not found: {gpx_path}")

    # --- Load existing MS XML ---
    tree = ET.parse(existing_ms_path)
    root = tree.getroot()

    # --- Find and parse existing GeoJSON ---
    geo_el = root.find("geojson")
    if geo_el is None or geo_el.text is None or not geo_el.text.strip():
        raise ValueError("Existing MS has no <geojson> content to append to.")

    existing_gj = json.loads(geo_el.text.strip())
    existing_features = existing_gj.get("features")
    if not isinstance(existing_features, list):
        existing_features = []
        existing_gj["features"] = existing_features

    # --- Convert GPX -> MS features (generate a temporary MS, then extract its features) ---
    title_new, wpts_new, tracks_new = parse_gpx_full(gpx_path)

    tmp_tree = build_ms_full(
        title=title_new,
        wpts=wpts_new,
        tracks=tracks_new,
        line_mode=line_mode,
        style_text=DEFAULT_STYLE,  # style handled below (we usually keep existing)
    )
    tmp_root = tmp_tree.getroot()
    tmp_geo_el = tmp_root.find("geojson")
    if tmp_geo_el is None or tmp_geo_el.text is None:
        raise ValueError("Internal error: generated MS has no <geojson>.")

    new_gj = json.loads(tmp_geo_el.text.strip())
    new_features = new_gj.get("features", [])
    if not isinstance(new_features, list):
        new_features = []
    # --- Append features (with dedup) ---
    filtered_new_features, duplicates_skipped = _dedup_features(existing_features, new_features)
    existing_features.extend(filtered_new_features)

    added_objects = len(filtered_new_features)
    # duplicates_skipped already computed

    # --- Write updated GeoJSON back ---
    geo_el.text = json.dumps(existing_gj, ensure_ascii=False, indent=2)

    # --- Title handling ---
    if not keep_existing_title:
        name_el = root.find("name")
        if name_el is None:
            name_el = ET.SubElement(root, "name")
        name_el.text = title_new

    # --- Style handling ---
    if style_text is not None:
        style_el = root.find("style")
        if style_text == "":
            # remove style element if exists
            if style_el is not None:
                root.remove(style_el)
        else:
            if style_el is None:
                style_el = ET.SubElement(root, "style")
            style_el.text = style_text

    # --- Save result ---
    tree.write(out_ms_path, encoding="utf-8", xml_declaration=True)

    return (added_objects, duplicates_skipped)
# ----------------- CLI -----------------

def main():
    parser = argparse.ArgumentParser(description="Convert between GPX (wpt+trk) and customMapSource+GeoJSON.")
    parser.add_argument("input")
    parser.add_argument("output")
    parser.add_argument("--append", action="store_true",
                        help="Append GPX conversion result into an existing MS file (output).")

    parser.add_argument("--to", choices=["gpx", "ms"], default=None,
                        help="Target format (default: opposite of input).")

    # grid helper (for waypoint grids; not related to normal tracks)
    parser.add_argument("--line-mode", choices=["none", "orth", "snake", "both"], default="none",
                        help="When converting GPX->MS: optional grid lines derived from waypoint names.")

    parser.add_argument("--style", default=DEFAULT_STYLE,
                        help='Style text for <style>. Use --style "" to omit.')

    args = parser.parse_args()

    inp = Path(args.input)
    out = Path(args.output)

    src = detect_format(inp)
    dst = args.to if args.to else ("ms" if src == "gpx" else "gpx")
    if args.append:
        if not (src == "gpx" and dst == "ms"):
            raise SystemExit("--append is only supported for GPX input -> MS output.")
        added, skipped = append_gpx_into_ms(
            existing_ms_path=out,
            gpx_path=inp,
            out_ms_path=out,
            line_mode=args.line_mode,
            style_text=None,
            keep_existing_title=True,
        )
        print(f"Append: +{added}, duplicates skipped: {skipped}")
        return


    if src == "gpx" and dst == "ms":
        title, wpts, tracks = parse_gpx_full(inp)
        tree = build_ms_full(title, wpts, tracks, line_mode=args.line_mode, style_text=args.style)
        tree.write(out, encoding="utf-8", xml_declaration=True)
        print("Done.")
        return

    if src == "ms" and dst == "gpx":
        title, wpts, tracks, _style = parse_ms_full(inp)
        tree = build_gpx_full(title, wpts, tracks)
        tree.write(out, encoding="utf-8", xml_declaration=True)
        print("Done.")
        return

    raise SystemExit("Unsupported conversion path.")


if __name__ == "__main__":
    main()

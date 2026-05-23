#!/usr/bin/env python3
"""Minimal SVG -> Android Vector Drawable converter for the Cburnett chess piece set.

Handles only the SVG features used by Cburnett:
  - root <svg viewBox="0 0 W H">
  - one or more <g> groups with shared fill / stroke / stroke-* attributes
  - <path d="...">

Each path inherits group-level attributes, overridden by its own. Output is a single
<vector ...> with a flat list of <path .../> children.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SVG_NS = "{http://www.w3.org/2000/svg}"

# SVG attr -> Android attr. Attributes not in this map are dropped.
ATTR_MAP = {
    "fill": "android:fillColor",
    "stroke": "android:strokeColor",
    "stroke-width": "android:strokeWidth",
    "stroke-linecap": "android:strokeLineCap",
    "stroke-linejoin": "android:strokeLineJoin",
    "stroke-miterlimit": "android:strokeMiterLimit",
    "fill-rule": "android:fillType",
    "fill-opacity": "android:fillAlpha",
    "stroke-opacity": "android:strokeAlpha",
    "opacity": "android:fillAlpha",
}

# SVG value -> Android value where they differ.
ENUM_MAP = {
    "evenodd": "evenOdd",
    "nonzero": "nonZero",
}


def parse_color(raw):
    if raw is None or raw.strip() == "none":
        return None
    c = raw.strip()
    if c.startswith("#"):
        body = c[1:]
        if len(body) == 3:
            body = "".join(ch * 2 for ch in body)
        if len(body) == 6:
            return "#FF" + body.upper()
        if len(body) == 8:
            return "#" + body.upper()
    return c  # named colors etc.; vector drawable accepts most


def translate_attrs(svg_attrs):
    """Translate SVG attrs to Android attrs (dict).

    SVG's default fill is *black* when no fill attribute is present anywhere; Android
    Vector Drawable's default is *no fill* (transparent). Emit an explicit black
    fillColor when the SVG didn't specify one, otherwise the path renders as an outline
    only and silhouette pieces (Cburnett black pawn, body of black rook, etc.) appear
    hollow.
    """
    out = {}
    has_fill_attr = False
    for key, val in svg_attrs.items():
        if key in ("fill", "stroke"):
            if key == "fill":
                has_fill_attr = True
            mapped = parse_color(val)
            if mapped is None:
                continue
            out[ATTR_MAP[key]] = mapped
        elif key in ATTR_MAP:
            out[ATTR_MAP[key]] = ENUM_MAP.get(val, val)
    if not has_fill_attr:
        out["android:fillColor"] = "#FF000000"
    return out


def circle_to_path(cx: float, cy: float, r: float) -> str:
    """Express a circle as two SVG arcs (Android Vector Drawable understands the same
    path mini-language as SVG, so this works directly as android:pathData)."""
    return (
        f"M{cx - r},{cy} "
        f"a{r},{r} 0 1,0 {2 * r},0 "
        f"a{r},{r} 0 1,0 {-2 * r},0 Z"
    )


def collect_paths(node, inherited):
    """Recursively walk <g> and <path>, producing (path_data, attrs) for each path."""
    if node.tag == SVG_NS + "g":
        new_inherited = dict(inherited)
        new_inherited.update(node.attrib)
        result = []
        for child in node:
            result.extend(collect_paths(child, new_inherited))
        return result
    elif node.tag == SVG_NS + "path":
        attrs = dict(inherited)
        attrs.update(node.attrib)
        data = attrs.pop("d", None)
        if not data:
            return []
        return [(data, attrs)]
    elif node.tag == SVG_NS + "circle":
        attrs = dict(inherited)
        attrs.update(node.attrib)
        try:
            cx = float(attrs.pop("cx", "0"))
            cy = float(attrs.pop("cy", "0"))
            r = float(attrs.pop("r", "0"))
        except ValueError:
            return []
        return [(circle_to_path(cx, cy, r), attrs)]
    return []


def convert(svg_path: Path) -> str:
    tree = ET.parse(svg_path)
    root = tree.getroot()
    vb = root.attrib.get("viewBox", "0 0 24 24").split()
    vp_w, vp_h = float(vb[2]), float(vb[3])

    paths = []
    for child in root:
        paths.extend(collect_paths(child, {}))

    # Emit Android XML.
    width_dp = int(round(vp_w))
    height_dp = int(round(vp_h))
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{width_dp}dp"',
        f'    android:height="{height_dp}dp"',
        f'    android:viewportWidth="{vp_w}"',
        f'    android:viewportHeight="{vp_h}">',
    ]
    for data, attrs in paths:
        a = translate_attrs(attrs)
        a["android:pathData"] = data
        lines.append("    <path")
        for k, v in a.items():
            # Escape any quotes in data — shouldn't be present in Cburnett paths.
            escaped = v.replace('"', "&quot;")
            lines.append(f'        {k}="{escaped}"')
        # Close on last attribute line.
        lines[-1] = lines[-1] + " />"
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def main():
    if len(sys.argv) < 3:
        print("Usage: svg2vd.py <input_dir> <output_dir>", file=sys.stderr)
        sys.exit(1)
    in_dir = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)

    for svg in sorted(in_dir.glob("*.svg")):
        # Translate e.g. "wK.svg" -> "piece_wk.xml" (drawable names must be lowercase).
        base = svg.stem.lower()
        out_path = out_dir / f"piece_{base}.xml"
        out_path.write_text(convert(svg))
        print(f"{svg.name} -> {out_path}")


if __name__ == "__main__":
    main()

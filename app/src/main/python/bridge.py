from pathlib import Path
import traceback
import converter3


def convert(input_path: str,
            output_path: str,
            to: str = None,
            line_mode: str = "none",
            style: str = "") -> str:
    """
    Универсальный вызов конвертера из Android.

    :param input_path: путь к входному файлу
    :param output_path: путь к выходному файлу
    :param to: "gpx", "ms" или None (auto)
    :param line_mode: none / orth / snake / both
    :param style: текст стиля для MS (если пусто — используется DEFAULT_STYLE)
    :return: строка статуса
    """

    try:
        inp = Path(input_path)
        out = Path(output_path)

        if not inp.exists():
            raise RuntimeError(f"Input file not found: {inp}")

        # Определяем исходный формат
        src_format = converter3.detect_format(inp)

        # Определяем целевой формат
        if to:
            dst_format = to.lower()
        else:
            dst_format = "ms" if src_format == "gpx" else "gpx"

        # --- GPX → MS ---
        if src_format == "gpx" and dst_format == "ms":
            title, wpts, tracks = converter3.parse_gpx_full(inp)

            tree = converter3.build_ms_full(
                title=title,
                wpts=wpts,
                tracks=tracks,
                line_mode=line_mode,
                style_text=style if style else converter3.DEFAULT_STYLE
            )

            tree.write(out, encoding="utf-8", xml_declaration=True)

            return f"OK: GPX → MS | waypoints={len(wpts)} tracks={len(tracks)}"

        # --- MS → GPX ---
        if src_format == "ms" and dst_format == "gpx":
            title, wpts, tracks, _style = converter3.parse_ms_full(inp)

            tree = converter3.build_gpx_full(
                title=title,
                wpts=wpts,
                tracks=tracks
            )

            tree.write(out, encoding="utf-8", xml_declaration=True)

            return f"OK: MS → GPX | waypoints={len(wpts)} tracks={len(tracks)}"

        raise RuntimeError(f"Unsupported conversion: {src_format} → {dst_format}")

    except Exception as e:
        # Возвращаем читаемую ошибку в Android UI
        return "ERROR:\n" + str(e) + "\n\n" + traceback.format_exc()

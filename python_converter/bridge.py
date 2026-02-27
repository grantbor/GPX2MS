from __future__ import annotations

from pathlib import Path
import traceback
import converter3


def convert(
        input_path: str,
        output_path: str,
        to: str = None,
        line_mode: str = "none",
        style: str = "",
        append: bool = False,
) -> str:
    """
    Универсальный вызов конвертера из Android.

    :param input_path: путь к входному файлу
    :param output_path: путь к выходному файлу
    :param to: "gpx", "ms" или None (auto)
    :param line_mode: none / orth / snake / both (имеет смысл только для GPX->MS)
    :param style:
        Для GPX->MS:
          - ""  -> DEFAULT_STYLE (обычная конвертация)
          - "#RRGGBB"/"#AARRGGBB" -> применяется как цвет (converter3 развернёт в MapCSS)
          - "node {...} line {...}" -> полный MapCSS
        Для append (GPX->MS):
          - ""  -> НЕ менять стиль существующего MS (оставить как есть)
          - "#RRGGBB"/MapCSS -> перезаписать стиль в MS (перекрасит весь файл)
    :param append: если True и делаем GPX->MS, то дописываем результат в существующий MS (output_path)
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
        dst_format = to.lower() if to else ("ms" if src_format == "gpx" else "gpx")

        # Для append: если style == "" -> не менять стиль (это важно для UX).
        # Для обычной конвертации: style == "" -> DEFAULT_STYLE (как было раньше).
        style_arg = style
        if src_format == "gpx" and dst_format == "ms" and not append:
            if style_arg is None or str(style_arg).strip() == "":
                style_arg = converter3.DEFAULT_STYLE

        # Запускаем конвертацию через единый API
        res = converter3.convert_file(
            inp,
            out,
            to=dst_format,          # можно None, но здесь явно
            line_mode=line_mode,
            style=style_arg,
            append=bool(append),
        )
        return res.message

    except Exception as e:
        return "ERROR:\n" + str(e) + "\n\n" + traceback.format_exc()
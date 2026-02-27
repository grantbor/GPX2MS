# GPX2MS

Конвертер GPX ↔ MS (customMapSource).

Поддерживается:

- ✅ GPX → MS
- ✅ MS → GPX
- ✅ Настройка line-mode (python only)
- ✅ Настройка style 
- ✅ Append GPX → существующий `.ms` (добавление новых объектов без удаления старых с дедупликацией)

Проект состоит из двух независимых и функционально почти идентичных приложений - конвертер на python (python_converter/converter3.py, синтаксис ниже) с графической обоолочкой (python_converter/gpx2ms.py, требует PySide6) и Андроид-приложение. 

Проект создан с использованием ИИ

---

# Системные требования

Python 3.8+

---

# Использование

## Полный синтаксис

```bash
python converter.py INPUT OUTPUT \
    [--to {gpx,ms}] \
    [--append] \
    [--line-mode {none,orth,snake,both}] \
    [--style STYLE]
```

---

# Параметры

## INPUT

Входной файл:

- `.gpx`
- `.ms`

## OUTPUT

Выходной файл:

- `.ms` при конвертации из GPX
- `.gpx` при конвертации из MS

---

## `--to`

Явно указать направление конвертации:

```bash
--to gpx
--to ms
```

Если не указано — определяется автоматически по расширению файлов.

---

## `--append`

Режим добавления (append).

Используется **только при GPX → MS**.

Вместо создания нового `.ms`:

1. Конвертер читает существующий `.ms` (файл OUTPUT должен существовать).
2. Конвертирует GPX в GeoJSON features.
3. Добавляет новые features в массив `features` внутри `<geojson>`.
4. Сохраняет обновлённый `.ms`.

⚠️ Важно:

- OUTPUT должен быть существующим `.ms` файлом.
- Если файл не существует — используйте обычную конвертацию без `--append`.
- Рекомендуется сделать резервную копию `.ms` перед использованием append.

### Пример

```bash
python converter.py input.gpx map.ms --append
```

---

## `--line-mode`

- `none` — без изменений
- `orth` — ортогональные линии
- `snake` — змейка
- `both` — комбинированный режим

Пример:

```bash
python converter.py input.gpx output.ms --line-mode orth
```

---

## `--style`

Позволяет задать стиль объектов при конвертации GPX → MS.

```bash
python converter.py input.gpx output.ms --style hiking
```

---

# Примеры

## GPX → MS (новый файл)

```bash
python converter.py track.gpx map.ms
```

## GPX → MS (append)

```bash
python converter.py new_track.gpx existing_map.ms --append
```

## MS → GPX

```bash
python converter.py map.ms track.gpx
```

# Графическая оболочка

```bash
python gpx2ms.py
```
# История версий приложения для Android
- 0.1 - 0.9 - Python + Chaquopy
- 1.0 - Переезд на Kotlin

# Ограничения

- Append работает только для GPX → MS.
- OUTPUT при использовании `--append` должен существовать.
- Конвертер предполагает корректную структуру GPX и MS.

---

# Лицензия

MIT License

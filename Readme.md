# GPX2MS

Конвертер GPX ↔ MS (customMapSource).

Поддерживается:

- ✅ GPX → MS
- ✅ MS → GPX
- ✅ Настройка line-mode
- ✅ Настройка style
- ✅ Append GPX → существующий `.ms` (добавление новых объектов без удаления старых)

Проект написан на Python.  
Android-приложение является оболочкой над этим конвертером (через Chaquopy).

Проект создан с использованием ИИ

---

# Установка

Требуется Python 3.8+

```bash
git clone https://github.com/grantbor/GPX2MS.git
cd GPX2MS2/app/src/main/python/
```

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

---

# Android wrapper

Android-приложение является оболочкой над Python-конвертером.

- **Convert** — создаёт результат и предлагает Save / Share / Open in Guru Maps / Append.
- **Append** — предлагает выбрать target `.ms` и дописывает в него данные из выбранного `.gpx`.

---

# Графическая оболочка

```bash
cd app/src/main/python/
python gpx2ms.py
```

## Сборка с помощью Pyinstaller

### Linux

```bash
python -m venv .venv
source .venv/bin/activate
pip install -U pip
pip install PySide6 pyinstaller
pyinstaller --noconfirm --windowed --onedir gpx2ms.py \
  --hidden-import "converter3.py:."
```
  ---OR---
```  
pyinstaller --noconfirm --windowed --onefile gpx2ms.py \
  --hidden-import "converter3.py:."
```
### Windows

```PowerShell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -U pip
pip install PySide6 pyinstaller
pyinstaller --noconfirm --windowed --onedir gpx2ms.py `
  --hidden-import "converter3.py;."
```
---OR---
```
pyinstaller --noconfirm --windowed --onefile gpx2ms.py `
  --hidden-import "converter3.py;."
```



# Ограничения

- Append работает только для GPX → MS.
- OUTPUT при использовании `--append` должен существовать.
- Конвертер предполагает корректную структуру GPX и MS.

---

# Лицензия

MIT License

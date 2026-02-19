# GPX ↔ customMapSource Converter

Утилита для конвертации между:

- **GPX 1.1**
  - Waypoints (`<wpt>`)
  - Tracks (`<trk>`, `<trkseg>`, `<trkpt>`)
- **customMapSource + GeoJSON**
  - `Point`
  - `LineString`
  - `MultiLineString`

Поддерживается:

- ✅ Треки, сохранённые с навигатора
- ✅ Пользовательские точки
- ✅ Высота (`ele`)
- ✅ Время (`time`)
- ✅ Несколько сегментов трека
- ✅ Генерация сетки по именованным точкам (опционально)
- ✅ Обратная конверсия без потери структуры

---

# Требования

- Python 3.9+
- Внешние библиотеки **не требуются**

Проверка версии Python:

```bash
python --version
```

---

# Быстрый старт

GPX → MS:

```bash
python converter.py input.gpx output.ms
```

MS → GPX:

```bash
python converter.py input.ms output.gpx
```

Формат назначения определяется автоматически.

---

# Полный синтаксис

```bash
python converter.py INPUT OUTPUT [--to {gpx,ms}] [--line-mode {none,orth,snake,both}] [--style STYLE]
```

---

# Аргументы

## INPUT

Входной файл:

- `.gpx`
- `.ms`
- `.xml`

## OUTPUT

Выходной файл.

---

# Опции

## `--to {gpx,ms}`

Принудительное указание целевого формата.

Если не указан — выбирается автоматически (противоположный входному).

Примеры:

```bash
--to ms
--to gpx
```

---

## `--line-mode {none,orth,snake,both}`

Используется **только при конвертации GPX → MS**  
и применяется только к waypoints с именами сеточного типа:

```
A1, B2, AA-12, AH_23 и т.п.
```

### Режимы

| Режим | Поведение |
|--------|-----------|
| none   | Без генерации линий |
| orth   | Горизонтали + вертикали |
| snake  | Одна непрерывная “змейка” |
| both   | И orth, и snake |

Пример:

```bash
python converter.py grid.gpx grid.ms --line-mode orth
```

⚠ Для обычных треков навигатора этот параметр **не нужен**.

---

## `--style`

Текст тега `<style>` для MS-файла.

Убрать стиль полностью:

```bash
--style ""
```

---

# Примеры использования

---

## 1️⃣ Обычный трек навигатора (trk + wpt)

GPX → MS:

```bash
python converter.py Navigator.gpx Navigator.ms
```

MS → GPX:

```bash
python converter.py Navigator.ms Navigator_back.gpx
```

Что происходит:

- `<wpt>` → GeoJSON `Point`
- `<trk>/<trkseg>` → `MultiLineString`
- `<ele>` → Z-координата
- `<time>` → properties.times

---

## 2️⃣ Сетка точек (A1, B2, …)

Горизонтали + вертикали:

```bash
python converter.py Grid.gpx Grid.ms --line-mode orth
```

Змейка:

```bash
python converter.py Grid.gpx Grid.ms --line-mode snake
```

Оба режима:

```bash
python converter.py Grid.gpx Grid.ms --line-mode both
```

---

## 3️⃣ Принудительное указание формата

```bash
python converter.py file.gpx file.ms --to ms
```

---

# Поддерживаемая структура GPX

## Waypoints

```xml
<wpt lat="..." lon="...">
  <name>PointName</name>
  <ele>123</ele>
  <time>...</time>
</wpt>
```

Конвертируется в GeoJSON `Point`.

---

## Tracks

```xml
<trk>
  <name>TrackName</name>
  <trkseg>
    <trkpt lat="..." lon="...">
      <ele>...</ele>
      <time>...</time>
    </trkpt>
  </trkseg>
</trk>
```

Конвертируется в GeoJSON `MultiLineString`.

Если сегментов несколько — создаётся несколько линий внутри MultiLineString.

---

# Обратная конверсия MS → GPX

| GeoJSON | GPX |
|----------|------|
| Point | `<wpt>` |
| LineString | `<trk>` |
| MultiLineString | `<trk>` с несколькими `<trkseg>` |

Сохраняются:

- координаты
- высота
- временные метки (если присутствуют)

---

# Упаковка в .exe (Windows)

## Установка PyInstaller

```bash
pip install pyinstaller
```

## Сборка

```bash
pyinstaller --onefile --name converter converter.py
```

Готовый файл:

```
dist/converter.exe
```

Запуск:

```bash
dist\\converter.exe Navigator.gpx Navigator.ms
```

---

# Частые ошибки

## ❌ unrecognized arguments: -line-mode

Нужно использовать два дефиса:

```bash
--line-mode
```

---

## ❌ Пустой результат

Если GPX содержит только `<trk>` без `<wpt>`,  
grid-режимы (`orth`, `snake`) не применяются — это нормально.

---

# Рекомендации

- Для обычных треков навигатора запускайте без `--line-mode`
- Grid-режим используйте только для сеток точек
- Проверяйте результат в GIS-программе или целевом приложении

---

# Версия

GPX ↔ customMapSource Converter  
Поддержка: GPX 1.1 + customMapSource (GeoJSON)


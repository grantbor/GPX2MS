import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

// NOTE: keep reserved prefixes for tolerant XML parsing
val _RESERVED_PREFIXES = setOf("xml", "xmlns") // нельзя объявлять как xmlns:xml / xmlns:xmlns

fun parseXmlTolerant(path: File): Document {
    var data = String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8)
        .replace("\uFFFD", "") // replacement for errors="replace"

    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    val builder = factory.newDocumentBuilder()

    try {
        return builder.parse(InputSource(StringReader(data)))
    } catch (e: Exception) {
        val msg = e.toString()
        if (!msg.contains("prefix") && !msg.contains("bound")) {
            throw e
        }

        // Префиксы в тегах: <x:tag ...> </x:tag>
        val tagPrefixes = Regex("""</?\s*([A-Za-z_][\w.-]*):""").findAll(data)
            .map { it.groupValues[1] }.toSet()

        // Префиксы в атрибутах: xsi:schemaLocation="..." (но НЕ xmlns:ql="...")
        val attrPrefixes = Regex("""\s([A-Za-z_][\w.-]*):[A-Za-z_][\w.-]*\s*=""").findAll(data)
            .map { it.groupValues[1] }
            .filter { it !in _RESERVED_PREFIXES }
            .toSet()

        val prefixes = (tagPrefixes + attrPrefixes - _RESERVED_PREFIXES).sorted()

        // Уже объявленные xmlns:*
        val declared = Regex("""\sxmlns:([A-Za-z_][\w.-]*)=""").findAll(data)
            .map { it.groupValues[1] }.toMutableSet()
        declared.addAll(_RESERVED_PREFIXES)

        val missing = prefixes.filter { it !in declared }
        if (missing.isEmpty()) {
            throw e
        }

        val injectRegex = Regex("""<[^>]*>""")
        val data2 = injectRegex.replaceFirst(data) { m ->
            val head = m.value
            val extra = missing.joinToString("") { " xmlns:$it=\"urn:autofix:$it\"" }
            head.dropLast(1) + extra + ">"
        }
        return builder.parse(InputSource(StringReader(data2)))
    }
}

const val GPX_NS = "http://www.topografix.com/GPX/1/1"

const val CIRCLE_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\" viewBox=\"0 0 8 8\"><circle cx=\"4\" cy=\"4\" r=\"3\" fill=\"#888\"/></svg>"
val CIRCLE_SVG_B64 = Base64.getEncoder().encodeToString(CIRCLE_SVG.toByteArray(StandardCharsets.UTF_8))

val _HEX_COLOR_RE = Pattern.compile("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

fun makeStyle(color: String = "#00FFFF"): String {
    val c = color.trim().let { if (it.isEmpty()) "#00FFFF" else it }
    // One MapCSS style per MS file (applies to all objects in the file).
    return """
node {
    text: eval(tag("name"));
    details-text: eval(tag("name"));
    details-description: eval(tag("name"));
    text-color: black;
    font-stroke-width: 5px;
    font-stroke-color: yellow;
    icon-image: eval(data("$CIRCLE_SVG_B64"));
    icon-scale: 1;
    icon-tint: $c;
}
line {
    color: $c;
    width: 3px;
}
""".trim()
}

fun styleFromArg(style: String?): String? {
    /** Normalize style argument.

    Accepted inputs:
      - None => None
      - ""   => "" (special meaning: keep existing style on append)
      - "#RRGGBB" or "#AARRGGBB" => expanded to full MapCSS via make_style()
      - otherwise => returned as-is (assumed to already be MapCSS)
    */
    if (style == null) return null
    val s = style.trim()
    if (s == "") return ""
    if (_HEX_COLOR_RE.matcher(s).matches()) {
        return makeStyle(s)
    }
    return s
}

val DEFAULT_STYLE = makeStyle("#00FFFF")

// Grid-ish names: A1, a-01, AA 12, ah_23, etc.
val GRID_RE = Pattern.compile("^\\s*([A-Za-z]+)\\s*[-_ ]*\\s*(\\d+)\\s*$")

data class NamedPoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val ele: Double = 0.0,
    val timeIso: String? = null,
    val desc: String? = null,
    val sym: String? = null
)

data class Track(
    val name: String,
    // list of segments; each segment is list of points
    val segments: List<List<NamedPoint>>
)

// ----------------- small helpers -----------------
fun nowIso(): String {
    return ZonedDateTime.now(ZoneId.of("UTC"))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
}

fun _text(el: Element?): String? {
    if (el == null) return null
    val t = el.textContent?.trim()
    return if (t.isNullOrEmpty()) null else t
}

fun detectFormat(path: File): String {
    val low = path.name.lowercase()
    if (low.endsWith(".gpx")) return "gpx"
    if (low.endsWith(".ms")) return "ms"

    // fallback: sniff
    val content = String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8)
    val head = content.take(2000).lowercase()
    if ("<gpx" in head) return "gpx"
    if ("<custommapsource" in head || "<geojson" in head) return "ms"
    throw IllegalArgumentException("Unknown format: $path")
}

fun _findText(parent: Element, localName: String): String? {
    val children = parent.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child.nodeType == Node.ELEMENT_NODE) {
            val element = child as Element
            var tag = element.tagName
            if (tag.contains("}")) {
                tag = tag.substringAfter("}")
            } else if (tag.contains(":")) {
                tag = tag.substringAfter(":")
            }
            if (tag == localName) {
                return _text(element)
            }
        }
    }
    return null
}

fun _parsePointElement(el: Element, isWpt: Boolean): NamedPoint {
    val lat = el.getAttribute("lat").toDouble()
    val lon = el.getAttribute("lon").toDouble()

    var ele = 0.0
    val eleText = _findText(el, "ele")
    if (eleText != null) {
        try {
            ele = eleText.toDouble()
        } catch (e: NumberFormatException) {
            ele = 0.0
        }
    }

    val t = _findText(el, "time")
    val name = _findText(el, "name") ?: (if (!isWpt) "" else "wpt")
    val desc = _findText(el, "desc")
    val sym = _findText(el, "sym")
    return NamedPoint(name = name, lat = lat, lon = lon, ele = ele, timeIso = t, desc = desc, sym = sym)
}

fun parseGpxFull(path: File): Triple<String, List<NamedPoint>, List<Track>> {
    val doc = parseXmlTolerant(path)
    val root = doc.documentElement

    // Helper to find by NS
    fun findByNS(parent: Element, ns: String, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                val uri = child.namespaceURI
                val ln = child.localName ?: child.tagName.substringAfter(":")
                if ((uri == ns || uri == null) && ln == localName) {
                    result.add(child)
                }
            }
        }
        return result
    }

    // name/title
    var nm: String? = null
    val metadata = findByNS(root, GPX_NS, "metadata").firstOrNull()
    if (metadata != null) {
        nm = _text(findByNS(metadata, GPX_NS, "name").firstOrNull())
    }
    if (nm == null) nm = path.nameWithoutExtension
    if (nm.isEmpty()) nm = path.nameWithoutExtension

    val wpts = mutableListOf<NamedPoint>()
    val tracks = mutableListOf<Track>()

    for (w in findByNS(root, GPX_NS, "wpt")) {
        val lat = w.getAttribute("lat").toDoubleOrNull() ?: 0.0
        val lon = w.getAttribute("lon").toDoubleOrNull() ?: 0.0
        val name = _text(findByNS(w, GPX_NS, "name").firstOrNull()) ?: "wpt"
        val ele = _text(findByNS(w, GPX_NS, "ele").firstOrNull())?.toDoubleOrNull() ?: 0.0
        val t = _text(findByNS(w, GPX_NS, "time").firstOrNull())
        val desc = _text(findByNS(w, GPX_NS, "desc").firstOrNull()) ?: _text(findByNS(w, GPX_NS, "cmt").firstOrNull())
        val sym = _text(findByNS(w, GPX_NS, "sym").firstOrNull())
        wpts.add(NamedPoint(name = name, lat = lat, lon = lon, ele = ele, timeIso = t, desc = desc, sym = sym))
    }

    for (trk in findByNS(root, GPX_NS, "trk")) {
        val tname = _text(findByNS(trk, GPX_NS, "name").firstOrNull()) ?: "track"
        val segments = mutableListOf<List<NamedPoint>>()
        for (seg in findByNS(trk, GPX_NS, "trkseg")) {
            val pts = mutableListOf<NamedPoint>()
            for (p in findByNS(seg, GPX_NS, "trkpt")) {
                val lat = p.getAttribute("lat").toDoubleOrNull() ?: 0.0
                val lon = p.getAttribute("lon").toDoubleOrNull() ?: 0.0
                val ele = _text(findByNS(p, GPX_NS, "ele").firstOrNull())?.toDoubleOrNull() ?: 0.0
                val t = _text(findByNS(p, GPX_NS, "time").firstOrNull())
                pts.add(NamedPoint(name = "", lat = lat, lon = lon, ele = ele, timeIso = t))
            }
            if (pts.isNotEmpty()) {
                segments.add(pts)
            }
        }
        if (segments.isNotEmpty()) {
            tracks.add(Track(name = tname, segments = segments))
        }
    }

    return Triple(nm!!, wpts, tracks)
}

fun buildGpxFull(title: String, wpts: List<NamedPoint>, tracks: List<Track>): Document {
    val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val doc = db.newDocument()
    val root = doc.createElementNS(GPX_NS, "gpx")
    root.setAttribute("version", "1.1")
    root.setAttribute("creator", "GPX2MS")
    doc.appendChild(root)

    val meta = doc.createElementNS(GPX_NS, "metadata")
    root.appendChild(meta)
    val nameEl = doc.createElementNS(GPX_NS, "name")
    nameEl.textContent = title
    meta.appendChild(nameEl)

    for (p in wpts) {
        val w = doc.createElementNS(GPX_NS, "wpt")
        w.setAttribute("lat", p.lat.toString())
        w.setAttribute("lon", p.lon.toString())
        root.appendChild(w)
        
        doc.createElementNS(GPX_NS, "name").apply { textContent = p.name; w.appendChild(this) }
        doc.createElementNS(GPX_NS, "ele").apply { textContent = p.ele.toString(); w.appendChild(this) }
        if (p.timeIso != null) {
            doc.createElementNS(GPX_NS, "time").apply { textContent = p.timeIso; w.appendChild(this) }
        }
        if (p.desc != null) {
            doc.createElementNS(GPX_NS, "desc").apply { textContent = p.desc; w.appendChild(this) }
        }
        if (p.sym != null) {
            doc.createElementNS(GPX_NS, "sym").apply { textContent = p.sym; w.appendChild(this) }
        }
    }

    for (t in tracks) {
        val trk = doc.createElementNS(GPX_NS, "trk")
        root.appendChild(trk)
        doc.createElementNS(GPX_NS, "name").apply { textContent = t.name; trk.appendChild(this) }
        for (seg in t.segments) {
            val segEl = doc.createElementNS(GPX_NS, "trkseg")
            trk.appendChild(segEl)
            for (p in seg) {
                val pt = doc.createElementNS(GPX_NS, "trkpt")
                pt.setAttribute("lat", p.lat.toString())
                pt.setAttribute("lon", p.lon.toString())
                segEl.appendChild(pt)
                doc.createElementNS(GPX_NS, "ele").apply { textContent = p.ele.toString(); pt.appendChild(this) }
                if (p.timeIso != null) {
                    doc.createElementNS(GPX_NS, "time").apply { textContent = p.timeIso; pt.appendChild(this) }
                }
            }
        }
    }

    return doc
}

// ----------------- MS parsing/building -----------------
// A minimal JSON utility to avoid dependencies
object MinimalJson {
    fun parse(json: String): Any? {
        val trimmed = json.trim()
        if (trimmed.startsWith("{")) return parseObject(trimmed)
        if (trimmed.startsWith("[")) return parseArray(trimmed)
        return null
    }

    private fun parseObject(json: String): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val content = json.substring(json.indexOf('{') + 1, json.lastIndexOf('}')).trim()
        if (content.isEmpty()) return map
        
        var i = 0
        while (i < content.length) {
            // key
            val keyStart = content.indexOf('"', i) + 1
            val keyEnd = content.indexOf('"', keyStart)
            val key = content.substring(keyStart, keyEnd)
            
            // colon
            val colon = content.indexOf(':', keyEnd)
            
            // value
            i = colon + 1
            while (i < content.length && (content[i] == ' ' || content[i] == '\n' || content[i] == '\r')) i++
            val valueEnd = findValueEnd(content, i)
            val valueStr = content.substring(i, valueEnd).trim()
            map[key] = parseValue(valueStr)
            
            i = valueEnd
            val comma = content.indexOf(',', i)
            if (comma == -1) break
            i = comma + 1
        }
        return map
    }

    private fun parseArray(json: String): List<Any?> {
        val list = mutableListOf<Any?>()
        val content = json.substring(json.indexOf('[') + 1, json.lastIndexOf(']')).trim()
        if (content.isEmpty()) return list
        
        var i = 0
        while (i < content.length) {
            while (i < content.length && (content[i] == ' ' || content[i] == '\n' || content[i] == '\r')) i++
            if (i >= content.length) break
            val valueEnd = findValueEnd(content, i)
            val valueStr = content.substring(i, valueEnd).trim()
            list.add(parseValue(valueStr))
            i = valueEnd
            val comma = content.indexOf(',', i)
            if (comma == -1) break
            i = comma + 1
        }
        return list
    }

    private fun findValueEnd(s: String, start: Int): Int {
        var i = start
        if (s[i] == '"') {
            i++
            while (i < s.length) {
                if (s[i] == '"' && s[i - 1] != '\\') return i + 1
                i++
            }
        } else if (s[i] == '{' || s[i] == '[') {
            val open = s[i]
            val close = if (open == '{') '}' else ']'
            var count = 1
            i++
            while (i < s.length) {
                if (s[i] == open) count++
                else if (s[i] == close) count--
                if (count == 0) return i + 1
                i++
            }
        } else {
            while (i < s.length && s[i] != ',' && s[i] != '}' && s[i] != ']') i++
        }
        return i
    }

    private fun parseValue(s: String): Any? {
        if (s == "null") return null
        if (s == "true") return true
        if (s == "false") return false
        if (s.startsWith("\"")) return s.substring(1, s.length - 1)
        if (s.startsWith("{")) return parseObject(s)
        if (s.startsWith("[")) return parseArray(s)
        return s.toDoubleOrNull() ?: s
    }

    fun stringify(obj: Any?): String {
        return when (obj) {
            null -> "null"
            is String -> "\"${obj.replace("\"", "\\\"")}\""
            is Number, is Boolean -> obj.toString()
            is Map<*, *> -> {
                val entries = obj.entries.joinToString(",") { "\"${it.key}\":${stringify(it.value)}" }
                "{$entries}"
            }
            is List<*> -> {
                val elements = obj.joinToString(",") { stringify(it) }
                "[$elements]"
            }
            else -> "\"$obj\""
        }
    }
}

fun parseMsFull(path: File): Triple<String, List<NamedPoint>, List<Track>> {
    val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val doc = db.parse(path)
    val root = doc.documentElement

    val titleEl = (0 until root.childNodes.length).map { root.childNodes.item(it) }
        .find { it is Element && it.tagName == "name" } as? Element
    val title = titleEl?.textContent?.trim() ?: path.nameWithoutExtension

    val styleEl = (0 until root.childNodes.length).map { root.childNodes.item(it) }
        .find { it is Element && it.tagName == "style" } as? Element
    val styleText = styleEl?.textContent

    val geoEl = (0 until root.childNodes.length).map { root.childNodes.item(it) }
        .find { it is Element && it.tagName == "geojson" } as? Element
    if (geoEl == null || geoEl.textContent == null) throw IllegalArgumentException("No <geojson> found")

    val gj = MinimalJson.parse(geoEl.textContent.trim()) as? Map<String, Any?> ?: throw IllegalArgumentException("Invalid JSON")
    val features = gj["features"] as? List<Any?> ?: emptyList()

    val wpts = mutableListOf<NamedPoint>()
    val tracks = mutableListOf<Track>()

    for (feat in features) {
        if (feat !is Map<*, *>) continue
        val geom = feat["geometry"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val props = feat["properties"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val gtype = geom["type"] as? String

        if (gtype == "Point") {
            val coords = geom["coordinates"] as? List<*> ?: emptyList<Any?>()
            if (coords.size < 2) continue
            val lon = (coords[0] as? Number)?.toDouble() ?: 0.0
            val lat = (coords[1] as? Number)?.toDouble() ?: 0.0
            val ele = if (coords.size >= 3) (coords[2] as? Number)?.toDouble() ?: 0.0 else 0.0
            val name = (props["name"] ?: "wpt").toString()
            val t = props["time"] as? String
            val desc = props["desc"] as? String
            val sym = props["sym"] as? String
            wpts.add(NamedPoint(name = name, lat = lat, lon = lon, ele = ele, timeIso = t, desc = desc, sym = sym))
        } else if (gtype == "MultiLineString") {
            val coords = geom["coordinates"] as? List<*> ?: emptyList<Any?>()
            val times = props["times"] as? List<*>
            val name = (props["name"] ?: "track").toString()
            val segments = mutableListOf<List<NamedPoint>>()
            for (si in coords.indices) {
                val seg = coords[si] as? List<*> ?: continue
                val pts = mutableListOf<NamedPoint>()
                val segTimes = if (times != null && si < times.size) times[si] as? List<*> else null
                for (pi in seg.indices) {
                    val c = seg[pi] as? List<*> ?: continue
                    if (c.size < 2) continue
                    val lon = (c[0] as? Number)?.toDouble() ?: 0.0
                    val lat = (c[1] as? Number)?.toDouble() ?: 0.0
                    val ele = if (c.size >= 3) (c[2] as? Number)?.toDouble() ?: 0.0 else 0.0
                    var tIso: String? = null
                    if (segTimes != null && pi < segTimes.size) {
                        tIso = segTimes[pi] as? String
                    }
                    pts.add(NamedPoint(name = "", lat = lat, lon = lon, ele = ele, timeIso = tIso))
                }
                if (pts.isNotEmpty()) {
                    segments.add(pts)
                }
            }
            if (segments.isNotEmpty()) {
                tracks.add(Track(name = name, segments = segments))
            }
        }
    }

    // Since Triple is returned, we carry style implicitly or ignore it like Python's return signature
    return Triple(title, wpts, tracks)
}

fun _makeLinesOrth(wpts: List<NamedPoint>): List<List<List<Double>>> = emptyList()
fun _makeLinesSnake(wpts: List<NamedPoint>): List<List<List<Double>>> = emptyList()

fun buildMsFull(
    title: String,
    wpts: List<NamedPoint>,
    tracks: List<Track>,
    lineMode: String = "none", // grid helper for WAYPOINTS only: none|orth|snake|both
    styleText: String = DEFAULT_STYLE
): Document {
    val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val doc = db.newDocument()
    val root = doc.createElement("customMapSource")
    root.setAttribute("overlay", "true")
    doc.appendChild(root)

    doc.createElement("name").apply { textContent = title; root.appendChild(this) }

    val features = mutableListOf<Map<String, Any?>>()

    // Optional grid lines based on waypoint names
    val lm = lineMode.lowercase()
    if (lm in listOf("orth", "snake", "both")) {
        val lines = mutableListOf<List<List<Double>>>()
        if (lm == "orth" || lm == "both") lines.addAll(_makeLinesOrth(wpts))
        if (lm == "snake" || lm == "both") lines.addAll(_makeLinesSnake(wpts))
        if (lines.isNotEmpty()) {
            features.add(mapOf(
                "type" to "Feature",
                "properties" to mapOf("name" to "grid"),
                "geometry" to mapOf("type" to "MultiLineString", "coordinates" to lines)
            ))
        }
    }

    // Tracks -> MultiLineString
    for (tr in tracks) {
        val coords = mutableListOf<List<List<Double>>>()
        val timesOut = mutableListOf<List<String?>>()
        var anyTime = false
        for (seg in tr.segments) {
            val segCoords = mutableListOf<List<Double>>()
            val segTimes = mutableListOf<String?>()
            for (p in seg) {
                segCoords.add(listOf(p.lon, p.lat, p.ele))
                segTimes.add(p.timeIso)
                if (p.timeIso != null) anyTime = true
            }
            if (segCoords.isNotEmpty()) {
                coords.add(segCoords)
                timesOut.add(segTimes)
            }
        }
        val props = mutableMapOf<String, Any?>("name" to tr.name)
        if (anyTime) props["times"] = timesOut
        features.add(mapOf(
            "type" to "Feature",
            "properties" to props,
            "geometry" to mapOf("type" to "MultiLineString", "coordinates" to coords)
        ))
    }

    // Waypoints -> Points
    for (p in wpts) {
        val props = mutableMapOf<String, Any?>("name" to p.name)
        if (p.timeIso != null) props["time"] = p.timeIso
        if (p.desc != null) props["desc"] = p.desc
        if (p.sym != null) props["sym"] = p.sym
        features.add(mapOf(
            "type" to "Feature",
            "properties" to props,
            "geometry" to mapOf("type" to "Point", "coordinates" to listOf(p.lon, p.lat, p.ele))
        ))
    }

    val gj = mapOf("type" to "FeatureCollection", "features" to features)
    doc.createElement("geojson").apply {
        textContent = MinimalJson.stringify(gj)
        root.appendChild(this)
    }

    if (styleText.isNotEmpty()) {
        doc.createElement("style").apply {
            textContent = styleText
            root.appendChild(this)
        }
    }

    return doc
}

// ----------------- Public API (import-friendly) -----------------
data class ConvertResult(
    val message: String,
    val addedCount: Int = 0,
    val skippedDuplicates: Int = 0
)

fun _normF(x: Double, nd: Int): Double {
    return try {
        java.math.BigDecimal.valueOf(x).setScale(nd, java.math.RoundingMode.HALF_UP).toDouble()
    } catch (e: Exception) {
        0.0
    }
}

data class WptKey(
    val name: String,
    val lat: Double,
    val lon: Double,
    val ele: Double,
    val time: String,
    val desc: String,
    val sym: String
)

fun _wptKey(p: NamedPoint): WptKey {
    return WptKey(
        p.name ?: "",
        _normF(p.lat, 7),
        _normF(p.lon, 7),
        _normF(p.ele, 2),
        p.timeIso ?: "",
        p.desc ?: "",
        p.sym ?: ""
    )
}

data class TrackKey(
    val name: String,
    val segments: List<List<WptKey>>
)

fun _trackKey(t: Track): TrackKey {
    val segs = t.segments.map { seg -> seg.map { _wptKey(it) } }
    return TrackKey(t.name ?: "", segs)
}

fun _dedupMerge(
    existingWpts: List<NamedPoint>,
    existingTracks: List<Track>,
    newWpts: List<NamedPoint>,
    newTracks: List<Track>
): Any { // Returns tuple: List<NamedPoint>, List<Track>, Int, Int
    val wptSeen = existingWpts.map { _wptKey(it) }.toMutableSet()
    val trkSeen = existingTracks.map { _trackKey(it) }.toMutableSet()

    val mergedWpts = existingWpts.toMutableList()
    val mergedTracks = existingTracks.toMutableList()

    var added = 0
    var skipped = 0

    for (p in newWpts) {
        val k = _wptKey(p)
        if (k in wptSeen) {
            skipped++
            continue
        }
        wptSeen.add(k)
        mergedWpts.add(p)
        added++
    }

    for (t in newTracks) {
        val k = _trackKey(t)
        if (k in trkSeen) {
            skipped++
            continue
        }
        trkSeen.add(k)
        mergedTracks.add(t)
        added++
    }

    return arrayOf(mergedWpts, mergedTracks, added, skipped)
}

fun writeDocument(doc: Document, file: File) {
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
    val source = DOMSource(doc)
    val result = StreamResult(file)
    transformer.transform(source, result)
}

fun convertFile(
    inputPath: String,
    outputPath: String,
    to: String? = null,
    lineMode: String = "none",
    style: String = DEFAULT_STYLE,
    append: bool = false
): ConvertResult {
    val inp = File(inputPath)
    val out = File(outputPath)

    val src = detectFormat(inp)
    val dst = to ?: if (src == "gpx") "ms" else "gpx"

    val styleNorm = styleFromArg(style)

    if (src == "gpx" && dst == "ms") {
        val (titleNew, wptsNew, tracksNew) = parseGpxFull(inp)

        if (append) {
            var titleOld = out.nameWithoutExtension
            var wptsOld = listOf<NamedPoint>()
            var tracksOld = listOf<Track>()
            var styleOld: String? = null

            if (out.exists()) {
                try {
                    val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    val doc = db.parse(out)
                    val root = doc.documentElement
                    val nm = (0 until root.childNodes.length).map { root.childNodes.item(it) }
                        .find { it is Element && it.tagName == "name" }?.textContent?.trim()
                    titleOld = nm ?: out.nameWithoutExtension
                    
                    val st = (0 until root.childNodes.length).map { root.childNodes.item(it) }
                        .find { it is Element && it.tagName == "style" }?.textContent
                    styleOld = st

                    val res = parseMsFull(out)
                    wptsOld = res.second
                    tracksOld = res.third
                } catch (e: Exception) {
                    titleOld = out.nameWithoutExtension
                }
            }

            val mergeRes = _dedupMerge(wptsOld, tracksOld, wptsNew, tracksNew) as Array<*>
            val mergedWpts = mergeRes[0] as List<NamedPoint>
            val mergedTracks = mergeRes[1] as List<Track>
            val added = mergeRes[2] as Int
            val skipped = mergeRes[3] as Int

            val titleFinal = if (titleOld.isNotEmpty()) titleOld else titleNew
            val chosen = if (styleNorm == null || styleNorm == "") styleOld else styleNorm
            val styleFinal = if (chosen != null && chosen != "") chosen else DEFAULT_STYLE

            val doc = buildMsFull(titleFinal, mergedWpts, mergedTracks, lineMode = lineMode, styleText = styleFinal)
            writeDocument(doc, out)

            return ConvertResult(message = "Append: +$added, duplicates skipped: $skipped", addedCount = added, skippedDuplicates = skipped)
        }

        val styleFinal = if (styleNorm != null && styleNorm != "") styleNorm else DEFAULT_STYLE
        val doc = buildMsFull(titleNew, wptsNew, tracksNew, lineMode = lineMode, styleText = styleFinal)
        writeDocument(doc, out)
        return ConvertResult(message = "Done.", addedCount = wptsNew.size + tracksNew.size, skippedDuplicates = 0)
    }

    if (src == "ms" && dst == "gpx") {
        val (title, wpts, tracks) = parseMsFull(inp)
        val doc = buildGpxFull(title, wpts, tracks)
        writeDocument(doc, out)
        return ConvertResult(message = "Done.", addedCount = wpts.size + tracks.size, skippedDuplicates = 0)
    }

    throw IllegalArgumentException("Unsupported conversion: $src -> $dst")
}

fun main(args: Array<String>) {
    val params = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()
    var append = false

    var i = 0
    while (i < args.size) {
        when {
            args[i] == "--to" -> params["to"] = args[++i]
            args[i] == "--append" -> append = true
            args[i] == "--line-mode" -> params["line-mode"] = args[++i]
            args[i] == "--style" -> params["style"] = args[++i]
            else -> positional.add(args[i])
        }
        i++
    }

    if (positional.size < 2) {
        println("Usage: <input> <output> [--to gpx|ms] [--append] [--line-mode none|orth|snake|both] [--style style]")
        return
    }

    val res = convertFile(
        positional[0],
        positional[1],
        to = params["to"],
        lineMode = params["line-mode"] ?: "none",
        style = params["style"] ?: DEFAULT_STYLE,
        append = append
    )

    println(res.message)
}

fun appendGpxIntoMs(
    existingMsPath: String,
    gpxPath: String,
    outMsPath: String? = null,
    lineMode: String = "none",
    styleText: String? = null,
    keepExistingTitle: Boolean = true
): Pair<Int, Int> {
    val outPath = outMsPath ?: existingMsPath
    val outF = File(outPath)

    var beforeWpts = 0
    var beforeTracks = 0
    if (outF.exists()) {
        try {
            val res = parseMsFull(outF)
            beforeWpts = res.second.size
            beforeTracks = res.third.size
        } catch (e: Exception) {
            beforeWpts = 0
            beforeTracks = 0
        }
    }

    val styleArg = styleText ?: ""

    convertFile(
        gpxPath,
        outPath,
        to = "ms",
        lineMode = lineMode,
        style = styleArg,
        append = true
    )

    val res2 = parseMsFull(File(outPath))
    val addedWpts = Math.max(0, res2.second.size - beforeWpts)
    val addedTracks = Math.max(0, res2.third.size - beforeTracks)
    return Pair(addedWpts, addedTracks)
}

// Emulate Python's if __name__ == "__main__":
class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            main(args)
        }
    }
}

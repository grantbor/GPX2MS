package io.github.grantbor.gpx2ms

/**
 * Модель, представляющая одну точку.
 * Соответствует NamedPoint из Python.
 * @param name Название точки
 * @param lat Широта
 * @param lon Долгота
 * @param ele Высота (по умолчанию 0.0)
 * @param timeIso Время в формате ISO (может быть null)
 * @param desc Описание (может быть null)
 * @param sym Символ (может быть null)
 */
data class NamedPoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val ele: Double = 0.0,
    val timeIso: String? = null,
    val desc: String? = null,
    val sym: String? = null
)

/**
 * Модель, представляющая один трек, состоящий из сегментов.
 * Соответствует Track из Python.
 * @param name Название трека
 * @param segments Список сегментов. Каждый сегмент - это список точек.
 */
data class Track(
    val name: String,
    val segments: List<List<NamedPoint>>
)

/**
 * Результат парсинга файла (GPX или MS).
 * Используется для передачи данных между парсерами и билдерами.
 * @param title Название карты/файла
 * @param waypoints Список отдельных точек (wpt)
 * @param tracks Список треков
 * @param styleText Стиль в формате MapCSS (только для MS, может быть null)
 */
data class ConversionData(
    val title: String,
    val waypoints: List<NamedPoint>,
    val tracks: List<Track>,
    val styleText: String? = null
)

/**
 * Результат работы конвертера.
 * @param message Сообщение для пользователя (успех или ошибка)
 * @param outputBytes Байты выходного файла (null, если ошибка)
 * @param outputExtension Расширение выходного файла ("gpx" или "ms")
 */
data class ConversionResult(
    val message: String,
    val outputBytes: ByteArray?,
    val outputExtension: String
) {
    // Для корректной работы data class с ByteArray нужно переопределить equals/hashCode
    // Но в нашем случае это не критично, можно оставить как есть.
    // Чтобы избежать предупреждений, можно добавить:
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ConversionResult

        if (message != other.message) return false
        if (outputBytes != null) {
            if (other.outputBytes == null) return false
            if (!outputBytes.contentEquals(other.outputBytes)) return false
        } else if (other.outputBytes != null) return false
        if (outputExtension != other.outputExtension) return false

        return true
    }

    override fun hashCode(): Int {
        var result = message.hashCode()
        result = 31 * result + (outputBytes?.contentHashCode() ?: 0)
        result = 31 * result + outputExtension.hashCode()
        return result
    }
}
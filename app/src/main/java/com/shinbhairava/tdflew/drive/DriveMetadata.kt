package com.shinbhairava.tdflew.drive

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Metadata stored in Telegram message captions.
 *
 * Telegram has no real folder API for Saved Messages, so TD Flew uses a tiny,
 * versioned marker plus a Base64-URL encoded path. Both file and folder names
 * support Unicode. The marker is intentionally compact to stay below
 * Telegram's caption limit.
 */
object DriveMetadata {
    private const val FILE_PREFIX = "TDFLEW1:F:"
    private const val FOLDER_PREFIX = "TDFLEW1:D:"
    private const val CHUNK_PREFIX = "TDFLEW1:C:"
    private const val TAG_PREFIX = "TDFLEW1:T:"
    const val MAX_PATH_LENGTH = 480
    const val MAX_NAME_LENGTH = 96
    const val MAX_PATH_BYTES = 720

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    data class Chunk(
        val path: String,
        val group: String,
        val index: Int,
        val total: Int,
    )

    fun withTags(marker: String, tags: Collection<String>): String {
        val clean = tags.mapNotNull { sanitizeTag(it) }.distinct().take(20)
        return if (clean.isEmpty()) marker else "$marker\n$TAG_PREFIX${clean.joinToString(",")}"
    }

    fun parseTags(caption: String): List<String> = caption.lines()
        .firstOrNull { it.startsWith(TAG_PREFIX) }
        ?.removePrefix(TAG_PREFIX)
        ?.split(',')
        ?.mapNotNull(::sanitizeTag)
        ?.distinct()
        .orEmpty()

    private fun sanitizeTag(raw: String): String? = raw.trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}_-]"), "")
        .take(32)
        .takeIf { it.isNotBlank() }

    fun encodeFile(path: String): String {
        val normalized = normalizeFilePath(path)
            ?: throw IllegalArgumentException("Invalid file path")
        return FILE_PREFIX + encoder.encodeToString(normalized.toByteArray(StandardCharsets.UTF_8))
    }

    fun encodeFolder(path: String): String {
        val normalized = normalizeFolderPath(path)
            ?: throw IllegalArgumentException("Invalid folder path")
        return FOLDER_PREFIX + encoder.encodeToString(normalized.toByteArray(StandardCharsets.UTF_8))
    }

    fun encodeChunk(path: String, group: String, index: Int, total: Int): String {
        val normalized = normalizeFilePath(path)
            ?: throw IllegalArgumentException("Invalid file path")
        require(group.isNotBlank() && index in 0 until total && total > 1)
        return "$CHUNK_PREFIX${encoder.encodeToString(normalized.toByteArray(StandardCharsets.UTF_8))}:$group:$index:$total"
    }

    fun parseFilePath(caption: String): String? = decodeMarker(caption, FILE_PREFIX)
        ?.let(::normalizeFilePath)

    fun parseFolderPath(caption: String): String? = decodeMarker(caption, FOLDER_PREFIX)
        ?.let(::normalizeFolderPath)

    fun parseChunk(caption: String): Chunk? {
        val marker = caption.trim().lineSequence().firstOrNull().orEmpty()
        val fields = marker.removePrefix(CHUNK_PREFIX)
            .takeIf { marker.startsWith(CHUNK_PREFIX) }
            ?.split(':') ?: return null
        if (fields.size != 4) return null
        val path = try {
            String(decoder.decode(fields[0]), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val index = fields[2].toIntOrNull() ?: return null
        val total = fields[3].toIntOrNull() ?: return null
        return Chunk(normalizeFilePath(path) ?: return null, fields[1], index, total)
            .takeIf { it.total > 1 && it.index in 0 until it.total && it.group.isNotBlank() }
    }

    fun normalizeFolderPath(raw: String): String? {
        val normalized = normalizeRawPath(raw) ?: return null
        return normalized
    }

    fun normalizeFilePath(raw: String): String? {
        val normalized = normalizeRawPath(raw) ?: return null
        if (normalized == "/") return null
        return normalized
    }

    fun parentOf(path: String): String {
        val normalized = normalizeFilePath(path) ?: return "/"
        val index = normalized.lastIndexOf('/')
        return if (index <= 0) "/" else normalized.substring(0, index)
    }

    fun nameOf(path: String): String {
        val normalized = normalizeRawPath(path) ?: return ""
        return normalized.substringAfterLast('/')
    }

    fun childPath(parent: String, name: String): String? {
        val parentPath = normalizeFolderPath(parent) ?: return null
        val cleanName = sanitizeName(name) ?: return null
        val result = if (parentPath == "/") "/$cleanName" else "$parentPath/$cleanName"
        return normalizeFilePath(result)
            ?.takeIf { parentOf(it) == parentPath }
    }

    /**
     * Makes a safe, display-friendly single path segment. Path separators and
     * control characters are removed instead of being allowed to create
     * unexpected nesting.
     */
    fun sanitizeName(rawName: String): String? {
        val cleaned = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .trim()
            .trim('.')
            .replace(Regex("\\s+"), " ")

        if (cleaned.isBlank()) return null
        if (cleaned.length <= MAX_NAME_LENGTH) return cleaned

        val dot = cleaned.lastIndexOf('.')
        val extension = if (dot > 0 && cleaned.length - dot <= 12) cleaned.substring(dot) else ""
        val baseLimit = (MAX_NAME_LENGTH - extension.length).coerceAtLeast(1)
        return cleaned.take(baseLimit).trimEnd() + extension
    }

    fun sanitizeFolderName(rawName: String): String? {
        val name = sanitizeName(rawName) ?: return null
        if (name == "." || name == "..") return null
        return name
    }

    private fun decodeMarker(caption: String, prefix: String): String? {
        val text = caption.trim().lineSequence().firstOrNull().orEmpty()
        if (!text.startsWith(prefix)) return null
        val encoded = text.removePrefix(prefix)
        if (encoded.isBlank()) return null
        return try {
            String(decoder.decode(encoded), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun normalizeRawPath(raw: String): String? {
        if (raw.isEmpty() || raw.length > MAX_PATH_LENGTH) return null
        if (raw.any { it == '\u0000' || it == '\n' || it == '\r' }) return null

        val absolute = if (raw.startsWith('/')) raw else "/$raw"
        val segments = absolute.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "/"
        if (segments.any { it == "." || it == ".." }) return null
        if (segments.any { it.length > MAX_NAME_LENGTH }) return null

        val normalized = "/" + segments.joinToString("/")
        return normalized.takeIf {
            it.length <= MAX_PATH_LENGTH &&
                it.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATH_BYTES
        }
    }
}

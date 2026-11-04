package com.shinbhairava.tdflew.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return if (index == 0) {
        "${value.toLong()} ${units[index]}"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[index])
    }
}

fun formatDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(dateFormatter)

fun formatShortDate(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(shortDateFormatter)

fun formatTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(timeFormatter)

fun formatDateTime(timestamp: Long): String =
    "${formatDate(timestamp)} · ${formatTime(timestamp)}"

fun dayKey(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return date.toString()
}

fun friendlyDayLabel(timestamp: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(dateFormatter)
    }
}

fun fileSubtitle(mimeType: String, size: Long): String {
    val label = when {
        mimeType.startsWith("image/") -> "Photo"
        mimeType.startsWith("video/") -> "Video"
        mimeType.startsWith("audio/") -> "Audio"
        mimeType.contains("pdf") -> "PDF"
        mimeType.contains("zip") || mimeType.contains("compressed") -> "Archive"
        mimeType.isBlank() -> "File"
        else -> mimeType.substringAfter('/', "File").replaceFirstChar { it.uppercase() }
    }
    return "$label · ${formatBytes(size)}"
}

package com.shinbhairava.tdflew.data

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun driveChatId(): Long? {
        val value = preferences.getLong(KEY_DRIVE_CHAT_ID, 0L)
        return value.takeIf { it != 0L }
    }

    fun driveChatTitle(): String? =
        preferences.getString(KEY_DRIVE_CHAT_TITLE, null)?.takeIf { it.isNotBlank() }

    fun saveDriveChat(chatId: Long, title: String) {
        preferences.edit()
            .putLong(KEY_DRIVE_CHAT_ID, chatId)
            .putString(KEY_DRIVE_CHAT_TITLE, title)
            .apply()
    }

    fun clearDriveChat() {
        preferences.edit()
            .remove(KEY_DRIVE_CHAT_ID)
            .remove(KEY_DRIVE_CHAT_TITLE)
            .apply()
    }

    fun backupUris(): Set<String> = preferences.getStringSet(KEY_BACKUP_URIS, emptySet()).orEmpty()

    fun addBackupUri(uri: String) {
        preferences.edit().putStringSet(KEY_BACKUP_URIS, backupUris() + uri).apply()
    }

    fun removeBackupUri(uri: String) {
        preferences.edit().putStringSet(KEY_BACKUP_URIS, backupUris() - uri).apply()
    }

    fun backupEnabled(): Boolean = preferences.getBoolean(KEY_BACKUP_ENABLED, false)

    fun setBackupEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_BACKUP_ENABLED, enabled).apply()
    }

    fun backupIntervalMinutes(): Long = preferences.getLong(KEY_BACKUP_INTERVAL, 60L)

    fun setBackupIntervalMinutes(minutes: Long) {
        preferences.edit().putLong(KEY_BACKUP_INTERVAL, minutes.coerceAtLeast(15L)).apply()
    }

    fun backupStamp(uri: String, modified: Long, size: Long): Boolean =
        preferences.getString(KEY_BACKUP_STAMPS, "")?.split('|')?.any {
            it.startsWith("$uri=") && it.substringAfter('=').split(':') == listOf(modified.toString(), size.toString())
        } == true

    fun saveBackupStamp(uri: String, modified: Long, size: Long) {
        val current = preferences.getString(KEY_BACKUP_STAMPS, "")
            .orEmpty().split('|').filter { it.isNotBlank() && !it.startsWith("$uri=") }
        preferences.edit().putString(KEY_BACKUP_STAMPS, (current + "$uri=$modified:$size").joinToString("|")).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "tdflew_settings"
        const val KEY_DRIVE_CHAT_ID = "drive_chat_id"
        const val KEY_DRIVE_CHAT_TITLE = "drive_chat_title"
        const val KEY_BACKUP_URIS = "backup_uris"
        const val KEY_BACKUP_ENABLED = "backup_enabled"
        const val KEY_BACKUP_INTERVAL = "backup_interval_minutes"
        const val KEY_BACKUP_STAMPS = "backup_stamps"
    }
}

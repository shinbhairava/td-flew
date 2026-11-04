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

    private companion object {
        const val PREFERENCES_NAME = "tdflew_settings"
        const val KEY_DRIVE_CHAT_ID = "drive_chat_id"
        const val KEY_DRIVE_CHAT_TITLE = "drive_chat_title"
    }
}

@file:Suppress("SpellCheckingInspection")

package com.shinbhairava.tdflew.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.shinbhairava.tdflew.BuildConfig
import com.shinbhairava.tdflew.data.CacheTools
import com.shinbhairava.tdflew.data.FileSaver
import com.shinbhairava.tdflew.data.SavedFile
import com.shinbhairava.tdflew.data.SettingsStore
import com.shinbhairava.tdflew.data.TransferStore
import com.shinbhairava.tdflew.data.TransferStatus
import com.shinbhairava.tdflew.drive.DriveChat
import com.shinbhairava.tdflew.drive.DriveChunkPart
import com.shinbhairava.tdflew.drive.DriveItem
import com.shinbhairava.tdflew.drive.DriveItemKind
import com.shinbhairava.tdflew.drive.DriveMetadata
import com.shinbhairava.tdflew.drive.DriveSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface AuthStep {
    data object Starting : AuthStep
    data object PhoneNumber : AuthStep
    data class Code(
        val hint: String,
        val length: Int?,
        val timeoutSeconds: Int,
    ) : AuthStep

    data class Password(val hint: String?) : AuthStep
    data class EmailAddress(val allowAppleId: Boolean, val allowGoogleId: Boolean) : AuthStep
    data class EmailCode(val hint: String, val length: Int?) : AuthStep
    data class Registration(val termsText: String?) : AuthStep
    data class OtherDevice(val link: String) : AuthStep
    data object SigningOut : AuthStep
    data object Ready : AuthStep
    data class Failure(val message: String) : AuthStep
}

data class UserProfile(
    val id: Long,
    val name: String,
    val username: String?,
    val photoFileId: Int?,
)

enum class ConnectionStatus {
    CONNECTING,
    UP_TO_DATE,
    UPDATING,
    WAITING_FOR_NETWORK,
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
)

private sealed interface SendOutcome {
    data class Sent(val message: TdApi.Message) : SendOutcome
    data class Failed(val code: Int, val message: String) : SendOutcome
}

class TdException(
    val code: Int,
    message: String,
) : Exception(message)

fun friendlyError(error: Throwable): String {
    if (error is TdException) {
        val raw = error.message.orEmpty()
        return when {
            error.code == 420 || raw.startsWith("FLOOD_WAIT") -> {
                val seconds = Regex("(\\d+)").find(raw)?.groupValues?.getOrNull(1)
                if (seconds != null) "Too many attempts. Try again in $seconds seconds." else "Too many attempts. Try again later."
            }

            raw == "PHONE_NUMBER_INVALID" -> "That phone number doesn't look valid. Include the country code."
            raw == "PHONE_CODE_INVALID" -> "That login code is incorrect."
            raw == "PHONE_CODE_EXPIRED" -> "That login code has expired. Request a new one."
            raw == "PASSWORD_HASH_INVALID" -> "That two-step verification password is incorrect."
            raw == "PHONE_NUMBER_BANNED" -> "This phone number is banned from Telegram."
            raw == "PHONE_NUMBER_UNOCCUPIED" -> "No Telegram account uses that phone number yet."
            raw == "CHAT_WRITE_FORBIDDEN" -> "You don't have permission to post in this chat."
            raw == "CHAT_NOT_FOUND" -> "That chat could not be found. Check the ID or link."
            raw == "USERNAME_NOT_OCCUPIED" -> "No public chat uses that username."
            raw == "TAKEOUT_REQUIRED" -> "Telegram requires additional account confirmation."
            raw == "AUTH_KEY_UNREGISTERED" || raw == "SESSION_REVOKED" ->
                "This Telegram session was revoked. Please sign in again."

            raw.isNotBlank() -> raw.replace('_', ' ').lowercase(Locale.getDefault())
                .replaceFirstChar { it.uppercase() }
            else -> "Telegram returned an error (${error.code})."
        }
    }
    return error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}

/**
 * Owns the single TDLib client and turns its callback API into coroutines and
 * observable state for Compose. The client intentionally lives for the whole
 * application process so uploads and downloads survive screen changes.
 */
class TelegramManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val transfers: TransferStore,
) : Client.ResultHandler {

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_HISTORY = 5_000
        private const val MAX_LOGS = 400
        private const val FILE_TIMEOUT_MS = 30 * 60 * 1000L
        private const val MAX_PART_BYTES = 1_900_000_000L
        private const val SEND_TIMEOUT_MS = 30 * 60 * 1000L
        private const val MAX_RECENT_SEND_OUTCOMES = 32
        private val nativeLoaded: Boolean = runCatching {
            System.loadLibrary("tdjni")
        }.isSuccess
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshotMutex = Mutex()
    private val logCounter = AtomicLong(0)

    @Volatile
    private var client: Client? = null

    @Volatile
    private var myUserId: Long = 0L

    @Volatile
    private var readyInitialized: Boolean = false

    @Volatile
    private var pendingTermsId: String? = null

    @Volatile
    private var cachedSnapshot: Pair<Long, DriveSnapshot>? = null
    private val requestedFiles = ConcurrentHashMap.newKeySet<Int>()
    private val fileWaiters = ConcurrentHashMap<Int, MutableList<CompletableDeferred<TdApi.File>>>()
    private val uploadsByPath = ConcurrentHashMap<String, String>()

    private val sendLock = Any()
    private val sendWaiters = HashMap<Long, CompletableDeferred<TdApi.Message>>()
    private val recentSendOutcomes = LinkedHashMap<Long, SendOutcome>()

    private val _authStep = MutableStateFlow<AuthStep>(AuthStep.Starting)
    val authStep: StateFlow<AuthStep> = _authStep.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionStatus.CONNECTING)
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val _driveChat = MutableStateFlow<DriveChat?>(null)
    val driveChat: StateFlow<DriveChat?> = _driveChat.asStateFlow()

    private val _filePaths = MutableStateFlow<Map<Int, String>>(emptyMap())
    val filePaths: StateFlow<Map<Int, String>> = _filePaths.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun start() {
        if (client != null) return
        if (!nativeLoaded) {
            val message = "TDLib native libraries could not be loaded on this device."
            _authStep.value = AuthStep.Failure(message)
            log(message, LogLevel.ERROR)
            return
        }
        _authStep.value = AuthStep.Starting
        log("Starting TDLib client")
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        runCatching {
            client = Client.create(
                this,
                Client.ExceptionHandler { error -> log("TDLib update error: ${error.message}", LogLevel.ERROR) },
                Client.ExceptionHandler { error -> log("TDLib request error: ${error.message}", LogLevel.ERROR) },
            )
        }.onFailure { error ->
            val message = "Could not start TDLib: ${error.message}"
            _authStep.value = AuthStep.Failure(message)
            log(message, LogLevel.ERROR)
        }
    }

    fun retry() {
        val current = client
        if (current == null) {
            start()
            return
        }
        _authStep.value = AuthStep.Starting
        scope.launch {
            runCatching { request<TdApi.Ok>(TdApi.Close()) }
                .onFailure {
                    _authStep.value = AuthStep.Failure(friendlyError(it))
                    log("Could not restart TDLib: ${it.message}", LogLevel.ERROR)
                }
        }
    }

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        _logs.update { current ->
            (listOf(entry) + current).take(MAX_LOGS)
        }
    }

    // ---------------------------------------------------------------------
    // TDLib updates
    // ---------------------------------------------------------------------

    override fun onResult(result: TdApi.Object) {
        try {
            when (result.constructor) {
                TdApi.UpdateAuthorizationState.CONSTRUCTOR -> {
                    handleAuthorizationState((result as TdApi.UpdateAuthorizationState).authorizationState)
                }

                TdApi.UpdateConnectionState.CONSTRUCTOR -> {
                    _connection.value = when ((result as TdApi.UpdateConnectionState).state) {
                        is TdApi.ConnectionStateWaitingForNetwork -> ConnectionStatus.WAITING_FOR_NETWORK
                        is TdApi.ConnectionStateUpdating -> ConnectionStatus.UPDATING
                        is TdApi.ConnectionStateReady -> ConnectionStatus.UP_TO_DATE
                        else -> ConnectionStatus.CONNECTING
                    }
                }

                TdApi.UpdateFile.CONSTRUCTOR -> handleFileUpdate((result as TdApi.UpdateFile).file)
                TdApi.UpdateDeleteMessages.CONSTRUCTOR -> invalidateSnapshot()
                TdApi.UpdateMessageContent.CONSTRUCTOR -> invalidateSnapshot()
                TdApi.UpdateMessageSendSucceeded.CONSTRUCTOR -> {
                    val update = result as TdApi.UpdateMessageSendSucceeded
                    dispatchSendOutcome(update.oldMessageId, SendOutcome.Sent(update.message))
                }

                TdApi.UpdateMessageSendFailed.CONSTRUCTOR -> {
                    val update = result as TdApi.UpdateMessageSendFailed
                    dispatchSendOutcome(
                        update.oldMessageId,
                        SendOutcome.Failed(update.error.code, update.error.message),
                    )
                }

                TdApi.UpdateTermsOfService.CONSTRUCTOR -> {
                    pendingTermsId = (result as TdApi.UpdateTermsOfService).termsOfServiceId
                }

                else -> Unit
            }
        } catch (error: Throwable) {
            log("Failed to process a TDLib update: ${error.message}", LogLevel.ERROR)
        }
    }

    private fun handleAuthorizationState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                readyInitialized = false
                _authStep.value = AuthStep.Starting
                scope.launch { configureTdlib() }
            }

            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                readyInitialized = false
                _authStep.value = AuthStep.PhoneNumber
            }

            is TdApi.AuthorizationStateWaitCode -> {
                val info = state.codeInfo
                val (hint, length) = describeCode(info)
                _authStep.value = AuthStep.Code(
                    hint = hint,
                    length = length,
                    timeoutSeconds = info?.timeout ?: 0,
                )
            }

            is TdApi.AuthorizationStateWaitPassword -> {
                _authStep.value = AuthStep.Password(
                    hint = state.passwordHint.takeIf { it.isNotBlank() },
                )
            }

            is TdApi.AuthorizationStateWaitEmailAddress -> {
                _authStep.value = AuthStep.EmailAddress(state.allowAppleId, state.allowGoogleId)
            }

            is TdApi.AuthorizationStateWaitEmailCode -> {
                val info = state.codeInfo
                _authStep.value = AuthStep.EmailCode(
                    hint = "Code sent to ${info?.emailAddressPattern ?: "your email"}",
                    length = info?.length?.takeIf { it > 0 },
                )
            }

            is TdApi.AuthorizationStateWaitRegistration -> {
                _authStep.value = AuthStep.Registration(state.termsOfService?.text?.text)
            }

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                _authStep.value = AuthStep.OtherDevice(state.link)
            }

            is TdApi.AuthorizationStateReady -> {
                _authStep.value = AuthStep.Ready
                _connection.value = ConnectionStatus.UP_TO_DATE
                if (!readyInitialized) {
                    readyInitialized = true
                    log("Telegram authorization complete")
                    scope.launch { initializeAccount() }
                }
            }

            is TdApi.AuthorizationStateLoggingOut -> {
                _authStep.value = AuthStep.SigningOut
                log("Signing out of Telegram")
            }

            is TdApi.AuthorizationStateClosing -> _authStep.value = AuthStep.SigningOut

            is TdApi.AuthorizationStateClosed -> {
                log("TDLib client closed; restarting for the next sign-in")
                client = null
                readyInitialized = false
                cachedSnapshot = null
                _driveChat.value = null
                _profile.value = null
                _connection.value = ConnectionStatus.CONNECTING
                _filePaths.value = emptyMap()
                scope.launch {
                    delay(800)
                    start()
                }
            }

            else -> log("Unhandled authorization state: ${state.javaClass.simpleName}", LogLevel.WARN)
        }
    }

    private suspend fun configureTdlib() {
        val base = File(context.filesDir, "tdlib")
        val database = File(base, "db").apply { mkdirs() }
        val files = File(base, "files").apply { mkdirs() }
        try {
            request<TdApi.Ok>(
                TdApi.SetTdlibParameters(
                    false,
                    database.absolutePath,
                    files.absolutePath,
                    null,
                    true,
                    true,
                    true,
                    false,
                    BuildConfig.TELEGRAM_API_ID,
                    BuildConfig.TELEGRAM_API_HASH,
                    Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en",
                    Build.MODEL.ifBlank { "Android" },
                    "Android ${Build.VERSION.RELEASE}",
                     "TD Flew ${BuildConfig.VERSION_NAME} - TDLib ${BuildConfig.TDLIB_VERSION}",
                ),
            )
            log("TDLib parameters configured")
        } catch (error: Throwable) {
            val message = if (error is TdException && error.message.orEmpty().contains("API_ID")) {
                "Telegram rejected the API credentials. Set TELEGRAM_API_ID and TELEGRAM_API_HASH secrets."
            } else {
                "Could not initialize TDLib: ${friendlyError(error)}"
            }
            _authStep.value = AuthStep.Failure(message)
            log(message, LogLevel.ERROR)
        }
    }

    private suspend fun initializeAccount() {
        try {
            val me = request<TdApi.User>(TdApi.GetMe())
            myUserId = me.id
            val username = me.usernames?.activeUsernames?.firstOrNull()
            val name = listOf(me.firstName, me.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { username?.let { "@$it" } ?: "Telegram user" }
            _profile.value = UserProfile(
                id = me.id,
                name = name,
                username = username,
                photoFileId = me.profilePhoto?.big?.id,
            )
            resolveDrive()
        } catch (error: Throwable) {
            log("Could not load the Telegram profile: ${friendlyError(error)}", LogLevel.ERROR)
        }
    }

    private suspend fun resolveDrive() {
        val storedId = settings.driveChatId()
        val candidates = buildList {
            if (storedId != null) add(storedId)
            if (myUserId != 0L) add(myUserId)
        }.distinct()

        for (chatId in candidates) {
            val chat = runCatching { request<TdApi.Chat>(TdApi.GetChat(chatId)) }.getOrNull()
            if (chat != null) {
                applyDrive(chat)
                return
            }
        }
        _driveChat.value = null
        log("No Telegram drive selected. Open Menu to choose one.", LogLevel.WARN)
    }

    private fun applyDrive(chat: TdApi.Chat): DriveChat {
        val drive = DriveChat(
            id = chat.id,
            title = chat.title.ifBlank {
                if (chat.id == myUserId) "Saved Messages" else "Telegram drive"
            },
            isSavedMessages = chat.id == myUserId,
        )
        _driveChat.value = drive
        settings.saveDriveChat(drive.id, drive.title)
        invalidateSnapshot()
        log("Drive set to ${drive.title}")
        return drive
    }

    private fun describeCode(info: TdApi.AuthenticationCodeInfo?): Pair<String, Int?> {
        val destination = info?.phoneNumber?.takeIf { it.isNotBlank() } ?: "your Telegram account"
        val type = info?.type
        val length = when (type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> type.length
            is TdApi.AuthenticationCodeTypeSms -> type.length
            is TdApi.AuthenticationCodeTypeCall -> type.length
            is TdApi.AuthenticationCodeTypeFragment -> type.length
            else -> null
        }?.takeIf { it > 0 }

        val channel = when (type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> "Telegram"
            is TdApi.AuthenticationCodeTypeSms -> "SMS"
            is TdApi.AuthenticationCodeTypeCall -> "phone call"
            is TdApi.AuthenticationCodeTypeFlashCall -> "flash call"
            is TdApi.AuthenticationCodeTypeMissedCall -> "missed call"
            is TdApi.AuthenticationCodeTypeFragment -> "Fragment"
            else -> "Telegram"
        }
        return "Code sent via $channel to $destination" to length
    }

    // ---------------------------------------------------------------------
    // Authentication actions
    // ---------------------------------------------------------------------

    suspend fun submitPhone(phone: String) {
        val cleaned = phone.trim().replace(" ", "").replace("-", "")
        if (!cleaned.startsWith("+") || cleaned.length < 6) {
            throw IllegalArgumentException("Enter the phone number with its country code, for example +1 555 123 4567.")
        }
        request<TdApi.Ok>(TdApi.SetAuthenticationPhoneNumber(cleaned, null))
        log("Phone number submitted")
    }

    suspend fun submitCode(code: String) {
        if (code.isBlank()) throw IllegalArgumentException("Enter the login code.")
        request<TdApi.Ok>(TdApi.CheckAuthenticationCode(code.trim()))
    }

    suspend fun resendCode() {
        request<TdApi.Ok>(TdApi.ResendAuthenticationCode(null))
        log("Requested a new login code")
    }

    suspend fun submitPassword(password: String) {
        if (password.isBlank()) throw IllegalArgumentException("Enter your two-step verification password.")
        request<TdApi.Ok>(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun submitEmailAddress(email: String) {
        if (!email.contains('@')) throw IllegalArgumentException("Enter a valid email address.")
        request<TdApi.Ok>(TdApi.SetAuthenticationEmailAddress(email.trim()))
    }

    suspend fun submitEmailCode(code: String) {
        if (code.isBlank()) throw IllegalArgumentException("Enter the email code.")
        request<TdApi.Ok>(
            TdApi.CheckAuthenticationEmailCode(
                TdApi.EmailAddressAuthenticationCode(code.trim()),
            ),
        )
    }

    suspend fun submitRegistration(firstName: String, lastName: String) {
        if (firstName.isBlank()) throw IllegalArgumentException("Enter your first name.")
        pendingTermsId?.let { id ->
            runCatching { request<TdApi.Ok>(TdApi.AcceptTermsOfService(id)) }
        }
        request<TdApi.Ok>(TdApi.RegisterUser(firstName.trim(), lastName.trim(), false))
        log("Registration submitted")
    }

    suspend fun logout() {
        log("Logging out of Telegram")
        settings.clearDriveChat()
        cachedSnapshot = null
        _driveChat.value = null
        _filePaths.value = emptyMap()
        transfers.clearAll()
        request<TdApi.Ok>(TdApi.LogOut())
    }

    // ---------------------------------------------------------------------
    // Drive selection
    // ---------------------------------------------------------------------

    suspend fun setDriveChat(chatId: Long): DriveChat {
        if (chatId == 0L) throw IllegalArgumentException("Enter a valid chat ID.")
        val chat = request<TdApi.Chat>(TdApi.GetChat(chatId))
        return applyDrive(chat)
    }

    suspend fun setDriveByInput(rawInput: String): DriveChat {
        val chat = resolveDriveInput(rawInput)
        return applyDrive(chat)
    }

    suspend fun setSavedMessagesDrive(): DriveChat {
        val me = request<TdApi.User>(TdApi.GetMe())
        myUserId = me.id
        val chat = request<TdApi.Chat>(TdApi.GetChat(me.id))
        return applyDrive(chat)
    }

    private suspend fun resolveDriveInput(rawInput: String): TdApi.Chat {
        val input = rawInput.trim()
        if (input.isBlank()) throw IllegalArgumentException("Enter a chat ID, @username, or t.me link.")
        input.toLongOrNull()?.let { id ->
            return request(TdApi.GetChat(id))
        }

        val normalized = input
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        val withoutQuery = normalized.substringBefore('?').substringBefore('#')

        if (withoutQuery.startsWith("t.me/") || withoutQuery.startsWith("telegram.me/")) {
            val path = withoutQuery.substringAfter('/')
            if (path.startsWith("+") || path.startsWith("joinchat/")) {
                val invite = "https://t.me/$path"
                val info = request<TdApi.ChatInviteLinkInfo>(TdApi.CheckChatInviteLink(invite))
                if (info.chatId != 0L) return request(TdApi.GetChat(info.chatId))
                return request(TdApi.JoinChatByInviteLink(invite))
            }
            if (path.startsWith("c/")) {
                val internalId = path.removePrefix("c/").substringBefore('/')
                val chatId = internalId.toLongOrNull()
                    ?: throw IllegalArgumentException("That private chat link is not valid.")
                return request(TdApi.GetChat(-1_000_000_000_000L - chatId))
            }
            val username = path.substringBefore('/').removePrefix("@")
            if (username.isBlank()) throw IllegalArgumentException("That Telegram link is not valid.")
            return request(TdApi.SearchPublicChat(username))
        }

        val username = input.removePrefix("@").trim()
        return runCatching {
            request<TdApi.Chat>(TdApi.SearchPublicChat(username))
        }.getOrElse { error ->
            throw IllegalArgumentException("No public chat found for @$username. ${friendlyError(error)}")
        }
    }

    suspend fun searchChats(query: String): List<DriveChat> {
        val chatIds = LinkedHashSet<Long>()
        if (query.isBlank()) {
            runCatching { request<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), 100)) }
            runCatching { request<TdApi.Chats>(TdApi.GetChats(TdApi.ChatListMain(), 50)) }
                .getOrNull()
                ?.chatIds
                ?.forEach { chatIds.add(it) }
        } else {
            runCatching { request<TdApi.Chats>(TdApi.SearchChats(query, 30)) }
                .getOrNull()
                ?.chatIds
                ?.forEach { chatIds.add(it) }
            runCatching { request<TdApi.Chats>(TdApi.SearchChatsOnServer(query, 30)) }
                .getOrNull()
                ?.chatIds
                ?.forEach { chatIds.add(it) }
        }

        return chatIds.take(40).mapNotNull { id ->
            runCatching { request<TdApi.Chat>(TdApi.GetChat(id)) }.getOrNull()
        }.filter { it.id != 0L }
            .distinctBy { it.id }
            .map { chat ->
                DriveChat(
                    id = chat.id,
                    title = chat.title.ifBlank { "Chat ${chat.id}" },
                    isSavedMessages = chat.id == myUserId,
                )
            }
    }

    // ---------------------------------------------------------------------
    // Drive history and items
    // ---------------------------------------------------------------------

    suspend fun snapshot(chatId: Long, force: Boolean = false): DriveSnapshot =
        snapshotMutex.withLock {
            val cached = cachedSnapshot
            if (!force && cached?.first == chatId) return cached.second

            val startedAt = System.currentTimeMillis()
            val folders = mutableListOf<DriveItem>()
            val files = mutableListOf<DriveItem>()
            val media = mutableListOf<DriveItem>()
            var scanned = 0
            var truncated = false
            var fromMessageId = 0L
            val seen = HashSet<Long>()

            while (scanned < MAX_HISTORY) {
                val page = request<TdApi.Messages>(
                    TdApi.GetChatHistory(
                        chatId,
                        fromMessageId,
                        0,
                        PAGE_SIZE,
                        false,
                    ),
                )
                if (page.messages.isEmpty()) break

                var added = 0
                for (message in page.messages) {
                    if (!seen.add(message.id)) continue
                    added += 1
                    scanned += 1
                    if (!message.isOutgoing) continue
                    when (val item = parseMessage(message)) {
                        null -> Unit
                        else -> when (item.kind) {
                            DriveItemKind.FOLDER -> folders += item
                            DriveItemKind.PHOTO, DriveItemKind.VIDEO -> media += item
                            DriveItemKind.FILE -> files += item
                        }
                    }
                }

                val oldestId = page.messages.minOfOrNull { it.id } ?: break
                if (added == 0) break
                fromMessageId = oldestId
            }

            if (scanned >= MAX_HISTORY) {
                truncated = true
                log("Drive history was capped at $MAX_HISTORY messages", LogLevel.WARN)
            }

            val snapshot = DriveSnapshot(
                folders = folders.distinctBy { it.id }.sortedByDescending { it.timestamp },
                files = collapseChunks(files).sortedByDescending { it.timestamp },
                media = collapseChunks(media).sortedByDescending { it.timestamp },
                scannedMessages = scanned,
                truncated = truncated,
            )
            cachedSnapshot = chatId to snapshot
            val elapsed = System.currentTimeMillis() - startedAt
            log(
                "Indexed ${snapshot.folders.size} folders, ${snapshot.files.size} files, " +
                    "${snapshot.media.size} media items in ${elapsed}ms",
            )
            snapshot
        }

    fun invalidateSnapshot() {
        cachedSnapshot = null
    }

    private fun collapseChunks(items: List<DriveItem>): List<DriveItem> {
        val result = mutableListOf<DriveItem>()
        items.filter { it.chunkGroup == null }.forEach { item ->
            if (result.none { it.id == item.id }) result += item
        }
        items.filter { it.chunkGroup != null }
            .groupBy { it.chunkGroup }
            .values
            .forEach { parts ->
                val ordered = parts.sortedBy { it.chunkIndex }
                val first = ordered.firstOrNull() ?: return@forEach
                result += first.copy(
                    size = ordered.sumOf { it.size },
                    chunkParts = ordered.flatMap { it.chunkParts },
                )
            }
        return result.distinctBy { it.id }
    }

    private fun parseMessage(message: TdApi.Message): DriveItem? {
        val timestamp = message.date.toLong() * 1000L
        return when (val content = message.content) {
            is TdApi.MessageText -> {
                val path = DriveMetadata.parseFolderPath(content.text.text) ?: return null
                if (path == "/") return null
                DriveItem(
                    id = "d${message.id}",
                    messageId = message.id,
                    path = path,
                    parentPath = DriveMetadata.parentOf(path),
                    name = DriveMetadata.nameOf(path),
                    kind = DriveItemKind.FOLDER,
                    size = 0L,
                    timestamp = timestamp,
                    mimeType = "application/x-directory",
                )
            }

            is TdApi.MessageDocument -> {
                val document = content.document
                val chunk = DriveMetadata.parseChunk(content.caption.text)
                val markerPath = chunk?.path ?: DriveMetadata.parseFilePath(content.caption.text)
                val tags = DriveMetadata.parseTags(content.caption.text)
                val fallbackName = DriveMetadata.sanitizeName(document.fileName)
                    ?: "File_${message.id}"
                val path = markerPath ?: "/$fallbackName"
                val file = document.document
                val mimeType = document.mimeType.ifBlank {
                    MediaTools.guessMimeType(fallbackName)
                }
                val kind = when {
                    mimeType.startsWith("image/") -> DriveItemKind.PHOTO
                    mimeType.startsWith("video/") -> DriveItemKind.VIDEO
                    else -> DriveItemKind.FILE
                }
                DriveItem(
                    id = "m${message.id}",
                    messageId = message.id,
                    path = path,
                    parentPath = DriveMetadata.parentOf(path),
                    name = DriveMetadata.nameOf(path).ifBlank { fallbackName },
                    kind = kind,
                    size = file.size.takeIf { it > 0L } ?: file.expectedSize.coerceAtLeast(0L),
                    timestamp = galleryTimestampFromName(fallbackName, timestamp),
                    mimeType = mimeType,
                    fileId = file.id,
                    thumbnailFileId = document.thumbnail?.file?.id,
                    isManaged = markerPath != null,
                    chunkGroup = chunk?.group,
                    chunkIndex = chunk?.index ?: 0,
                    chunkTotal = chunk?.total ?: 0,
                    chunkParts = if (chunk != null) {
                        listOf(DriveChunkPart(message.id, file.id, file.size, chunk.index))
                    } else {
                        emptyList()
                    },
                    tags = tags,
                )
            }

            is TdApi.MessagePhoto -> {
                val sizes = content.photo.sizes
                val full = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: return null
                val preview = sizes.minByOrNull { it.width.toLong() * it.height.toLong() } ?: full
                val markerPath = DriveMetadata.parseFilePath(content.caption.text)
                val tags = DriveMetadata.parseTags(content.caption.text)
                val fallbackName = "Photo_${message.id}.jpg"
                val path = markerPath ?: "/$fallbackName"
                DriveItem(
                    id = "m${message.id}",
                    messageId = message.id,
                    path = path,
                    parentPath = DriveMetadata.parentOf(path),
                    name = DriveMetadata.nameOf(path).ifBlank { fallbackName },
                    kind = DriveItemKind.PHOTO,
                    size = full.photo.size.takeIf { it > 0L } ?: full.photo.expectedSize.coerceAtLeast(0L),
                    timestamp = galleryTimestampFromName(fallbackName, timestamp),
                    mimeType = "image/jpeg",
                    fileId = full.photo.id,
                    thumbnailFileId = preview.photo.id,
                    isManaged = markerPath != null,
                    tags = tags,
                )
            }

            is TdApi.MessageVideo -> {
                val video = content.video.video
                val markerPath = DriveMetadata.parseFilePath(content.caption.text)
                val tags = DriveMetadata.parseTags(content.caption.text)
                val fallbackName = DriveMetadata.sanitizeName(content.video.fileName)
                    ?: "Video_${message.id}.mp4"
                val path = markerPath ?: "/$fallbackName"
                DriveItem(
                    id = "m${message.id}",
                    messageId = message.id,
                    path = path,
                    parentPath = DriveMetadata.parentOf(path),
                    name = DriveMetadata.nameOf(path).ifBlank { fallbackName },
                    kind = DriveItemKind.VIDEO,
                    size = video.size.takeIf { it > 0L } ?: video.expectedSize.coerceAtLeast(0L),
                    timestamp = galleryTimestampFromName(fallbackName, timestamp),
                    mimeType = content.video.mimeType.ifBlank {
                        MediaTools.guessMimeType(fallbackName, "video/mp4")
                    },
                    fileId = video.id,
                    thumbnailFileId = content.video.thumbnail?.file?.id,
                    durationSeconds = content.video.duration.coerceAtLeast(0),
                    isManaged = markerPath != null,
                    tags = tags,
                )
            }

            else -> null
        }
    }

    suspend fun createFolder(parentPath: String, rawName: String): DriveItem {
        val drive = _driveChat.value ?: throw IllegalStateException("Choose a Telegram drive first.")
        val parent = DriveMetadata.normalizeFolderPath(parentPath)
            ?: throw IllegalArgumentException("That folder path is not valid.")
        val name = DriveMetadata.sanitizeFolderName(rawName)
            ?: throw IllegalArgumentException("Enter a folder name.")
        val path = DriveMetadata.childPath(parent, name)
            ?: throw IllegalArgumentException("That folder name is not valid.")
        val existing = snapshot(drive.id).folders.any { it.path == path }
        if (existing) throw IllegalArgumentException("A folder named \"$name\" already exists here.")

        val marker = DriveMetadata.encodeFolder(path)
        val message = request<TdApi.Message>(
            TdApi.SendMessage(
                drive.id,
                null,
                null,
                null,
                null,
                TdApi.InputMessageText(
                    TdApi.FormattedText(marker, emptyArray()),
                    null,
                    false,
                ),
            ),
        )
        val sent = awaitMessageSent(message)
        invalidateSnapshot()
        log("Created folder $path")
        return parseMessage(sent) ?: DriveItem(
            id = "d${sent.id}",
            messageId = sent.id,
            path = path,
            parentPath = parent,
            name = name,
            kind = DriveItemKind.FOLDER,
            size = 0L,
            timestamp = sent.date.toLong() * 1000L,
            mimeType = "application/x-directory",
        )
    }

    suspend fun deleteItem(item: DriveItem): Int {
        val drive = _driveChat.value ?: throw IllegalStateException("Choose a Telegram drive first.")
        val current = snapshot(drive.id)
        val targets = if (item.isFolder) {
            current.allManagedItems.filter {
                it.path == item.path || it.path.startsWith("${item.path}/")
            }
        } else {
            listOf(item)
        }
        val ids = targets.flatMap { item ->
            if (item.chunkParts.isEmpty()) listOf(item.messageId)
            else item.chunkParts.map { it.messageId }
        }.distinct()
        if (ids.isEmpty()) return 0

        ids.chunked(100).forEach { chunk ->
            request<TdApi.Ok>(
                TdApi.DeleteMessages(drive.id, chunk.toLongArray(), true),
            )
        }
        invalidateSnapshot()
        log("Deleted ${ids.size} item(s) from ${drive.title}")
        return ids.size
    }

    // ---------------------------------------------------------------------
    // Uploads
    // ---------------------------------------------------------------------

    private data class StagedUpload(
        val file: File,
        val name: String,
        val mimeType: String,
        val size: Long,
    )

    suspend fun upload(
        uri: Uri,
        folderPath: String,
        asMedia: Boolean,
        preferredName: String? = null,
        preferredMimeType: String? = null,
        tags: Collection<String> = emptyList(),
    ): Long {
        val drive = _driveChat.value ?: throw IllegalStateException("Choose a Telegram drive first.")
        val parent = DriveMetadata.normalizeFolderPath(folderPath)
            ?: throw IllegalArgumentException("That folder path is not valid.")

        val staged = withContext(Dispatchers.IO) {
            stageUpload(uri, preferredName, preferredMimeType)
        }
        val paths = runCatching { snapshot(drive.id) }
            .getOrDefault(DriveSnapshot.Empty)
            .allManagedItems
            .mapTo(HashSet()) { it.path }
        val path = uniqueFilePath(parent, staged.name, paths)
        val transferId = transfers.addUpload(staged.name, staged.mimeType, staged.size)
        return try {
            val group = if (staged.size > MAX_PART_BYTES) UUID.randomUUID().toString().replace("-", "") else null
            val parts = splitForUpload(staged)
            val sentMessages = parts.mapIndexed { index, part ->
                val marker = if (group == null) {
                    DriveMetadata.withTags(DriveMetadata.encodeFile(path), tags)
                } else {
                    DriveMetadata.withTags(DriveMetadata.encodeChunk(path, group, index, parts.size), tags)
                }
                val sent = sendStaged(
                    drive.id,
                    part,
                    asMedia = group == null && asMedia,
                    marker = marker,
                    transferId = transferId,
                )
                if (part.file != staged.file) part.file.delete()
                sent
            }
            staged.file.delete()
            transfers.completeUpload(transferId, primaryFileId(sentMessages.last()))
            invalidateSnapshot()
            log(
                "Uploaded ${staged.name} (${formatSize(staged.size)}) to $path" +
                    if (group != null) " in ${parts.size} parts" else if (asMedia) " as media" else "",
            )
            sentMessages.first().id
        } catch (error: Throwable) {
            val message = friendlyError(error)
            transfers.failUpload(transferId, message)
            log("Upload failed for ${staged.name}: $message", LogLevel.ERROR)
            throw error
        }
    }

    private suspend fun sendStaged(
        chatId: Long,
        staged: StagedUpload,
        asMedia: Boolean,
        marker: String,
        transferId: String,
    ): TdApi.Message {
        uploadsByPath[staged.file.absolutePath] = transferId
        return try {
            val content = buildMessageContent(
                staged,
                asMedia,
                TdApi.FormattedText(marker, emptyArray()),
            )
            val message = request<TdApi.Message>(
                TdApi.SendMessage(chatId, null, null, null, null, content),
            )
            awaitMessageSent(message)
        } finally {
            uploadsByPath.remove(staged.file.absolutePath)
        }
    }

    private suspend fun splitForUpload(staged: StagedUpload): List<StagedUpload> {
        if (staged.size <= MAX_PART_BYTES) return listOf(staged)
        return withContext(Dispatchers.IO) {
            val parts = mutableListOf<StagedUpload>()
            FileInputStream(staged.file).use { input ->
                var index = 0
                val buffer = ByteArray(1024 * 1024)
                var remaining = staged.size
                while (remaining > 0L) {
                    val partFile = File(staged.file.parentFile, "${staged.file.name}.part$index")
                    FileOutputStream(partFile).use { output ->
                        var written = 0L
                        while (written < MAX_PART_BYTES && remaining > 0L) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_PART_BYTES - written).toInt())
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            remaining -= read
                        }
                    }
                    if (partFile.length() == 0L) {
                        partFile.delete()
                        break
                    }
                    parts += staged.copy(file = partFile, size = partFile.length())
                    index += 1
                }
            }
            parts
        }
    }

    /**
     * Waits until TDLib confirms that [message] was actually delivered.
     * SendMessage returns a temporary message whose id is replaced by
     * UpdateMessageSendSucceeded, so the update is used instead of polling.
     */
    private suspend fun awaitMessageSent(message: TdApi.Message): TdApi.Message {
        when (val state = message.sendingState) {
            null -> return message
            is TdApi.MessageSendingStateFailed ->
                throw TdException(state.error.code, state.error.message)

            is TdApi.MessageSendingStatePending -> Unit
            else -> return message
        }

        val waiter = CompletableDeferred<TdApi.Message>()
        val early = synchronized(sendLock) {
            val outcome = recentSendOutcomes.remove(message.id)
            if (outcome == null) {
                sendWaiters[message.id] = waiter
            }
            outcome
        }
        if (early != null) {
            return when (early) {
                is SendOutcome.Sent -> early.message
                is SendOutcome.Failed -> throw TdException(early.code, early.message)
            }
        }

        return try {
            withTimeoutOrNull(SEND_TIMEOUT_MS) { waiter.await() }
                ?: throw IllegalStateException(
                    "Telegram did not confirm the upload in time. Check your connection and try again.",
                )
        } finally {
            synchronized(sendLock) {
                if (sendWaiters[message.id] === waiter) {
                    sendWaiters.remove(message.id)
                }
            }
        }
    }

    private fun dispatchSendOutcome(oldMessageId: Long, outcome: SendOutcome) {
        synchronized(sendLock) {
            val waiter = sendWaiters.remove(oldMessageId)
            if (waiter != null) {
                when (outcome) {
                    is SendOutcome.Sent -> waiter.complete(outcome.message)
                    is SendOutcome.Failed ->
                        waiter.completeExceptionally(TdException(outcome.code, outcome.message))
                }
                return
            }

            // The update can arrive before the SendMessage result is resumed.
            // Keep a short history so awaitMessageSent can still observe it.
            recentSendOutcomes[oldMessageId] = outcome
            while (recentSendOutcomes.size > MAX_RECENT_SEND_OUTCOMES) {
                val oldest = recentSendOutcomes.keys.firstOrNull() ?: break
                recentSendOutcomes.remove(oldest)
            }
        }
    }

    private suspend fun stageUpload(
        uri: Uri,
        preferredName: String?,
        preferredMimeType: String?,
    ): StagedUpload = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName: String? = preferredName
        var size = -1L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (displayName.isNullOrBlank() && nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        val fallbackName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
            ?: "Upload_${System.currentTimeMillis()}"
        val safeName = DriveMetadata.sanitizeName(displayName ?: fallbackName) ?: "Upload"
        val mime = preferredMimeType
            ?.takeIf { it.isNotBlank() }
            ?: resolver.getType(uri)?.takeIf { it.isNotBlank() }
            ?: MediaTools.guessMimeType(safeName)

        val directory = File(context.cacheDir, "uploads").apply { mkdirs() }
        val destination = File(directory, "${System.currentTimeMillis()}_$safeName")
        try {
            resolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            } ?: error("Could not open the selected file.")
        } catch (error: Throwable) {
            destination.delete()
            throw IllegalStateException("Could not read the selected file: ${error.message}", error)
        }

        StagedUpload(
            file = destination,
            name = safeName,
            mimeType = mime,
            size = destination.length().takeIf { it > 0L } ?: size.coerceAtLeast(0L),
        )
    }

    private fun buildMessageContent(
        staged: StagedUpload,
        asMedia: Boolean,
        caption: TdApi.FormattedText,
    ): TdApi.InputMessageContent {
        val info = runCatching {
            MediaTools.inspect(staged.file, staged.mimeType, context.cacheDir)
        }.getOrDefault(MediaInfo(0, 0, 0, null))

        val thumbnail = if (info.thumbnail != null && info.width > 0 && info.height > 0) {
            val (thumbWidth, thumbHeight) = thumbnailDimensions(info.width, info.height)
            TdApi.InputThumbnail(
                TdApi.InputFileLocal(info.thumbnail.absolutePath),
                thumbWidth,
                thumbHeight,
            )
        } else {
            null
        }

        val isPhoto = asMedia &&
            staged.mimeType.lowercase() in setOf("image/jpeg", "image/png", "image/webp") &&
            info.width > 0 && info.height > 0
        val isVideo = asMedia &&
            staged.mimeType.startsWith("video/") &&
            info.width > 0 && info.height > 0

        return when {
            isPhoto -> TdApi.InputMessagePhoto(
                TdApi.InputFileLocal(staged.file.absolutePath),
                thumbnail,
                null,
                intArrayOf(),
                info.width,
                info.height,
                caption,
                false,
                null,
                false,
            )

            isVideo -> TdApi.InputMessageVideo(
                TdApi.InputFileLocal(staged.file.absolutePath),
                thumbnail,
                null,
                0,
                intArrayOf(),
                info.durationSeconds,
                info.width,
                info.height,
                true,
                caption,
                false,
                null,
                false,
            )

            else -> TdApi.InputMessageDocument(
                TdApi.InputFileLocal(staged.file.absolutePath),
                thumbnail,
                true,
                caption,
            )
        }
    }

    private fun thumbnailDimensions(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= 320) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val ratio = 320f / longest.toFloat()
        return (width * ratio).toInt().coerceAtLeast(1) to
            (height * ratio).toInt().coerceAtLeast(1)
    }

    private fun uniqueFilePath(parent: String, desiredName: String, used: Set<String>): String {
        val sanitized = DriveMetadata.sanitizeName(desiredName) ?: "File"
        val direct = DriveMetadata.childPath(parent, sanitized)
            ?: throw IllegalArgumentException("The file name is too long for Telegram captions.")
        if (direct !in used) return direct

        val dot = sanitized.lastIndexOf('.')
        val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val extension = if (dot > 0) sanitized.substring(dot) else ""
        for (index in 1..999) {
            val candidateName = "$base ($index)$extension"
            val candidate = DriveMetadata.childPath(parent, candidateName) ?: continue
            if (candidate !in used) return candidate
        }
        throw IllegalArgumentException("Could not find a free name for this file.")
    }

    private fun primaryFileId(message: TdApi.Message): Int {
        return when (val content = message.content) {
            is TdApi.MessageDocument -> content.document.document.id
            is TdApi.MessagePhoto -> content.photo.sizes
                .maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?.photo?.id ?: 0

            is TdApi.MessageVideo -> content.video.video.id
            else -> 0
        }
    }

    // ---------------------------------------------------------------------
    // Downloads and previews
    // ---------------------------------------------------------------------

    suspend fun download(item: DriveItem): SavedFile {
        val drive = _driveChat.value ?: throw IllegalStateException("Choose a Telegram drive first.")
        val fileId = item.fileId ?: throw IllegalArgumentException("This item has no downloadable file.")
        transfers.addDownload(
            fileId = fileId,
            messageId = item.messageId,
            chatId = drive.id,
            name = item.name,
            mimeType = item.mimeType,
            bytesTotal = item.size,
        )
        return try {
            val saved = if (item.chunkParts.isEmpty()) {
                val file = fetchFile(fileId)
                val path = file.local.path
                if (path.isBlank() || !File(path).exists()) {
                    error("Telegram did not return a local copy of this file.")
                }
                FileSaver.save(context, File(path), item.name, item.mimeType)
            } else {
                val parts = item.chunkParts.sortedBy { it.index }
                require(parts.size == item.chunkTotal) {
                    "Some parts of ${item.name} are missing from Telegram."
                }
                val merged = withContext(Dispatchers.IO) {
                    val output = File(context.cacheDir, "${UUID.randomUUID()}-${item.name}")
                    FileOutputStream(output).use { out ->
                        parts.forEach { part ->
                            val fetched = fetchFile(part.fileId)
                            val source = File(fetched.local.path)
                            require(source.isFile) { "Telegram did not return a local copy of a file part." }
                            FileInputStream(source).use { input -> input.copyTo(out) }
                        }
                    }
                    output
                }
                try {
                    FileSaver.save(context, merged, item.name, item.mimeType)
                } finally {
                    merged.delete()
                }
            }
            transfers.completeByFileId(fileId, saved.uri, saved.displayPath)
            log("Saved ${item.name} to ${saved.displayPath}")
            saved
        } catch (error: Throwable) {
            transfers.failByFileId(fileId, friendlyError(error))
            throw error
        }
    }

    suspend fun open(item: DriveItem) {
        val saved = download(item)
        if (!FileSaver.open(context, saved.uri, item.mimeType)) {
            throw IllegalStateException("No app on this device can open this file type.")
        }
    }

    suspend fun share(item: DriveItem) {
        val saved = download(item)
        if (!FileSaver.share(context, saved.uri, item.mimeType, item.name)) {
            throw IllegalStateException("Could not open the share sheet.")
        }
    }

    fun requestPreviewFile(fileId: Int?, priority: Int = 16) {
        if (fileId == null || fileId == 0) return
        val known = _filePaths.value[fileId]
        if (known != null && File(known).exists()) return
        if (!requestedFiles.add(fileId)) return
        scope.launch {
            try {
                fetchFile(fileId, priority)
            } catch (error: Throwable) {
                if (error !is kotlinx.coroutines.CancellationException) {
                    log("Could not load preview $fileId: ${friendlyError(error)}", LogLevel.WARN)
                }
            } finally {
                requestedFiles.remove(fileId)
            }
        }
    }

    private suspend fun fetchFile(fileId: Int, priority: Int = 1): TdApi.File {
        return withTimeout(FILE_TIMEOUT_MS) {
            val initial = request<TdApi.File>(TdApi.GetFile(fileId))
            if (initial.local.isDownloadingCompleted && initial.local.path.isNotBlank()) {
                return@withTimeout initial
            }

            val waiter = CompletableDeferred<TdApi.File>()
            registerFileWaiter(fileId, waiter)
            try {
                val current = request<TdApi.File>(TdApi.GetFile(fileId))
                if (current.local.isDownloadingCompleted) {
                    completeFileWaiters(current)
                } else {
                    request<TdApi.File>(
                        TdApi.DownloadFile(fileId, priority.coerceIn(1, 32), 0, 0, false),
                    )
                }
                waiter.await()
            } finally {
                removeFileWaiter(fileId, waiter)
            }
        }
    }

    private fun registerFileWaiter(fileId: Int, waiter: CompletableDeferred<TdApi.File>) {
        fileWaiters.compute(fileId) { _, existing ->
            (existing ?: mutableListOf()).apply { add(waiter) }
        }
    }

    private fun removeFileWaiter(fileId: Int, waiter: CompletableDeferred<TdApi.File>) {
        fileWaiters.computeIfPresent(fileId) { _, existing ->
            existing.remove(waiter)
            existing.ifEmpty { null }
        }
    }

    private fun completeFileWaiters(file: TdApi.File) {
        fileWaiters.remove(file.id)?.forEach { waiter ->
            waiter.complete(file)
        }
    }

    private fun handleFileUpdate(file: TdApi.File) {
        if (file.local.path.isNotBlank()) {
            _filePaths.update { paths ->
                val updated = paths + (file.id to file.local.path)
                if (updated.size <= 1_000) updated else updated.entries
                    .toList()
                    .takeLast(750)
                    .associate { it.key to it.value }
            }
            if (file.local.isDownloadingCompleted) completeFileWaiters(file)
        }

        transfers.updateProgressByFileId(
            file.id,
            file.local.downloadedSize.coerceAtLeast(0L),
            file.size.coerceAtLeast(0L),
        )

        val uploadId = uploadsByPath[file.local.path]
        if (uploadId != null) {
            val uploaded = file.remote.uploadedSize.coerceAtLeast(0L)
            val total = file.size.coerceAtLeast(file.remote.uploadedSize)
            transfers.updateProgressById(uploadId, uploaded, total)
        }
    }

    // ---------------------------------------------------------------------
    // Cache and maintenance
    // ---------------------------------------------------------------------

    suspend fun cacheSize(): Long = CacheTools.cacheSize(context)

    suspend fun clearCache(): Long {
        if (transfers.records.value.any { it.status == TransferStatus.RUNNING }) {
            throw IllegalStateException("Wait for active uploads and downloads to finish first.")
        }
        runCatching {
            request<TdApi.StorageStatistics>(
                TdApi.OptimizeStorage(
                    0,
                    0,
                    0,
                    0,
                    emptyArray(),
                    longArrayOf(),
                    longArrayOf(),
                    true,
                    0,
                ),
            )
        }.onFailure {
            log("TDLib cache optimization failed: ${friendlyError(it)}", LogLevel.WARN)
        }
        CacheTools.clearLocalCaches(context)
        _filePaths.value = emptyMap()
        requestedFiles.clear()
        invalidateSnapshot()
        log("Local cache cleared")
        return CacheTools.cacheSize(context)
    }

    // ---------------------------------------------------------------------
    // Request plumbing
    // ---------------------------------------------------------------------

    private suspend fun <T : TdApi.Object> request(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { continuation ->
            val activeClient = client
            if (activeClient == null) {
                continuation.resumeWithException(
                    IllegalStateException("Telegram is still starting. Please wait a moment."),
                )
                return@suspendCancellableCoroutine
            }
            try {
                activeClient.send(function) { result ->
                    if (!continuation.isActive) return@send
                    if (result is TdApi.Error) {
                        continuation.resumeWithException(
                            TdException(result.code, result.message),
                        )
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resume(result as T)
                    }
                }
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index += 1
        }
        return if (index == 0) {
            "${value.toLong()} ${units[index]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }
}

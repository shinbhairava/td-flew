package com.shinbhairava.tdflew.ui.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinbhairava.tdflew.R
import com.shinbhairava.tdflew.engine.AuthStep
import com.shinbhairava.tdflew.engine.TelegramManager
import com.shinbhairava.tdflew.engine.friendlyError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    step: AuthStep,
    manager: TelegramManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(step) {
        busy = false
        error = null
    }

    fun submit(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (throwable: Throwable) {
                error = friendlyError(throwable)
            } finally {
                busy = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                    ),
                ),
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.22f),
                radius = size.minDimension * 0.55f,
                center = Offset(size.width * 0.92f, size.height * 0.04f),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.14f),
                radius = size.minDimension * 0.42f,
                center = Offset(size.width * 0.05f, size.height * 0.94f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                shadowElevation = 6.dp,
                modifier = Modifier.size(104.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.padding(14.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "TD Flew",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "Your Telegram drive, with wings.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 2.dp,
                shadowElevation = 10.dp,
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    // Do not cross-fade authentication forms: the old and new
                    // layouts overlap during the first state change, which can
                    // make labels and words appear doubled on slower devices.
                    when (val target = step) {
                            is AuthStep.Starting -> ConnectingContent("Connecting securely to Telegram…")
                            is AuthStep.SigningOut -> ConnectingContent("Signing out of Telegram…")
                            is AuthStep.Ready -> ConnectingContent("Opening your drive…")

                            is AuthStep.PhoneNumber -> PhoneForm(
                                busy = busy,
                                error = error,
                                onSubmit = { phone -> submit { manager.submitPhone(phone) } },
                            )

                            is AuthStep.Code -> CodeForm(
                                step = target,
                                busy = busy,
                                error = error,
                                onSubmit = { code -> submit { manager.submitCode(code) } },
                                onResend = { submit { manager.resendCode() } },
                            )

                            is AuthStep.Password -> PasswordForm(
                                step = target,
                                busy = busy,
                                error = error,
                                onSubmit = { password -> submit { manager.submitPassword(password) } },
                            )

                            is AuthStep.EmailAddress -> EmailAddressForm(
                                step = target,
                                busy = busy,
                                error = error,
                                onSubmit = { email -> submit { manager.submitEmailAddress(email) } },
                            )

                            is AuthStep.EmailCode -> EmailCodeForm(
                                step = target,
                                busy = busy,
                                error = error,
                                onSubmit = { code -> submit { manager.submitEmailCode(code) } },
                            )

                            is AuthStep.Registration -> RegistrationForm(
                                busy = busy,
                                error = error,
                                onSubmit = { first, last ->
                                    submit { manager.submitRegistration(first, last) }
                                },
                                onOpenTerms = {
                                    openUrl(context, "https://telegram.org/tos")
                                },
                            )

                            is AuthStep.OtherDevice -> OtherDeviceContent(
                                step = target,
                                onOpenTelegram = {
                                    val link = target.link
                                    if (link.startsWith("tg://")) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(link)),
                                            )
                                        }
                                    } else {
                                        openUrl(context, link)
                                    }
                                },
                            )

                            is AuthStep.Failure -> FailureContent(
                                message = target.message,
                                onRetry = { manager.retry() },
                            )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Sessions are handled by TDLib. Your Telegram password is never stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ConnectingContent(label: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FailureContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Telegram didn't connect",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Try again")
        }
    }
}

@Composable
private fun PhoneForm(
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    var phone by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        // TDLib usually reaches PhoneNumber shortly after the activity starts.
        // Request focus after the first layout pass so the first screen is
        // immediately usable instead of requiring an extra tap.
        delay(250)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    FormTitle("Sign in to Telegram", "Use your Telegram account to turn a chat into your private drive.")
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text("Phone number") },
        placeholder = { Text("+1 555 123 4567") },
        leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
    ErrorText(error)
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = { onSubmit(phone) },
        enabled = !busy && phone.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("Continue")
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = "Your phone number is sent only to Telegram. TD Flew does not collect it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CodeForm(
    step: AuthStep.Code,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var seconds by remember(step) { mutableIntStateOf(step.timeoutSeconds.coerceAtLeast(0)) }

    LaunchedEffect(seconds) {
        if (seconds > 0) {
            delay(1000)
            seconds -= 1
        }
    }

    FormTitle("Enter your code", step.hint)
    OutlinedTextField(
        value = code,
        onValueChange = { newValue ->
            code = newValue.filter(Char::isDigit).take(step.length ?: 8)
        },
        label = { Text("Login code") },
        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(
            letterSpacing = 6.sp,
            textAlign = TextAlign.Center,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error)
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = { onSubmit(code) },
        enabled = !busy && code.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("Verify")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(
            onClick = onResend,
            enabled = !busy && seconds == 0,
        ) {
            Text(if (seconds > 0) "Resend in ${seconds}s" else "Resend code")
        }
    }
}

@Composable
private fun PasswordForm(
    step: AuthStep.Password,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    FormTitle(
        "Two-step verification",
        step.hint?.let { "Hint: $it" } ?: "Enter the password for your Telegram account.",
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error)
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = { onSubmit(password) },
        enabled = !busy && password.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("Sign in")
        }
    }
}

@Composable
private fun EmailAddressForm(
    step: AuthStep.EmailAddress,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    FormTitle(
        "Email confirmation",
        if (step.allowAppleId || step.allowGoogleId) {
            "Telegram needs an email address or a linked sign-in provider."
        } else {
            "Telegram needs an email address to confirm your sign-in."
        },
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email address") },
        leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error)
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = { onSubmit(email) },
        enabled = !busy && email.contains('@'),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Text(if (busy) "Sending…" else "Send code")
    }
}

@Composable
private fun EmailCodeForm(
    step: AuthStep.EmailCode,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    FormTitle("Check your email", step.hint)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.filter(Char::isDigit).take(step.length ?: 8) },
        label = { Text("Email code") },
        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(error)
    Spacer(Modifier.height(18.dp))
    Button(
        onClick = { onSubmit(code) },
        enabled = !busy && code.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Text(if (busy) "Verifying…" else "Verify")
    }
}

@Composable
private fun RegistrationForm(
    busy: Boolean,
    error: String?,
    onSubmit: (String, String) -> Unit,
    onOpenTerms: () -> Unit,
) {
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var accepted by rememberSaveable { mutableStateOf(false) }

    FormTitle("Create your Telegram account", "Telegram needs a name before it can finish signing you in.")
    OutlinedTextField(
        value = firstName,
        onValueChange = { firstName = it },
        label = { Text("First name") },
        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = lastName,
        onValueChange = { lastName = it },
        label = { Text("Last name (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = accepted, onCheckedChange = { accepted = it })
        Column {
            Text("I agree to Telegram's Terms of Service.", style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = onOpenTerms,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text("Read the terms", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    ErrorText(error)
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onSubmit(firstName, lastName) },
        enabled = !busy && firstName.isNotBlank() && accepted,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Text(if (busy) "Creating account…" else "Create account")
    }
}

@Composable
private fun OtherDeviceContent(
    step: AuthStep.OtherDevice,
    onOpenTelegram: () -> Unit,
) {
    FormTitle(
        "Confirm on another device",
        "Open Telegram on a device where you are already signed in and approve this login.",
    )
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Waiting for approval…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (step.link.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = step.link,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = onOpenTelegram,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Icon(Icons.Outlined.AlternateEmail, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Open Telegram")
    }
}

@Composable
private fun FormTitle(title: String, subtitle: String) {
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ErrorText(error: String?) {
    if (error.isNullOrBlank()) return
    Spacer(Modifier.height(12.dp))
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

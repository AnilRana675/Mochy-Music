package com.mochyfork.mftelegram.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.mochyfork.mftelegram.AuthInput
import com.mochyfork.mftelegram.AuthState
import com.mochyfork.mftelegram.SyncState
import com.mochyfork.mftelegram.TelegramEngine.Channel
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.ui.component.AppSwitch
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.screens.settings.integrations.IntegrationPageSubtitle
import java.text.DateFormat
import java.util.Date

const val MfChannelLibraryRoute = "integrations_settings/channel_library"

@Composable
fun MfChannelLibraryScreen(
    navController: NavController,
    viewModel: MfChannelLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiId by remember { mutableStateOf(viewModel.storedApiId()?.toString().orEmpty()) }
    var apiHash by remember { mutableStateOf("") }
    var showApiHash by remember { mutableStateOf(false) }
    var pendingKeyAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showChannelPicker by remember { mutableStateOf(false) }

    SubSettingsScaffold(
        title = stringResource(R.string.mf_channel_library),
        onBack = { navController.navigateUp() },
    ) {
        IntegrationPageSubtitle(stringResource(R.string.mf_channel_library_subtitle))
        var editingApiKeys by remember { mutableStateOf(false) }
        LaunchedEffect(state.hasInAppKeys) {
            if (state.hasInAppKeys) editingApiKeys = false
        }
        ApiKeysCard(
            state = state,
            apiId = apiId,
            apiHash = apiHash,
            showApiHash = showApiHash,
            editing = editingApiKeys,
            onApiIdChange = { apiId = it.filter(Char::isDigit) },
            onApiHashChange = { apiHash = it },
            onShowApiHashChange = { showApiHash = !showApiHash },
            onEdit = { editingApiKeys = true },
            onSave = {
                val action = { viewModel.saveApiKeys(apiId, apiHash) }
                if (state.auth == AuthState.Ready) pendingKeyAction = action else action()
            },
            onRemove = {
                val action = { viewModel.removeApiKeys(); apiId = ""; apiHash = "" }
                if (state.auth == AuthState.Ready) pendingKeyAction = action else action()
            },
        )
        Spacer(Modifier.height(12.dp))
        if (!state.available) {
            MfSectionCard {
                Text(stringResource(R.string.mf_feature_unavailable))
            }
            return@SubSettingsScaffold
        }
        MfSectionCard(stringResource(R.string.mf_account)) {
            AccountSection(state, phone, { phone = it }, code, { code = it }, password, { password = it }, viewModel)
        }
        Spacer(Modifier.height(12.dp))
        MfSectionCard(stringResource(R.string.mf_channel)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.mf_enable), fontWeight = FontWeight.SemiBold)
                AppSwitch(
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
            }
            if (state.auth != AuthState.Ready) {
                Text(
                    stringResource(R.string.mf_enable_reason),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (state.enabled) {
                Spacer(Modifier.height(12.dp))
                ChannelSection(state, onOpenPicker = {
                    viewModel.refreshCandidates()
                    showChannelPicker = true
                })
            }
        }
        Spacer(Modifier.height(12.dp))
        MfSectionCard(stringResource(R.string.mf_index_status)) {
            if (state.enabled) IndexSection(state, viewModel)
        }
        Spacer(Modifier.height(12.dp))
        MfSectionCard(stringResource(R.string.mf_about)) {
            Text(stringResource(R.string.mf_telegram_api_note))
        }
    }
    pendingKeyAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingKeyAction = null },
            title = { Text(stringResource(R.string.mf_confirm_key_change_title)) },
            text = { Text(stringResource(R.string.mf_confirm_key_change_body)) },
            confirmButton = {
                MfPrimaryButton(onClick = { pendingKeyAction = null; action() }) {
                    Text(stringResource(R.string.mf_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingKeyAction = null }) {
                    Text(stringResource(R.string.mf_cancel))
                }
            },
        )
    }
    if (showChannelPicker) {
        ChannelPickerSheet(
            state = state,
            onDismiss = { showChannelPicker = false },
            onSelect = {
                viewModel.selectChannel(it)
                showChannelPicker = false
            },
        )
    }
}

@Composable
private fun ApiKeysCard(
    state: MfChannelLibraryState,
    apiId: String,
    apiHash: String,
    showApiHash: Boolean,
    editing: Boolean,
    onApiIdChange: (String) -> Unit,
    onApiHashChange: (String) -> Unit,
    onShowApiHashChange: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    MfSectionCard(stringResource(R.string.mf_api_keys)) {
        if (state.hasInAppKeys && !editing) {
            Text(stringResource(R.string.mf_keys_saved), fontWeight = FontWeight.SemiBold)
            Text("${stringResource(R.string.mf_api_id)}: ${stringResource(R.string.mf_keys_masked)}")
            Text("${stringResource(R.string.mf_api_hash)}: ${stringResource(R.string.mf_keys_masked)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MfTonalButton(onClick = onEdit) { Text(stringResource(R.string.mf_change_keys)) }
                MfRemoveButton(onClick = onRemove) { Text(stringResource(R.string.mf_remove_keys)) }
            }
        } else {
            OutlinedTextField(
                value = apiId,
                onValueChange = onApiIdChange,
                label = { Text(stringResource(R.string.mf_api_id)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = apiHash,
                    onValueChange = onApiHashChange,
                    label = { Text(stringResource(R.string.mf_api_hash)) },
                    modifier = Modifier.weight(1f).clearAndSetSemantics { },
                    visualTransformation = if (showApiHash) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                IconButton(onClick = onShowApiHashChange) {
                    Text(stringResource(if (showApiHash) R.string.mf_hide_api_hash else R.string.mf_show_api_hash))
                }
            }
            Text(stringResource(R.string.mf_api_keys_help))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MfPrimaryButton(onClick = onSave) { Text(stringResource(R.string.mf_save_keys)) }
                if (state.hasInAppKeys) {
                    MfRemoveButton(onClick = onRemove) { Text(stringResource(R.string.mf_remove_keys)) }
                }
            }
        }
        state.keyError?.let {
            Text(
                when (it) {
                    MfKeyError.InvalidId -> stringResource(R.string.mf_invalid_api_id)
                    MfKeyError.InvalidHash -> stringResource(R.string.mf_invalid_api_hash)
                    MfKeyError.InvalidKeys -> stringResource(R.string.mf_invalid_api_hash)
                    MfKeyError.StorageFailure -> stringResource(R.string.mf_api_keys_storage_error)
                },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MfSectionCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            title?.let {
                Text(
                    text = it,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            content()
        }
    }
}

@Composable
private fun AccountSection(
    state: MfChannelLibraryState,
    phone: String,
    onPhone: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    viewModel: MfChannelLibraryViewModel,
) {
    when (state.auth) {
        AuthState.Ready -> {
            Text(stringResource(R.string.mf_signed_in))
            Spacer(Modifier.height(10.dp))
            MfPrimaryButton(onClick = viewModel::logout) { Text(stringResource(R.string.mf_log_out)) }
        }
        is AuthState.WaitCode -> SecretField(stringResource(R.string.mf_code), code, onCode, KeyboardType.Number)
        is AuthState.WaitPassword -> SecretField(stringResource(R.string.mf_password), password, onPassword, KeyboardType.Password)
        else -> {
            Text(stringResource(R.string.mf_account_not_connected))
            Spacer(Modifier.height(10.dp))
            SecretField(stringResource(R.string.mf_phone), phone, onPhone, KeyboardType.Phone, masked = false)
        }
    }
    val action = when (state.auth) {
        is AuthState.WaitCode -> AuthInput.Code(code)
        is AuthState.WaitPassword -> AuthInput.Password(password)
        else -> AuthInput.Phone(phone)
    }
    if (state.auth != AuthState.Ready) {
        Button(
            onClick = { viewModel.submit(action) },
            enabled = state.auth != AuthState.Starting && actionValue(action).isNotBlank(),
        ) {
            Text(stringResource(R.string.mf_continue))
        }
    }
    state.authError?.let { Text(it) }
}

private fun actionValue(action: AuthInput) = when (action) {
    is AuthInput.Phone -> action.phone
    is AuthInput.Code -> action.code
    is AuthInput.Password -> action.password
    is AuthInput.FirstName -> action.firstName
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    type: KeyboardType,
    masked: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type),
    )
}

@Composable
private fun ChannelSection(
    state: MfChannelLibraryState,
    onOpenPicker: () -> Unit,
) {
    val current = state.channels.firstOrNull { it.active }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenPicker)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = current?.let { stringResource(R.string.mf_current_channel, it.title) }
                    ?: stringResource(R.string.mf_no_channel),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.flagship_card_chevron),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(12.dp)
                    .rotate(90f),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    MfPrimaryButton(onClick = onOpenPicker) {
        Text(stringResource(R.string.mf_refresh_channels))
    }
}

@Composable
private fun IndexSection(state: MfChannelLibraryState, viewModel: MfChannelLibraryViewModel) {
    val current = state.channels.firstOrNull { it.active }
    Text(
        text = (current?.trackCount ?: 0).toString(),
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
    )
    when (val sync = state.sync) {
        is SyncState.Scanning -> Text(stringResource(R.string.mf_indexing_progress, sync.seen, sync.atMessageId))
        is SyncState.Error -> Text(sync.message)
        SyncState.Idle -> Text(
            current?.updatedAt?.takeIf { it > 0 }?.let {
                stringResource(R.string.mf_last_sync, DateFormat.getDateTimeInstance().format(Date(it)))
            } ?: stringResource(R.string.mf_not_indexed),
        )
    }
    MfTonalButton(
        onClick = viewModel::resync,
        enabled = current != null && state.sync !is SyncState.Scanning,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.sync is SyncState.Scanning) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(stringResource(R.string.mf_resync))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelPickerSheet(
    state: MfChannelLibraryState,
    onDismiss: () -> Unit,
    onSelect: (Channel) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val filtered = state.candidates.filter { it.title.contains(query, ignoreCase = true) }

    LaunchedEffect(state.candidates, state.authError) {
        if (state.candidates.isNotEmpty() || state.authError != null) loading = false
    }
    LaunchedEffect(Unit) {
        delay(1_000)
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.mf_choose_channel),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                placeholder = { Text(stringResource(R.string.mf_search_channels)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )
            when {
                loading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(stringResource(R.string.mf_loading_channels))
                    }
                }
                filtered.isEmpty() -> {
                    Text(
                        stringResource(R.string.mf_no_channels),
                        modifier = Modifier.padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    ) {
                        items(filtered, key = { it.id }) { channel ->
                            val selected = state.channels.any { it.active && it.chatId == channel.id }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(channel) }
                                    .padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(channel.title, modifier = Modifier.weight(1f))
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MfPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
        ),
        content = content,
    )
}

@Composable
private fun MfTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}

@Composable
private fun MfRemoveButton(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
        border = androidx.compose.material3.ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        ),
        content = content,
    )
}

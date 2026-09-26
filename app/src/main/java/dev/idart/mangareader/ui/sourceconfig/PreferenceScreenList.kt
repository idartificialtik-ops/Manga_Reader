package dev.idart.mangareader.ui.sourceconfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference

/**
 * Renders an extension's [PreferenceScreen] with Compose.
 *
 * The app has no XML/Fragment preference UI (it is 100% Compose on
 * `android:Theme.Material`), so the androidx `Preference` tree is walked here
 * instead of through `PreferenceFragmentCompat`.
 *
 * Values are written with androidx' own setters (`setText`, `setChecked`,
 * `setValue`): they run the extension's `OnPreferenceChangeListener` — which
 * some sources use to validate a login — and persist to the screen's
 * SharedPreferences, which is the file the source reads back.
 */
@Composable
fun PreferenceScreenList(
    screen: PreferenceScreen,
    modifier: Modifier = Modifier,
) {
    // Bumped after every change: sources react by rewriting summaries and
    // visibility (e.g. reveal the password field only for a "advanced" mode).
    var revision by remember(screen) { mutableStateOf(0) }
    val onChanged: () -> Unit = { revision++ }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // `revision` is read here so the tree re-reads itself after a change.
        @Suppress("UNUSED_EXPRESSION")
        revision
        PreferenceGroupContent(group = screen, onChanged = onChanged)
    }
}

@Composable
private fun PreferenceGroupContent(
    group: PreferenceGroup,
    onChanged: () -> Unit,
) {
    for (index in 0 until group.preferenceCount) {
        val preference = group.getPreference(index) ?: continue
        if (!preference.isVisible) continue
        when (preference) {
            is PreferenceCategory -> CategoryHeader(preference)
            // Nested screens / groups: recurse (already visible itself).
            is PreferenceGroup -> PreferenceGroupContent(preference, onChanged)
            else -> PreferenceRow(preference = preference, onChanged = onChanged)
        }
    }
    Unit
}

@Composable
private fun CategoryHeader(preference: Preference) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(
            text = preference.title?.toString().orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        preference.summary?.toString()?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreferenceRow(
    preference: Preference,
    onChanged: () -> Unit,
) {
    var dialog by remember(preference.key) { mutableStateOf(false) }
    val enabled = preference.isEnabled

    when (preference) {
        is TwoStatePreference -> TwoStateRow(preference, enabled, onChanged)
        is EditTextPreference -> {
            ClickableRow(preference, enabled, currentValue = preference.text) { dialog = true }
            if (dialog) {
                TextDialog(
                    preference = preference,
                    onDismiss = { dialog = false },
                    onConfirm = { value ->
                        dialog = false
                        preference.text = value
                        onChanged()
                    },
                )
            }
        }
        is ListPreference -> {
            val stored = preference.value
            val selectedIndex = stored?.let { preference.findIndexOfValue(it) } ?: -1
            val label = stored?.let { preference.entries.getOrNull(selectedIndex)?.toString() } ?: stored
            ClickableRow(preference, enabled, currentValue = label) { dialog = true }
            if (dialog) {
                OptionsDialog(
                    title = preference.title?.toString().orEmpty(),
                    entries = preference.entries.map { it.toString() },
                    selectedIndex = selectedIndex.coerceAtLeast(0),
                    onDismiss = { dialog = false },
                    onSelect = { index ->
                        dialog = false
                        preference.value = preference.getEntryValues()[index].toString()
                        onChanged()
                    },
                )
            }
        }
        is SeekBarPreference -> {
            ClickableRow(preference, enabled, currentValue = preference.value.toString()) { dialog = true }
            if (dialog) {
                SliderDialog(
                    preference = preference,
                    onDismiss = { dialog = false },
                    onConfirm = { value ->
                        dialog = false
                        preference.value = value
                        onChanged()
                    },
                )
            }
        }
        else -> {
            // Unknown/custom preference: keep it visible and read-only rather
            // than hiding a setting the source expects the user to see.
            ListItem(
                headlineContent = { Text(preference.title?.toString().orEmpty()) },
                supportingContent = preference.summary?.let { summary ->
                    { Text(summary.toString()) }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TwoStateRow(
    preference: TwoStatePreference,
    enabled: Boolean,
    onChanged: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = preference.title?.toString().orEmpty(),
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        },
        supportingContent = preference.summary?.let { summary ->
            {
                Text(
                    text = summary.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Switch(
                checked = preference.isChecked,
                onCheckedChange = { value ->
                    preference.isChecked = value
                    onChanged()
                },
                enabled = enabled,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                preference.isChecked = !preference.isChecked
                onChanged()
            },
    )
}

@Composable
private fun ClickableRow(
    preference: Preference,
    enabled: Boolean,
    currentValue: String?,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = preference.title?.toString().orEmpty(),
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        },
        supportingContent = {
            val summary = preference.summary?.toString().orEmpty()
            val value = currentValue.orEmpty()
            Column {
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Stored value: never render a password, and say so.
                if (value.isNotBlank()) {
                    Text(
                        text = if (isSecret(preference)) "••••••" else value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * Whether a value should be treated as a secret. Extensions mask their input
 * with `EditTextPreference.OnBindEditTextListener`, which is package-private
 * and therefore not readable from here — so the key/title decide it, and the
 * dialog also offers an explicit show/hide toggle.
 */
private fun isSecret(preference: Preference): Boolean {
    val hint = "${preference.key.orEmpty()} ${preference.title?.toString().orEmpty()}".lowercase()
    return listOf("password", "senha", "passwd", "token", "secret", "api_key", "apikey")
        .any { it in hint }
}

/** Single-value editor: the login case (usuário/senha). */
@Composable
private fun TextDialog(
    preference: EditTextPreference,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(preference.key) { mutableStateOf(preference.text.orEmpty()) }
    var revealed by remember(preference.key) { mutableStateOf(false) }
    val secret = isSecret(preference)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preference.title?.toString().orEmpty()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preference.summary?.toString()?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = preference.title?.let { { Text(it.toString()) } },
                    visualTransformation = when {
                        !secret || revealed -> VisualTransformation.None
                        else -> PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        if (secret) {
                            IconButton(onClick = { revealed = !revealed }) {
                                Icon(
                                    imageVector = if (revealed) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (revealed) "Ocultar" else "Mostrar",
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun OptionsDialog(
    title: String,
    entries: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                entries.forEachIndexed { index, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = index == selectedIndex, onClick = { onSelect(index) })
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        Text(entry, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun SliderDialog(
    preference: SeekBarPreference,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember(preference.key) { mutableStateOf(preference.value.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preference.title?.toString().orEmpty()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value.toInt().toString(), style = MaterialTheme.typography.headlineSmall)
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = preference.min.toFloat()..preference.max.toFloat(),
                    steps = (preference.max - preference.min - 1).coerceAtLeast(0),
                )
                HorizontalDivider()
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.toInt()) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

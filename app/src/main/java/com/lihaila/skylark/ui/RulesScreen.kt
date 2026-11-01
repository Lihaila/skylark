package com.lihaila.skylark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lihaila.skylark.R
import com.lihaila.skylark.core.Country
import com.lihaila.skylark.data.AppStore
import com.lihaila.skylark.data.Rule
import com.lihaila.skylark.data.RuleAction
import com.lihaila.skylark.data.RuleType
import com.lihaila.skylark.vpn.VpnState
import kotlinx.coroutines.launch
import androidx.compose.material3.RadioButton
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(modifier: Modifier = Modifier) {
    val state by AppStore.state.collectAsState()
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var pickingCountry by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_rules)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, stringResource(R.string.add_rule)) } },
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = cardColors) {
                    val home = Country.effective(context, state.settings)
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { pickingCountry = true },
                        headlineContent = { Text(stringResource(R.string.rule_bypass_home, Country.displayName(home))) },
                        supportingContent = { Text(stringResource(R.string.rule_bypass_home_desc)) },
                        trailingContent = {
                            Switch(state.settings.bypassHome, { v ->
                                AppStore.setSettings { it.copy(bypassHome = v) }
                                if (v) scope.launch { Country.ensureGeoip(context, home); VpnState.reload(context) } else VpnState.reload(context)
                            })
                        },
                    )
                    ToggleRow(stringResource(R.string.rule_bypass_cn), stringResource(R.string.rule_bypass_cn_desc), state.settings.bypassChina) { v ->
                        AppStore.setSettings { it.copy(bypassChina = v) }; VpnState.reload(context)
                    }
                    if (com.lihaila.skylark.BuildConfig.AD_BLOCK) {
                        ToggleRow(stringResource(R.string.rule_block_ads), stringResource(R.string.rule_block_ads_desc), state.settings.blockAds) { v ->
                            AppStore.setSettings { it.copy(blockAds = v) }; VpnState.reload(context)
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(top = 8.dp, start = 4.dp)) {
                    Text(stringResource(R.string.custom_rules), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.custom_rules_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (state.rules.isEmpty()) {
                item { Text(stringResource(R.string.no_custom_rules), color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(4.dp)) }
            }
            items(state.rules, key = { it.id }) { r ->
                Card(colors = cardColors) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(r.value) },
                        supportingContent = { Text(r.type.label) },
                        trailingContent = {
                            Row {
                                ActionBadge(r.action)
                                IconButton(onClick = { AppStore.deleteRule(r.id); VpnState.reload(context) }) { Icon(Icons.Default.Delete, null) }
                            }
                        },
                    )
                }
            }
        }
    }

    if (pickingCountry) CountryDialog(state.settings.homeCountry, Country.detect(context), onDismiss = { pickingCountry = false }) { code ->
        pickingCountry = false
        AppStore.setSettings { it.copy(homeCountry = code, bypassHome = true) }
        scope.launch { Country.ensureGeoip(context, Country.effective(context, AppStore.state.value.settings)); VpnState.reload(context) }
    }
    if (adding) AddRuleDialog(onDismiss = { adding = false }) { AppStore.addRule(it); adding = false; VpnState.reload(context) }
}

@Composable
fun ToggleRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = desc?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
    )
}

@Composable
private fun ActionBadge(a: RuleAction) {
    val (label, color) = when (a) {
        RuleAction.PROXY -> stringResource(R.string.action_proxy) to Brand
        RuleAction.DIRECT -> stringResource(R.string.action_direct) to Color(0xFF30A46C)
        RuleAction.REJECT -> stringResource(R.string.action_reject) to Color(0xFFE5484D)
    }
    Text(label, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, end = 4.dp))
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onAdd: (Rule) -> Unit) {
    var type by remember { mutableStateOf(RuleType.DOMAIN_SUFFIX) }
    var value by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RuleAction.PROXY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_rule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleType.entries.forEach { t -> FilterChip(type == t, onClick = { type = t }, label = { Text(t.label) }) }
                }
                OutlinedTextField(
                    value, { value = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.rule_value)) },
                    placeholder = {
                        Text(stringResource(when (type) {
                            RuleType.IP_CIDR -> R.string.rule_value_hint_ip
                            RuleType.GEOIP -> R.string.rule_value_hint_geo
                            else -> R.string.rule_value_hint_domain
                        }))
                    },
                )
                val actions = listOf(RuleAction.PROXY to R.string.action_proxy, RuleAction.DIRECT to R.string.action_direct, RuleAction.REJECT to R.string.action_reject)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    actions.forEachIndexed { i, (a, label) ->
                        SegmentedButton(action == a, onClick = { action = a }, shape = SegmentedButtonDefaults.itemShape(i, actions.size)) { Text(stringResource(label)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotEmpty(), onClick = {
                val v = if (type == RuleType.GEOIP) value.uppercase() else value.lowercase()
                onAdd(Rule(type = type, value = v, action = action))
            }) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Searchable list of every country/region, names shown in the app's language. "" = automatic. */
@Composable
private fun CountryDialog(current: String, detected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val locale = Locale.getDefault()
    val all = remember(locale) {
        Locale.getISOCountries().map { it to Country.displayName(it, locale) }.sortedBy { it.second }
    }
    val list = all.filter { query.isBlank() || it.second.contains(query, true) || it.first.equals(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_country)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        CountryRow(stringResource(R.string.country_auto, Country.displayName(detected, locale)), current.isBlank()) { onPick("") }
                    }
                    items(list, key = { it.first }) { (code, name) -> CountryRow(name, current == code) { onPick(code) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CountryRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected, onClick = null) },
    )
}

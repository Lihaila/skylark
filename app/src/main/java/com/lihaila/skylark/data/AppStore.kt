package com.lihaila.skylark.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Single source of truth, persisted as one JSON file. */
object AppStore {
    private lateinit var file: File
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "state.json")
        _state.value = load()
    }

    fun update(transform: (AppState) -> AppState) {
        _state.update(transform)
        save(_state.value)
    }

    fun select(id: String) = update { it.copy(selectedId = id) }
    fun setMode(mode: Mode) = update { it.copy(mode = mode) }
    fun setSettings(transform: (Settings) -> Settings) = update { it.copy(settings = transform(it.settings)) }

    fun addNodes(nodes: List<Node>) = update { s ->
        val all = s.nodes + nodes
        s.copy(nodes = all, selectedId = s.selectedId ?: all.firstOrNull()?.id)
    }

    fun upsertNode(node: Node) = update { s ->
        val exists = s.nodes.any { it.id == node.id }
        val all = if (exists) s.nodes.map { if (it.id == node.id) node else it } else s.nodes + node
        s.copy(nodes = all, selectedId = s.selectedId ?: node.id)
    }

    fun deleteNode(id: String) = update { s -> s.copy(nodes = s.nodes.filterNot { it.id == id }) }

    /** Replace all nodes belonging to [sub] with [nodes], keeping the selection if a node with the same name survives. */
    fun replaceSubNodes(sub: Subscription, nodes: List<Node>) = update { s ->
        val selectedName = s.selected?.takeIf { it.subId == sub.id }?.name
        val tagged = nodes.map { it.copy(subId = sub.id) }
        val all = s.nodes.filterNot { it.subId == sub.id } + tagged
        val newSel = if (selectedName != null) tagged.firstOrNull { it.name == selectedName }?.id ?: s.selectedId else s.selectedId
        val subs = if (s.subs.any { it.id == sub.id }) s.subs.map { if (it.id == sub.id) sub else it } else s.subs + sub
        s.copy(nodes = all, subs = subs, selectedId = newSel ?: all.firstOrNull()?.id)
    }

    fun deleteSub(id: String) = update { s -> s.copy(subs = s.subs.filterNot { it.id == id }, nodes = s.nodes.filterNot { it.subId == id }) }

    fun addRule(rule: Rule) = update { it.copy(rules = listOf(rule) + it.rules) }
    fun deleteRule(id: String) = update { s -> s.copy(rules = s.rules.filterNot { it.id == id }) }

    private fun load(): AppState = runCatching {
        val o = JSONObject(file.readText())
        AppState(
            nodes = o.optJSONArray("nodes").objects().map(Node::fromJson),
            subs = o.optJSONArray("subs").objects().map(Subscription::fromJson),
            selectedId = o.optString("selectedId").takeIf { it.isNotEmpty() },
            mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.RULE),
            rules = o.optJSONArray("rules").objects().map(Rule::fromJson),
            settings = Settings.fromJson(o.optJSONObject("settings")),
        )
    }.getOrDefault(AppState())

    @Synchronized
    private fun save(s: AppState) {
        val o = JSONObject()
            .put("nodes", JSONArray(s.nodes.map { it.toJson() }))
            .put("subs", JSONArray(s.subs.map { it.toJson() }))
            .put("selectedId", s.selectedId ?: "")
            .put("mode", s.mode.name)
            .put("rules", JSONArray(s.rules.map { it.toJson() }))
            .put("settings", s.settings.toJson())
        val tmp = File(file.parentFile, "state.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}

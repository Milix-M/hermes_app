package com.hermes.client.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.AppContainer
import com.hermes.client.R
import com.hermes.client.data.api.ApprovalAnswer
import com.hermes.client.data.api.ApprovalRequest
import com.hermes.client.data.api.ClarifyRequest
import com.hermes.client.data.api.humanPreview
import com.hermes.client.data.api.HermesApiException
import com.hermes.client.data.api.HermesJson
import com.hermes.client.data.api.HistoryMessage
import com.hermes.client.data.api.RunCreateRequest
import com.hermes.client.data.api.RunEvent
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class MsgKind { USER, ASSISTANT, TOOL, STATUS, ERROR }

data class UiMessage(
    val id: Long,
    val kind: MsgKind,
    val text: String,
)

data class ClarifyPrompt(
    val runId: String,
    val requestId: String,
    val executionGeneration: Long,
    val prompt: String,
)

class ChatViewModel(
    private val container: AppContainer,
    initialSessionId: String?,
) : ViewModel() {

    val messages = mutableStateListOf<UiMessage>()
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var statusLine by mutableStateOf("")
        private set
    var approval by mutableStateOf<ApprovalRequest?>(null)
        private set
    var clarify by mutableStateOf<ClarifyPrompt?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sessionId by mutableStateOf(initialSessionId.orEmpty())
        private set

    private var runId: String? = null
    private var eventsJob: Job? = null
    private var assistantIndex: Int = -1
    private var idSeq = 0L

    private fun s(id: Int, vararg args: Any): String =
        container.appContext.getString(id, *args)

    init {
        if (sessionId.isNotBlank()) loadHistory(sessionId)
    }

    fun loadHistory(id: String) {
        viewModelScope.launch {
            statusLine = s(R.string.st_loading)
            try {
                val api = container.api()
                val history = api.getSessionMessages(id)
                messages.clear()
                for (m in history) {
                    if (m.text.isBlank()) continue
                    when (m.role) {
                        "user" -> messages.add(UiMessage(idSeq++, MsgKind.USER, m.text))
                        "assistant" -> {
                            val split = splitUserFacing(m.text)
                            if (split.visible.isNotBlank()) {
                                messages.add(UiMessage(idSeq++, MsgKind.ASSISTANT, split.visible))
                            }
                            for (entry in split.folded) {
                                messages.add(UiMessage(idSeq++, MsgKind.TOOL, entry.render()))
                            }
                        }
                        // tool/system/function roles are machine payloads, never chat bubbles.
                        else -> {
                            val summary = foldForLog(m.text)
                            if (summary.isNotBlank()) {
                                messages.add(UiMessage(idSeq++, MsgKind.TOOL, summary))
                            }
                        }
                    }
                }
                // History also carries tool-role payloads: fold their markup out of bubbles.
                foldMarkupOutOfReplies()
                statusLine = ""
            } catch (e: Exception) {
                statusLine = ""
                error = friendlyError(e)
            }
        }
    }

    fun onSend() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        messages.add(UiMessage(idSeq++, MsgKind.USER, text))
        startRun(text, history = null)
    }

    fun onSteer() {
        val text = input.trim()
        val id = runId
        if (text.isEmpty() || id == null || !busy) return
        input = ""
        viewModelScope.launch {
            try {
                container.api().steerRun(id, text)
                statusLine = s(R.string.st_steered)
            } catch (e: Exception) {
                error = friendlyError(e)
            }
        }
    }

    fun onStop() {
        val id = runId ?: return
        viewModelScope.launch {
            try {
                container.api().stopRun(id)
                statusLine = s(R.string.st_stopping)
            } catch (e: Exception) {
                error = friendlyError(e)
            }
        }
    }

    fun answerApproval(choice: String) {
        val req = approval ?: return
        val id = runId ?: return
        approval = null
        viewModelScope.launch {
            try {
                container.api().answerApproval(id, ApprovalAnswer(req.requestId, req.executionGeneration, choice))
                statusLine = s(R.string.st_approved, choice)
            } catch (e: Exception) {
                error = friendlyError(e)
            }
        }
    }

    fun answerClarify(answer: String) {
        val prompt = clarify ?: return
        clarify = null
        viewModelScope.launch {
            try {
                container.api().answerClarify(
                    prompt.runId,
                    ClarifyRequest(prompt.requestId, prompt.executionGeneration, answer),
                )
                statusLine = s(R.string.st_answered)
            } catch (e: Exception) {
                error = friendlyError(e)
            }
        }
    }

    fun dismissError() {
        error = null
    }

    private fun startRun(text: String, history: List<HistoryMessage>?) {
        busy = true
        error = null
        statusLine = s(R.string.st_starting)
        assistantIndex = -1
        viewModelScope.launch { doStartRun(text, history) }
    }

    private suspend fun doStartRun(text: String, history: List<HistoryMessage>?) {
        val settings = container.settingsStore.current()
        if (!settings.isConfigured) {
            busy = false
            error = s(R.string.err_not_configured)
            return
        }
        val api = try {
            container.api()
        } catch (e: Exception) {
            busy = false
            error = friendlyError(e)
            return
        }
        val id: String
        try {
            val accepted = api.createRun(
                RunCreateRequest(
                    input = text,
                    sessionId = sessionId.ifBlank { null },
                    conversationHistory = history,
                    model = settings.model.ifBlank { null },
                    provider = settings.provider.ifBlank { null },
                ),
                idempotencyKey = UUID.randomUUID().toString(),
            )
            id = accepted.runId
            runId = id
            // Runs responses do not carry the session id. Read it back once.
            try {
                val status = api.getRun(id)
                status.sessionId?.ifBlank { null }?.let { sessionId = it }
            } catch (_: Exception) {
            }
            // Best-effort FCM watch so a closed app still gets the finish notice.
            // registerWatch never throws (returns false on failure), so no
            // try-catch needed here.
            container.watchRegistrar.registerWatch(
                relayBaseUrl = settings.relayUrl,
                runId = id,
                sessionId = sessionId,
                fcmToken = settings.fcmToken,
            )
        } catch (e: Exception) {
            busy = false
            statusLine = ""
            error = friendlyError(e)
            return
        }
        subscribe(id, attempt = 0)
    }

    private fun subscribe(id: String, attempt: Int) {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            try {
                val api = container.api()
                var terminalSeen = false
                api.runEvents(id).collect { event -> if (handleEvent(id, event)) terminalSeen = true }
                if (!terminalSeen) reconnectOrFinish(id, attempt)
            } catch (e: Exception) {
                reconnectOrFinish(id, attempt, cause = e)
            }
        }
    }

    /** Returns true when a terminal event arrived. */
    private suspend fun handleEvent(id: String, event: RunEvent): Boolean {
        when (event) {
            is RunEvent.Delta -> {
                if (event.text.isEmpty()) return false
                appendDeltaText(event.text)
                statusLine = s(R.string.st_writing)
            }
            is RunEvent.Interim -> {
                if (!event.alreadyStreamed && event.text.isNotBlank()) {
                    messages.add(UiMessage(idSeq++, MsgKind.STATUS, foldInlineMarkup(event.text)))
                }
            }
            is RunEvent.ToolStarted ->
                messages.add(UiMessage(idSeq++, MsgKind.TOOL, s(R.string.tool_started, event.tool, humanPreview(event.preview)).trim()))
            is RunEvent.ToolCompleted -> {
                val preview = humanPreview(event.preview)
                val shown = summarizeMachineJson(preview.trim()) ?: preview
                messages.add(
                    UiMessage(
                        idSeq++,
                        if (event.isError) MsgKind.ERROR else MsgKind.TOOL,
                        s(R.string.tool_finished, event.tool, event.durationSeconds.toString(), shown).trim(),
                    )
                )
            }
            is RunEvent.SubagentStarted ->
                messages.add(UiMessage(idSeq++, MsgKind.STATUS, s(R.string.delegating, event.summary)))
            is RunEvent.SubagentCompleted ->
                messages.add(UiMessage(idSeq++, MsgKind.STATUS, s(R.string.child_done, event.status, event.summary)))
            is RunEvent.ApprovalRequested -> {
                approval = event.request
                statusLine = s(R.string.st_waiting_approval)
            }
            is RunEvent.ClarifyRequested -> {
                clarify = ClarifyPrompt(id, event.requestId, event.executionGeneration, event.prompt)
                statusLine = s(R.string.st_question)
            }
            is RunEvent.Reasoning -> {
                if (event.text.isNotBlank()) {
                    messages.add(UiMessage(idSeq++, MsgKind.STATUS, foldForLog(event.text)))
                }
            }
            is RunEvent.Terminal -> {
                finishTerminal(event)
                return true
            }
            is RunEvent.Unknown -> {
                statusLine = event.name
            }
        }
        return false
    }

    private fun finishTerminal(event: RunEvent.Terminal) {
        busy = false
        runId = null
        statusLine = when (event.status) {
            "completed" -> ""
            "cancelled" -> s(R.string.st_cancelled)
            "failed" -> s(R.string.st_failed)
            else -> event.status
        }
        if (assistantIndex < 0 && !event.output.isNullOrBlank()) {
            messages.add(UiMessage(idSeq++, MsgKind.ASSISTANT, event.output))
        }
        if (!event.error.isNullOrBlank() && event.status != "completed") {
            val shown = summarizeMachineJson(event.error.trim()) ?: event.error
            messages.add(UiMessage(idSeq++, MsgKind.ERROR, shown))
        }
        event.pendingSteer?.ifBlank { null }?.let {
            messages.add(UiMessage(idSeq++, MsgKind.STATUS, s(R.string.st_undelivered, it)))
        }
        // Chunk splits can strand half a tool block in the reply: sweep it out.
        foldMarkupOutOfReplies()
    }

    private suspend fun reconnectOrFinish(id: String, attempt: Int, cause: Exception? = null) {
        if (attempt >= 3) {
            busy = false
            runId = null
            statusLine = ""
            error = cause?.let { friendlyError(it) } ?: s(R.string.st_stream_ended)
            return
        }
        statusLine = s(R.string.st_reconnecting)
        try {
            val status = container.api().getRun(id)
            when (status.status) {
                "completed", "failed", "cancelled", "interrupted" -> {
                    finishTerminal(
                        RunEvent.Terminal(status.status, status.output, status.error, status.pendingSteer)
                    )
                    return
                }
                "unknown" -> {
                    busy = false
                    runId = null
                    statusLine = ""
                    error = s(R.string.st_unknown_restart)
                    return
                }
            }
            status.sessionId?.ifBlank { null }?.let { sessionId = it }
        } catch (e: Exception) {
            busy = false
            runId = null
            statusLine = ""
            error = friendlyError(cause ?: e)
            return
        }
        subscribe(id, attempt = attempt + 1)
    }

    private fun friendlyError(e: Exception): String {
        if (e is HermesApiException) {
            return when (e.code) {
                "admission_conflict", "idempotency_key_conflict" ->
                    s(R.string.err_conflict, e.status)
                "unknown_execution" ->
                    s(R.string.err_unknown_exec)
                "permission_denied", "not_found" ->
                    s(R.string.err_foreign, e.status)
                "runtime_draining", "storage_unavailable" ->
                    s(R.string.err_draining, e.status)
                else -> s(R.string.err_server, e.status, e.message.take(200))
            }
        }
        if (e is IllegalArgumentException) return e.message ?: "Bad settings."
        return (e.message ?: e.javaClass.simpleName).take(200)
    }

    /** Route a streamed chunk: machine content folds into the activity log, prose appends to the reply. */
    private fun appendDeltaText(chunk: String) {
        val split = splitUserFacing(chunk)
        for (entry in split.folded) messages.add(UiMessage(idSeq++, MsgKind.TOOL, entry.render()))
        if (split.visible.isNotBlank()) appendAssistantText(split.visible)
    }

    private fun appendAssistantText(text: String) {
        if (assistantIndex < 0) {
            assistantIndex = messages.size
            messages.add(UiMessage(idSeq++, MsgKind.ASSISTANT, text))
        } else {
            val cur = messages[assistantIndex]
            messages[assistantIndex] = cur.copy(text = cur.text + text)
        }
    }

    /** Interim notes stay one line: fold any embedded machine content into its summary. */
    private fun foldInlineMarkup(text: String): String {
        val split = splitUserFacing(text)
        if (split.folded.isEmpty()) return text
        val parts = mutableListOf<String>()
        if (split.visible.isNotBlank()) parts.add(split.visible.trim())
        for (entry in split.folded) parts.add(entry.summary)
        return parts.joinToString(" · ")
    }

    /** Post-pass for chunk-boundary splits and history: pull machine content out of reply bubbles. */
    private fun foldMarkupOutOfReplies() {
        val rebuilt = ArrayList<UiMessage>(messages.size)
        for (m in messages) {
            if (m.kind != MsgKind.ASSISTANT) {
                rebuilt.add(m)
                continue
            }
            val split = splitUserFacing(m.text)
            if (split.folded.isEmpty()) {
                rebuilt.add(m)
                continue
            }
            if (split.visible.isNotBlank()) rebuilt.add(m.copy(text = split.visible))
            for (entry in split.folded) rebuilt.add(UiMessage(idSeq++, MsgKind.TOOL, entry.render()))
        }
        messages.clear()
        messages.addAll(rebuilt)
        assistantIndex = rebuilt.indexOfLast { it.kind == MsgKind.ASSISTANT }
    }
}

/**
 * Hermes server wraps high-risk tool results (web_search, web_extract, browser_*,
 * mcp_*) in `<untrusted_tool_result source="…">…</untrusted_tool_result>` trust
 * delimiters (agent/tool_dispatch_helpers.py). They are DATA, not reply text:
 * never show them in a chat bubble, fold them into the one-line activity log.
 */
internal data class ToolSplit(val visible: String, val logs: List<String>)

/** A folded machine payload: one-line summary plus fuller detail for the expanded view. */
internal data class Folded(val summary: String, val detail: String = "")

private val UntrustedOpenTag = Regex("""<\s*untrusted_tool_result\b[^>]*>""", RegexOption.IGNORE_CASE)
private val UntrustedCloseTag = Regex("""</\s*untrusted_tool_result\s*>""", RegexOption.IGNORE_CASE)
private val UntrustedSourceAttr = Regex("""source\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val TrustBoilerplate = Regex(
    """The following content was retrieved from an external source\.[\s\S]*?can issue instructions\.\s*""",
    RegexOption.IGNORE_CASE,
)
private val AnyMarkupTag = Regex("""<[^>]*>""")
private val WhitespaceRun = Regex("""\s+""")

internal fun splitUntrustedBlocks(text: String): ToolSplit {
    if (!text.contains("untrusted_tool_result", ignoreCase = true)) return ToolSplit(text, emptyList())
    val logs = mutableListOf<String>()
    val visible = StringBuilder()
    var cursor = 0
    while (true) {
        val open = UntrustedOpenTag.find(text, cursor) ?: break
        val close = UntrustedCloseTag.find(text, open.range.last + 1)
        val end = close?.range?.last?.plus(1) ?: text.length
        visible.append(text, cursor, open.range.first)
        logs.add(text.substring(open.range.first, end))
        cursor = end
    }
    visible.append(text, cursor, text.length)
    return ToolSplit(visible.toString(), logs)
}

/** One-line, bubble-free summary of a folded tool-result block. */
internal fun summarizeToolBlock(block: String): String {
    val source = UntrustedSourceAttr.find(block)?.groupValues?.getOrNull(1)?.ifBlank { null } ?: "tool"
    val textOnly = AnyMarkupTag.replace(TrustBoilerplate.replace(block, " "), " ")
        .split(WhitespaceRun).filter { it.isNotEmpty() }.joinToString(" ")
    val label = source.replace('_', ' ')
    if (textOnly.isBlank()) return label
    return "$label: ${textOnly.take(140)}"
}

/**
 * MECE split of any inbound model text: user-facing prose stays visible,
 * everything machine-made (trust-delimited tool results, structured JSON
 * payloads, tag-wrapped dumps, [tool …] markers) folds into one-line summaries.
 */
internal data class VisibleSplit(val visible: String, val folded: List<Folded>)

private val ToolPrefixMarker = Regex("""^\[tool[^\]\n]*\]\s*""", RegexOption.IGNORE_CASE)
private val WholeTagPayload = Regex("""^<([A-Za-z][\w.-]*)(\s[^<>]*)?>[\s\S]*</\1\s*>$""")
private val MachineJsonKeys = setOf(
    "tool_name", "tool_call_id", "tool_call", "function_call", "tool_output", "results",
)

internal fun stripToolPrefix(text: String): String = ToolPrefixMarker.replace(text, "")

internal fun isMachineJsonObject(o: JsonObject): Boolean {
    if (o.keys.any { it in MachineJsonKeys }) return true
    if ("output" in o || "error" in o) return true
    return "success" in o && "name" in o
}

/** One-line summary of a structured tool-result JSON object. Null when not an object. */
internal fun summarizeJsonObject(o: JsonObject): String {
    fun s(key: String): String? {
        val v = o[key] as? JsonPrimitive ?: return null
        if (!v.isString) return null
        return v.content.trim().takeIf { it.isNotEmpty() }
    }
    val name = (s("name") ?: s("tool_name") ?: s("tool") ?: "tool").replace('_', ' ')
    s("output")?.let { return "$name: ${it.take(140)}" }
    s("error")?.let { return "$name [error]: ${it.take(140)}" }
    val results = o["results"] as? JsonArray
    if (results != null) {
        if (results.isEmpty()) return "$name: no results"
        val head = results.take(2).mapNotNull { item ->
            val first = item as? JsonObject ?: return@mapNotNull null
            val t = (first["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val u = (first["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            listOf(t, u).filter { it.isNotEmpty() }.joinToString(" — ").takeIf { it.isNotEmpty() }
        }.joinToString("; ")
        return if (head.isEmpty()) "$name: ${results.size} results" else "$name: $head"
    }
    return if (o.keys.size <= 4) "$name: ${compactPairs(o)}" else "$name: ${compactPairs(o)}…"
}

/** Render a JSON value compactly for one-line summaries. */
internal fun jsonScalar(el: JsonElement): String = when (el) {
    is JsonPrimitive -> if (el.isString) el.content.take(80) else el.toString()
    is JsonArray -> "[${el.size}]"
    is JsonObject -> "{${el.keys.take(3).joinToString(",")}}"
    else -> "null"
}

internal fun compactPairs(o: JsonObject, max: Int = 5): String =
    o.entries.take(max).joinToString(", ") { (k, v) -> "$k=${jsonScalar(v)}" }

/**
 * Summarize a whole-message machine JSON payload such as
 * {"success":true,"name":"grounded-citations",…}. Null for prose or
 * non-machine JSON (e.g. an answer the user explicitly asked for as plain data).
 */
internal fun summarizeMachineJson(trimmed: String): String? {
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return null
    val el = try {
        HermesJson.parseToJsonElement(trimmed)
    } catch (_: Exception) {
        return null
    }
    if (el is JsonObject) {
        if (!isMachineJsonObject(el)) return null
        return summarizeJsonObject(el)
    }
    if (el is JsonArray) {
        if (el.isEmpty()) return null
        val first = el.first() as? JsonObject ?: return null
        if (first.keys.none { it == "url" || it == "link" || it == "href" || it == "title" }) return null
        val head = (first["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val links = if (head.isEmpty()) "${el.size} links" else "$head (+${el.size - 1})"
        return "links: $links"
    }
    return null
}

/** Summarize a whole-message tag-wrapped dump (<foo>…</foo>). Null for prose. */
internal fun summarizeTagPayload(trimmed: String): String? {
    val m = WholeTagPayload.matchEntire(trimmed) ?: return null
    return "markup: <${m.groupValues[1]}>"
}

internal fun splitUserFacing(text: String): VisibleSplit {
    val untrusted = splitUntrustedBlocks(text)
    val folded = untrusted.logs.map { block ->
        Folded(summarizeToolBlock(block), untrustedDetail(block))
    }.toMutableList()
    var rest = stripToolPrefix(untrusted.visible)
    summarizeMachineJson(rest.trim())?.let { summary ->
        folded.add(Folded(summary, prettyMachineJson(rest.trim())))
        rest = ""
    }
    summarizeTagPayload(rest.trim())?.let { summary ->
        folded.add(Folded(summary, rest.trim().take(1000)))
        rest = ""
    }
    if (rest.isNotBlank()) {
        // Machine JSON embedded mid-prose (agent pasted a tool dump into the
        // reply) folds too — except inside fenced code samples, which stay.
        val embedded = foldEmbeddedJson(rest)
        if (embedded.second.isNotEmpty()) {
            rest = embedded.first
            folded.addAll(embedded.second)
        }
    }
    return VisibleSplit(rest, folded)
}

/** Pretty, capped rendering of a machine payload for the expanded activity view. */
internal fun prettyMachineJson(trimmed: String, max: Int = 1500): String {
    val el = try {
        HermesJson.parseToJsonElement(trimmed)
    } catch (_: Exception) {
        return trimmed.take(max)
    }
    if (el !is JsonObject && el !is JsonArray) return trimmed.take(max)
    val pretty = try {
        PrettyJson.encodeToString(JsonElement.serializer(), el)
    } catch (_: Exception) {
        return trimmed.take(max)
    }
    return if (pretty.length <= max) pretty else pretty.take(max) + "…"
}

private val PrettyJson = Json { prettyPrint = true }

/** Readable inner text of an untrusted block (boilerplate and tags stripped). */
internal fun untrustedDetail(block: String, max: Int = 1500): String {
    val inner = AnyMarkupTag.replace(TrustBoilerplate.replace(block, " "), " ")
        .split(WhitespaceRun).filter { it.isNotEmpty() }.joinToString(" ")
    return if (inner.length <= max) inner else inner.take(max) + "…"
}

/**
 * Fold machine-JSON payloads embedded mid-prose (agent pasted a tool dump
 * into the reply). Fenced code samples are left alone — those are docs.
 * Returns cleaned text plus folded entries.
 */
internal fun foldEmbeddedJson(text: String): Pair<String, List<Folded>> {
    val lines = text.split('\n')
    val kept = mutableListOf<String>()
    val folded = mutableListOf<Folded>()
    var inFence = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            kept.add(line)
            i++
            continue
        }
        val trimmed = line.trimStart()
        if (!inFence && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
            val (segment, end) = takeBalanced(lines, i)
            val summary = segment?.let { summarizeMachineJson(it.trim()) }
            if (segment != null && summary != null) {
                folded.add(Folded(summary, prettyMachineJson(segment.trim())))
                i = end + 1
                continue
            }
        }
        kept.add(line)
        i++
    }
    return kept.joinToString("\n") to folded
}

/** Balanced {...}/[...] span starting at lines[from]. Null when it never balances. */
internal fun takeBalanced(lines: List<String>, from: Int): Pair<String?, Int> {
    val sb = StringBuilder()
    var depth = 0
    var inStr = false
    var esc = false
    for (j in from until lines.size) {
        val line = lines[j]
        if (line.trimStart().startsWith("```")) break
        for (ch in line) {
            if (inStr) {
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> inStr = false
                }
            } else {
                when (ch) {
                    '"' -> inStr = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
            }
        }
        sb.append(line).append('\n')
        if (depth == 0) return sb.toString().trimEnd() to j
        if (depth < 0) break
    }
    return null to from
}

/** Rendered activity-log entry: summary line first, detail below for the expanded view. */
internal fun Folded.render(): String = if (detail.isBlank()) summary else "$summary\n$detail"

/** Single folded line for machine-role content (history tool/system messages, reasoning). */
internal fun foldForLog(text: String): String {
    val split = splitUserFacing(text)
    return (listOf(split.visible.trim()).filter { it.isNotEmpty() } + split.folded.map { it.summary })
        .joinToString(" · ").take(200)
}

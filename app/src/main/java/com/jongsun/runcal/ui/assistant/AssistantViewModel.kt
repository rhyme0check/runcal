package com.jongsun.runcal.ui.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jongsun.runcal.ai.AiPrefs
import com.jongsun.runcal.ai.AiScope
import com.jongsun.runcal.ai.AssistantDataSource
import com.jongsun.runcal.ai.AssistantEngine
import com.jongsun.runcal.ai.AssistantException
import com.jongsun.runcal.ai.ChangeLog
import com.jongsun.runcal.ai.ChoiceRequest
import com.jongsun.runcal.ai.GeminiClient
import com.jongsun.runcal.ai.ProposalItem
import com.jongsun.runcal.data.CalendarInfo
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import java.time.DayOfWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ChatRole { USER, ASSISTANT, ERROR }

enum class CardStatus { PENDING, EXECUTED, PARTIAL, FAILED, CANCELLED, UNDONE }

/** 확인 카드 상태. [scopes]/[calendars]는 카드에서 사용자가 고르는 값이다. */
data class ProposalCardState(
    val items: List<ProposalItem>,
    val scopes: Map<String, AiScope?>,
    val calendars: Map<String, Long?>,
    val impacts: Map<String, String>,
    val status: CardStatus = CardStatus.PENDING,
    val resultLines: List<String> = emptyList(),
    /** 이 시각까지 "실행 취소"를 누를 수 있다. null이면 되돌릴 수 없는(또는 되돌릴 게 없는) 상태. */
    val undoUntilMillis: Long? = null,
    val undoNote: String? = null,
)

enum class ChoiceStatus { PENDING, CHOSEN, CANCELLED }

/** 후보 선택 카드 상태. 후보 목록은 앱이 실제 제목으로 보여준다. */
data class ChoiceState(val request: ChoiceRequest, val status: ChoiceStatus = ChoiceStatus.PENDING, val chosenRef: String? = null)

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val events: List<EventItem> = emptyList(),
    val card: ProposalCardState? = null,
    val choice: ChoiceState? = null,
)

private const val UNDO_WINDOW_MILLIS = 10_000L
private const val YEAR_MILLIS = 365L * 24 * 60 * 60 * 1000

/**
 * 대화 상태(메모리 전용 — 디스크에 저장하지 않고 백업 대상도 아님). 쓰기는 확인 카드에서 [confirm]을 눌렀을 때만
 * 실행되고, 실행 결과는 로컬 변경 기록([ChangeLog])에 남는다.
 */
class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var engine: AssistantEngine? = null
    private var nextId = 1L
    private val undoActions = HashMap<Long, List<suspend () -> Boolean>>()

    fun send(text: String, calendarViewModel: CalendarViewModel) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        // 아직 확인 대기 중인 카드는 새 명령이 들어오면 취소로 처리한다(엔진도 같은 방식으로 정리한다).
        cancelPendingCards("새 명령을 보내 취소됨")
        append(ChatRole.USER, trimmed)
        _busy.value = true
        viewModelScope.launch {
            try {
                val active = activeEngine(calendarViewModel)
                handleReply(active.send(trimmed, AiPrefs.current(getApplication()).model), calendarViewModel)
            } catch (e: AssistantException) {
                append(ChatRole.ERROR, e.userMessage)
            } catch (e: Exception) {
                append(ChatRole.ERROR, "요청을 처리하지 못했습니다. 다시 시도해 주세요.")
            } finally {
                _busy.value = false
            }
        }
    }

    /** 엔진 응답을 화면 메시지로 바꾼다(확인 카드·후보 선택 카드 포함). */
    private suspend fun handleReply(reply: com.jongsun.runcal.ai.AssistantReply, vm: CalendarViewModel) {
        val card = if (reply.proposals.isNotEmpty()) {
            ProposalCardState(
                items = reply.proposals,
                scopes = reply.proposals.associate { it.callKey to it.scope },
                calendars = reply.proposals.associate { it.callKey to it.calendarId },
                impacts = reply.proposals.mapNotNull { p -> impactFor(vm, p)?.let { p.callKey to it } }.toMap(),
            )
        } else {
            null
        }
        val choice = reply.choice?.let { ChoiceState(it) }
        append(ChatRole.ASSISTANT, reply.text, if (choice != null) emptyList() else reply.events, card, choice)
    }

    /** 후보 목록에서 사용자가 하나를 골랐다. 선택 결과를 모델에게 넘겨 원래 명령(수정·삭제)을 이어간다. */
    fun choose(messageId: Long, ref: String, calendarViewModel: CalendarViewModel) {
        val state = _messages.value.firstOrNull { it.id == messageId }?.choice ?: return
        if (state.status != ChoiceStatus.PENDING || _busy.value) return
        val active = engine ?: return
        active.resolve(state.request.callKey, buildJsonObject { put("status", "chosen"); put("chosenRef", ref) })
        updateChoice(messageId) { it.copy(status = ChoiceStatus.CHOSEN, chosenRef = ref) }
        _busy.value = true
        viewModelScope.launch {
            try {
                handleReply(active.continueAfterChoice(AiPrefs.current(getApplication()).model), calendarViewModel)
            } catch (e: AssistantException) {
                append(ChatRole.ERROR, e.userMessage)
            } catch (e: Exception) {
                append(ChatRole.ERROR, "요청을 처리하지 못했습니다. 다시 시도해 주세요.")
            } finally {
                _busy.value = false
            }
        }
    }

    /** 후보 중 원하는 것이 없다 — 모델을 다시 부르지 않고 접는다(다음 입력에 결과가 함께 전달된다). */
    fun cancelChoice(messageId: Long) {
        val state = _messages.value.firstOrNull { it.id == messageId }?.choice ?: return
        if (state.status != ChoiceStatus.PENDING) return
        engine?.resolve(state.request.callKey, status("cancelled", "사용자가 후보 중 원하는 것이 없다고 했다"))
        updateChoice(messageId) { it.copy(status = ChoiceStatus.CANCELLED) }
    }

    private fun updateChoice(messageId: Long, transform: (ChoiceState) -> ChoiceState) {
        _messages.update { list -> list.map { m -> if (m.id == messageId && m.choice != null) m.copy(choice = transform(m.choice)) else m } }
    }

    fun setScope(messageId: Long, callKey: String, scope: AiScope) = updateCard(messageId) { it.copy(scopes = it.scopes + (callKey to scope)) }

    fun setCalendar(messageId: Long, callKey: String, calendarId: Long) = updateCard(messageId) { it.copy(calendars = it.calendars + (callKey to calendarId)) }

    /** 사용자가 [실행]을 누른 뒤에만 호출된다. 항목을 차례로 실행하고, 하나라도 실패하면 거기서 멈춘다. */
    fun confirm(messageId: Long, calendarViewModel: CalendarViewModel) {
        val card = _messages.value.firstOrNull { it.id == messageId }?.card ?: return
        if (card.status != CardStatus.PENDING || _busy.value) return
        // 반복 일정은 범위를 골라야만 실행할 수 있다(화면에서도 막지만 여기서도 확인한다).
        if (card.items.any { it.needsScope() && card.scopes[it.callKey] == null }) return
        val active = engine ?: return
        _busy.value = true
        viewModelScope.launch {
            val mutator = AssistantMutator(calendarViewModel, active.describer)
            val lines = ArrayList<String>()
            val undos = ArrayList<suspend () -> Boolean>()
            var allUndoable = true
            var executed = 0
            var failed = false
            for (item in card.items) {
                if (failed) {
                    active.resolve(item.callKey, status("cancelled", "앞선 항목이 실패해 실행하지 않았다"))
                    continue
                }
                val outcome = try {
                    mutator.execute(item, card.calendars[item.callKey] ?: item.calendarId, card.scopes[item.callKey])
                } catch (e: Exception) {
                    MutationOutcome(false, "실행 중 오류가 났습니다", "실패: 실행 중 오류")
                }
                ChangeLog.append(getApplication(), if (outcome.ok) "실행" else "실패", outcome.logSummary)
                lines += (if (outcome.ok) "✔ " else "✖ ") + outcome.message
                if (outcome.ok) {
                    executed++
                    outcome.undo?.let { undos += it } ?: run { allUndoable = false }
                    val done = buildJsonObject {
                        put("status", "done")
                        outcome.createdId?.let { id ->
                            calendarViewModel.getEventDetail(id)?.let { put("createdRef", active.registerEvent(it)) }
                        }
                    }
                    active.resolve(item.callKey, done)
                } else {
                    failed = true
                    active.resolve(item.callKey, status("failed", outcome.message))
                }
            }
            val canUndo = executed > 0 && !failed && allUndoable
            if (canUndo) undoActions[messageId] = undos
            updateCard(messageId) {
                it.copy(
                    status = when {
                        !failed -> CardStatus.EXECUTED
                        executed > 0 -> CardStatus.PARTIAL
                        else -> CardStatus.FAILED
                    },
                    resultLines = lines,
                    undoUntilMillis = if (canUndo) System.currentTimeMillis() + UNDO_WINDOW_MILLIS else null,
                    undoNote = if (executed > 0 && !canUndo) "이 변경은 되돌리기를 지원하지 않아 변경 기록에만 남깁니다." else null,
                )
            }
            _busy.value = false
        }
    }

    fun cancel(messageId: Long) {
        val card = _messages.value.firstOrNull { it.id == messageId }?.card ?: return
        if (card.status != CardStatus.PENDING) return
        card.items.forEach { engine?.resolve(it.callKey, status("cancelled", "사용자가 취소했다")) }
        ChangeLog.append(getApplication(), "취소", card.items.joinToString(" / ") { summaryOf(it) })
        updateCard(messageId) { it.copy(status = CardStatus.CANCELLED) }
    }

    /** 실행 직후 [UNDO_WINDOW_MILLIS] 안에서만 가능하다. */
    fun undo(messageId: Long) {
        val card = _messages.value.firstOrNull { it.id == messageId }?.card ?: return
        val until = card.undoUntilMillis ?: return
        if (card.status != CardStatus.EXECUTED || System.currentTimeMillis() > until) return
        val actions = undoActions.remove(messageId) ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            var ok = true
            for (action in actions.asReversed()) {
                ok = runCatching { action() }.getOrDefault(false) && ok
            }
            ChangeLog.append(getApplication(), if (ok) "되돌림" else "되돌림 실패", card.resultLines.joinToString(" / "))
            updateCard(messageId) {
                it.copy(
                    status = if (ok) CardStatus.UNDONE else it.status,
                    undoUntilMillis = null,
                    undoNote = if (ok) "실행을 되돌렸습니다." else "되돌리지 못했습니다. 변경 기록을 확인하세요.",
                )
            }
            _busy.value = false
        }
    }

    fun changeLog() = ChangeLog.recent(getApplication())

    fun newConversation() {
        engine?.reset()
        undoActions.clear()
        _messages.value = emptyList()
    }

    private fun ProposalItem.needsScope(): Boolean = !target?.rrule.isNullOrBlank() && kind != com.jongsun.runcal.ai.ProposalKind.CREATE

    private fun summaryOf(item: ProposalItem): String = when (item.kind) {
        com.jongsun.runcal.ai.ProposalKind.CREATE -> "추가 제안: '${item.title}'"
        com.jongsun.runcal.ai.ProposalKind.UPDATE -> "수정 제안: '${item.target?.title}'"
        com.jongsun.runcal.ai.ProposalKind.DELETE -> "삭제 제안: '${item.target?.title}'"
    }

    private fun status(status: String, reason: String): JsonObject = buildJsonObject { put("status", status); put("reason", reason) }

    private fun cancelPendingCards(reason: String) {
        _messages.value.filter { it.choice?.status == ChoiceStatus.PENDING }.forEach { message ->
            updateChoice(message.id) { it.copy(status = ChoiceStatus.CANCELLED) }
        }
        _messages.value.filter { it.card?.status == CardStatus.PENDING }.forEach { message ->
            ChangeLog.append(getApplication(), "취소", (message.card?.items.orEmpty()).joinToString(" / ") { summaryOf(it) } + " ($reason)")
            updateCard(message.id) { it.copy(status = CardStatus.CANCELLED) }
        }
    }

    /** 반복 일정 삭제·수정 카드에 보여줄 영향 범위 문구. */
    private suspend fun impactFor(vm: CalendarViewModel, item: ProposalItem): String? {
        val target = item.target ?: return null
        val rrule = target.rrule
        if (rrule.isNullOrBlank()) return null
        val count = runCatching {
            vm.eventsInRange(target.begin, target.begin + YEAR_MILLIS).count { it.id == target.id && it.begin >= target.begin }
        }.getOrDefault(0)
        val bounded = rrule.contains("COUNT=") || rrule.contains("UNTIL=")
        return if (bounded) "이 회차부터 끝까지 ${count}회" else "종료 없는 반복 — 이 회차부터 향후 1년 기준 ${count}회(그 이후로도 계속)"
    }

    private fun updateCard(messageId: Long, transform: (ProposalCardState) -> ProposalCardState) {
        _messages.update { list -> list.map { m -> if (m.id == messageId && m.card != null) m.copy(card = transform(m.card)) else m } }
    }

    private fun activeEngine(vm: CalendarViewModel): AssistantEngine =
        engine ?: AssistantEngine(GeminiClient(), dataSourceFor(vm)).also { engine = it }

    private fun append(role: ChatRole, text: String, events: List<EventItem> = emptyList(), card: ProposalCardState? = null, choice: ChoiceState? = null) {
        _messages.update { it + ChatMessage(nextId++, role, text, events, card, choice) }
    }

    private fun dataSourceFor(vm: CalendarViewModel) = object : AssistantDataSource {
        override suspend fun eventsInRange(startMillis: Long, endMillis: Long) = vm.eventsInRange(startMillis, endMillis)
        override fun calendars(): List<CalendarInfo> = vm.calendars.value
        override fun weekStartDay(): DayOfWeek = vm.weekStartDay.value
        override fun defaultReminderMinutes(): List<Int> = vm.defaultReminderMinutes.value?.let { listOf(it) } ?: emptyList()
    }
}

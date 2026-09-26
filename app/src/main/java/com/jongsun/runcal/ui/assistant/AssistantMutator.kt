package com.jongsun.runcal.ui.assistant

import android.provider.CalendarContract
import com.jongsun.runcal.ai.AiScope
import com.jongsun.runcal.ai.ProposalBuilder
import com.jongsun.runcal.ai.ProposalItem
import com.jongsun.runcal.ai.ProposalKind
import com.jongsun.runcal.data.EventItem
import com.jongsun.runcal.data.parseRRule
import com.jongsun.runcal.data.toRRuleString
import com.jongsun.runcal.ui.calendar.CalendarViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 실행 결과. [undo]가 null이면 되돌릴 수 없는 변경이다(기록만 남긴다). [createdId]는 새로 만든 일정 id. */
data class MutationOutcome(
    val ok: Boolean,
    val message: String,
    val logSummary: String,
    val undo: (suspend () -> Boolean)? = null,
    val createdId: Long? = null,
)

/**
 * 확인된 제안을 실제로 실행한다. 새 저장 경로를 만들지 않고, 편집 화면(EventEditDialog)이 쓰는 [CalendarViewModel]의
 * 같은 함수들만 호출한다(캐시 갱신·위젯 갱신·알림 재등록이 그대로 따라온다). 종일=UTC 자정, "전체" 범위의 시리즈 이동(델타)
 * 등 반복 일정 규칙도 편집 화면과 같게 맞췄다. 이 클래스는 사용자가 [실행]을 누른 뒤에만 호출된다.
 */
class AssistantMutator(private val vm: CalendarViewModel, private val describe: ProposalBuilder) {
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun isLocalCalendar(calendarId: Long?): Boolean =
        vm.calendars.value.firstOrNull { it.id == calendarId }?.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL

    suspend fun execute(item: ProposalItem, calendarId: Long?, scope: AiScope?): MutationOutcome = when (item.kind) {
        ProposalKind.CREATE -> create(item, calendarId)
        ProposalKind.UPDATE -> update(item, scope)
        ProposalKind.DELETE -> delete(item, scope)
    }

    private suspend fun create(item: ProposalItem, calendarId: Long?): MutationOutcome {
        val cal = calendarId ?: return fail("추가할 캘린더가 선택되지 않았습니다")
        val id = vm.createLocalEvent(cal, item.title, item.startMillis, item.endMillis, item.allDay, "", "", item.reminderMinutes.orEmpty(), item.rrule)
        if (id <= 0) return fail("일정을 추가하지 못했습니다")
        val range = describe.describeRange(item.allDay, item.startMillis, item.endMillis)
        return MutationOutcome(
            ok = true,
            message = "추가했습니다: '${item.title}' · $range",
            logSummary = "추가: '${item.title}' · $range" + (item.recurrenceLabel?.let { " · $it" } ?: ""),
            undo = { vm.deleteLocalEvent(id) > 0 },
            createdId = id,
        )
    }

    private suspend fun update(item: ProposalItem, scope: AiScope?): MutationOutcome {
        val target = item.target ?: return fail("대상 일정이 없습니다")
        val master = vm.getEventDetail(target.id) ?: return fail("일정을 다시 읽지 못했습니다(이미 삭제됐을 수 있습니다)")
        val oldReminders = vm.getReminders(target.id)
        val reminders = item.reminderMinutes ?: oldReminders
        val recurring = !target.rrule.isNullOrBlank()
        val summary = "수정: '${target.title}' · " + item.changeLines.joinToString(" / ") + scopeSuffix(recurring, scope)

        if (!recurring) {
            val updated = vm.updateLocalEvent(target.id, item.title, item.startMillis, item.endMillis, target.allDay, master.location, master.description, reminders, null)
            if (updated <= 0) return fail("수정하지 못했습니다")
            return MutationOutcome(true, "수정했습니다: '${item.title}'", summary, undo = restoreMaster(master, oldReminders))
        }
        return when (scope) {
            AiScope.THIS_ONLY -> {
                if (isLocalCalendar(target.calendarId)) return fail("이 캘린더는 기기 안에만 있는 캘린더라 '이번만' 수정을 지원하지 않습니다. '이후 전체' 또는 '전체'를 선택하세요")
                val id = vm.createSingleOccurrenceException(
                    masterEventId = target.id, originalInstanceBeginMillis = target.begin, title = item.title,
                    startMillis = item.startMillis, endMillis = item.endMillis, allDay = target.allDay,
                    location = master.location, description = master.description, reminderMinutes = reminders,
                )
                if (id <= 0) fail("이번 회차만 수정하지 못했습니다") else MutationOutcome(true, "이번 회차만 수정했습니다: '${item.title}'", summary)
            }
            AiScope.THIS_AND_FOLLOWING -> {
                val anchor = Instant.ofEpochMilli(item.startMillis).atZone(if (target.allDay) ZoneOffset.UTC else zone).toLocalDate()
                val ok = vm.updateFollowingOccurrences(
                    masterEventId = target.id, masterAllDay = target.allDay, masterRrule = target.rrule.orEmpty(),
                    splitInstanceBeginMillis = target.begin, calendarId = target.calendarId, title = item.title,
                    startMillis = item.startMillis, endMillis = item.endMillis, allDay = target.allDay,
                    location = master.location, description = master.description, reminderMinutes = reminders,
                    newRrule = parseRRule(target.rrule).toRRuleString(anchor),
                ) > 0
                if (!ok) fail("이후 회차를 수정하지 못했습니다") else MutationOutcome(true, "이 회차부터 수정했습니다: '${item.title}'", summary)
            }
            AiScope.ALL -> {
                val newMasterStart = master.begin + (item.startMillis - target.begin)
                val newMasterEnd = master.end + (item.endMillis - target.end)
                val anchor = Instant.ofEpochMilli(newMasterStart).atZone(if (target.allDay) ZoneOffset.UTC else zone).toLocalDate()
                val updated = vm.updateLocalEvent(
                    target.id, item.title, newMasterStart, newMasterEnd, target.allDay, master.location, master.description,
                    reminders, parseRRule(target.rrule).toRRuleString(anchor),
                )
                if (updated <= 0) fail("전체 일정을 수정하지 못했습니다") else MutationOutcome(true, "전체 반복 일정을 수정했습니다: '${item.title}'", summary, undo = restoreMaster(master, oldReminders))
            }
            null -> fail("반복 일정의 적용 범위가 선택되지 않았습니다")
        }
    }

    private suspend fun delete(item: ProposalItem, scope: AiScope?): MutationOutcome {
        val target = item.target ?: return fail("대상 일정이 없습니다")
        val master = vm.getEventDetail(target.id)
        val oldReminders = vm.getReminders(target.id)
        val recurring = !target.rrule.isNullOrBlank()
        val range = describe.describeRange(target.allDay, target.begin, target.end)
        val summary = "삭제: '${target.title}' · $range" + scopeSuffix(recurring, scope)

        if (!recurring || scope == AiScope.ALL) {
            val src = master ?: return fail("일정을 다시 읽지 못했습니다(이미 삭제됐을 수 있습니다)")
            if (vm.deleteLocalEvent(target.id) <= 0) return fail("삭제하지 못했습니다")
            return MutationOutcome(true, "삭제했습니다: '${target.title}'", summary, undo = recreate(src, oldReminders, target.calendarId))
        }
        return when (scope) {
            AiScope.THIS_ONLY -> {
                if (isLocalCalendar(target.calendarId)) return fail("이 캘린더는 기기 안에만 있는 캘린더라 '이번만' 삭제를 지원하지 않습니다. '이후 전체' 또는 '전체'를 선택하세요")
                if (vm.deleteSingleOccurrence(target.id, target.begin) <= 0) fail("이번 회차를 삭제하지 못했습니다") else MutationOutcome(true, "이번 회차만 삭제했습니다: '${target.title}'", summary)
            }
            AiScope.THIS_AND_FOLLOWING -> {
                val ok = vm.deleteFollowingOccurrences(target.id, target.allDay, target.rrule.orEmpty(), target.begin)
                if (!ok) fail("이후 회차를 삭제하지 못했습니다") else MutationOutcome(true, "이 회차부터 삭제했습니다: '${target.title}'", summary)
            }
            else -> fail("반복 일정의 적용 범위가 선택되지 않았습니다")
        }
    }

    /** 수정 전 값으로 되돌린다(마스터 스냅샷 기준). */
    private fun restoreMaster(master: EventItem, oldReminders: List<Int>): suspend () -> Boolean = {
        vm.updateLocalEvent(master.id, master.title, master.begin, master.end, master.allDay, master.location, master.description, oldReminders, master.rrule) > 0
    }

    /** 삭제를 되돌린다 — 같은 내용으로 다시 만든다(id는 새로 발급되고, 반복 예외는 복원되지 않는다). */
    private fun recreate(master: EventItem, oldReminders: List<Int>, calendarId: Long): suspend () -> Boolean = {
        vm.createLocalEvent(calendarId, master.title, master.begin, master.end, master.allDay, master.location, master.description, oldReminders, master.rrule) > 0
    }

    private fun scopeSuffix(recurring: Boolean, scope: AiScope?): String = if (!recurring) "" else when (scope) {
        AiScope.THIS_ONLY -> " · 이번만"
        AiScope.THIS_AND_FOLLOWING -> " · 이후 전체"
        AiScope.ALL -> " · 전체"
        null -> ""
    }

    private fun fail(message: String) = MutationOutcome(false, message, "실패: $message")
}

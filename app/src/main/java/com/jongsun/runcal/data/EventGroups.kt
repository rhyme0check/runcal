package com.jongsun.runcal.data

import android.content.Context
import com.jongsun.runcal.data.room.EventTypeEntity
import com.jongsun.runcal.data.room.RunCalDatabase
import com.jongsun.runcal.data.source.EventSourceKind

/** 제목 규칙 문자열("RP, RACE")을 비교용 대문자 목록으로. */
fun parseTitleKeywords(text: String): List<String> =
    text.split(',', '，', '/').map { it.trim().uppercase() }.filter { it.isNotEmpty() }

/** 제목 앞의 이모지·기호·공백을 걷어낸 대문자 제목("🏃 EASY 8km" → "EASY 8KM"). */
fun normalizedTitleForRules(title: String): String = title.trimStart { !it.isLetterOrDigit() }.uppercase()

/**
 * 일정 → 일정그룹 판정. 직접 지정한 것이 먼저이고(캘린더 일정은 저장할 때 고른 그룹, Notion 항목은 화면에서 고른 그룹),
 * 없으면 그룹 목록 순서대로 제목 규칙(앞글자 일치)을 본다. Notion 항목에 "그룹 없음"을 직접 고르면 규칙도 보지 않는다.
 */
class EventGroupIndex(
    groups: List<EventTypeEntity>,
    private val calendarAssignments: Map<Long, String>,
    private val notionAssignments: Map<String, String>,
) {
    private val byId = groups.associateBy { it.id }
    private val rules: List<Pair<EventTypeEntity, List<String>>> =
        groups.sortedBy { it.sortOrder }.map { it to parseTitleKeywords(it.titleKeywords) }.filter { it.second.isNotEmpty() }

    fun groupOf(event: EventItem): EventTypeEntity? {
        when (event.sourceKind) {
            EventSourceKind.CALENDAR -> calendarAssignments[event.id]?.let { id -> byId[id]?.let { return it } }
            EventSourceKind.NOTION -> notionAssignments[notionKey(event.notionDatabaseId, event.notionPageId)]?.let { id ->
                if (id.isEmpty()) return null
                byId[id]?.let { return it }
            }
        }
        return matchRules(event.title)
    }

    /** Notion 항목에 직접 정한 그룹 id. null=직접 정하지 않음(규칙 적용), ""=그룹 없음. */
    fun notionManualGroupId(event: EventItem): String? = notionAssignments[notionKey(event.notionDatabaseId, event.notionPageId)]

    /** 제목 규칙만으로 판정한 그룹(화면에 "자동: …"으로 보여 줄 때). */
    fun matchRules(title: String): EventTypeEntity? {
        if (rules.isEmpty()) return null
        val normalized = normalizedTitleForRules(title)
        return rules.firstOrNull { (_, keywords) -> keywords.any { normalized.startsWith(it) } }?.first
    }

    companion object {
        fun notionKey(registrationId: String?, pageId: String?): String = "${registrationId.orEmpty()}:${pageId.orEmpty()}"
        val EMPTY = EventGroupIndex(emptyList(), emptyMap(), emptyMap())
    }
}

/**
 * [EventGroupIndex]의 프로세스 메모리 캐시. 위젯 렌더링 경로에서도 쓰므로 Room은 처음 한 번만 읽고,
 * 그룹·직접 지정이 바뀌는 곳에서 [invalidate]한다.
 */
object EventGroups {
    @Volatile private var cached: EventGroupIndex? = null

    suspend fun index(context: Context): EventGroupIndex {
        cached?.let { return it }
        val dao = RunCalDatabase.getInstance(context).eventTypeDao()
        val built = EventGroupIndex(
            groups = dao.getAll(),
            calendarAssignments = dao.allAssignments().associate { it.eventId to it.typeId },
            notionAssignments = dao.allNotionAssignments().associate { EventGroupIndex.notionKey(it.registrationId, it.pageId) to it.typeId },
        )
        cached = built
        return built
    }

    fun invalidate() {
        cached = null
    }
}

/**
 * 일정에 그룹과 "그릴 색"을 붙인다. 색 우선순위: [overrideColor](지금 보고 있는 프리셋이 색 덮어쓰기를 켠 경우) →
 * 그룹 색 → 일정에 직접 지정한 색 → 캘린더(소스) 색. 앞의 둘은 [EventItem.displayColor]에, 뒤의 둘은 기존 필드에 있다.
 */
fun EventItem.withGroup(index: EventGroupIndex, overrideColor: Int?): EventItem {
    val group = index.groupOf(this)
    val display = overrideColor ?: group?.colorArgb
    return if (group?.id == groupId && display == displayColor) this else copy(groupId = group?.id, displayColor = display)
}

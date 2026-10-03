package com.jongsun.runcal.data

import com.jongsun.runcal.data.source.EventSourceKind

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val color: Int,
    val visible: Boolean,
    // CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR 여부.
    // 편집 화면의 "소속 캘린더" 선택지를 여기서 걸러서, 쓰기 실패로 이어질 읽기 전용 캘린더를
    // 애초에 고를 수 없게 한다.
    val isWritable: Boolean = true,
    // CalendarContract.Calendars.ACCOUNT_TYPE. ACCOUNT_TYPE_LOCAL(동기화 어댑터 없음)인
    // 캘린더는 실측으로 확인된 CalendarProvider 결함 때문에 반복 일정 예외("이번만") 처리 시
    // 예외 이전 회차가 사라진다 — 편집 화면이 이 값으로 "이번만" 옵션을 감춘다.
    val accountType: String = "",
    // CalendarContract.Calendars.OWNER_ACCOUNT / IS_PRIMARY. 새 프리셋의 기본 선택(구글 메인 캘린더,
    // 공휴일 캘린더)을 고를 때 쓴다 — 표시 이름은 사용자가 바꿀 수 있어 판단 근거로 쓰지 않는다.
    val ownerAccount: String = "",
    val isPrimary: Boolean = false,
)

/**
 * 이름 아래에 작게 보여줄 계정 표시. 기기마다 같은 이름(예: "대한민국의 휴일")의 캘린더가 여러 계정에 있을 수 있어
 * 이름만으로는 구분이 안 된다. 계정 이름이 캘린더 이름과 같으면 굳이 반복하지 않는다.
 */
fun CalendarInfo.accountLabel(): String = when {
    accountType == android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL && accountName == CalendarRepository.LOCAL_ACCOUNT_NAME -> "RunCal(이 기기)"
    accountName.isBlank() || accountName == displayName -> ""
    else -> accountName
}

/** 칩·메뉴처럼 한 줄만 쓸 수 있는 곳의 이름. 같은 이름이 [all] 안에 또 있으면 계정을 괄호로 붙인다. */
fun CalendarInfo.distinctName(all: List<CalendarInfo>): String {
    val duplicated = all.count { it.displayName == displayName } > 1
    val account = accountLabel()
    return if (duplicated && account.isNotEmpty()) "$displayName ($account)" else displayName
}

/**
 * 새 프리셋을 만들 때 기본으로 체크해 둘 캘린더: RunCal 로컬 캘린더, 구글 계정의 메인 캘린더, 한국 공휴일 캘린더.
 * (공유받은 캘린더·다른 나라 공휴일 등은 기본 해제 — 매번 수동으로 빼는 수고를 없앤다.) 하나도 못 찾으면 전체.
 */
fun defaultPresetCalendarIds(calendars: List<CalendarInfo>): Set<Long> {
    val picked = calendars.filter { it.isRunCalLocal() || it.isGooglePrimary() || it.isKoreanHoliday() }.map { it.id }.toSet()
    return picked.ifEmpty { calendars.map { it.id }.toSet() }
}

fun CalendarInfo.isRunCalLocal(): Boolean =
    accountType == android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL && accountName == CalendarRepository.LOCAL_ACCOUNT_NAME

fun CalendarInfo.isGooglePrimary(): Boolean =
    accountType == "com.google" && (isPrimary || ownerAccount.equals(accountName, ignoreCase = true))

/** 구글 공휴일 캘린더는 소유 계정이 "<언어>.south_korea#holiday@group.v.calendar.google.com" 형식이다. */
fun CalendarInfo.isKoreanHoliday(): Boolean {
    val owner = ownerAccount.lowercase()
    return owner.contains("#holiday@") && owner.contains("south_korea")
}

data class EventItem(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String = "",
    // 이하 4개는 Notion 연동을 위한 신규 필드. 기본값이 있어 기존 호출부는 전부 그대로 컴파일된다.
    val sourceKind: EventSourceKind = EventSourceKind.CALENDAR,
    val notionPageId: String? = null,
    val notionDatabaseId: String? = null,
    val notionStatus: String? = null,
    // P2(편집) 신규 필드.
    val description: String = "",
    // Notion 항목 전용 — 읽기 전용 편집 화면의 "Notion에서 열기" 링크에 쓴다.
    val notionUrl: String? = null,
    // P2-B(반복 일정) 신규 필드. RFC5545 RRULE 원문(예: "FREQ=WEEKLY;BYDAY=MO,WE"). 반복이
    // 아니면 null/빈 문자열 — CalendarContract.Instances는 이 값을 회차마다 그대로 복제해서
    // 돌려주므로, 어떤 회차를 눌러도 같은 반복 규칙을 읽을 수 있다.
    val rrule: String? = null,
    // 일정에 직접 지정한 색(CalendarContract.Events.EVENT_COLOR). null이면 캘린더(소스) 색을 따른다.
    val eventColor: Int? = null,
)

package com.jongsun.runcal.data

data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val color: Int,
    val visible: Boolean,
)

data class EventItem(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
)

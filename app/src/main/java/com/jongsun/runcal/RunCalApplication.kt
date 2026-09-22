package com.jongsun.runcal

import android.app.Application

/**
 * 캘린더 ContentObserver는 여기서 등록하지 않는다.
 * Application.onCreate는 런타임 권한 요청보다 먼저 실행되므로, 이 시점에는 READ_CALENDAR 권한이
 * 없을 수 있어 registerContentObserver 호출 시 SecurityException으로 크래시가 발생할 수 있다.
 * 대신 [CalendarObserverManager.register]를 권한이 확인된 시점(권한 승인 콜백, MainActivity
 * onCreate에서 권한이 이미 있는 경우)에 호출한다.
 */
class RunCalApplication : Application()

package com.jongsun.runcal.data.network

import okhttp3.OkHttpClient

/**
 * 프로세스 전체에서 공유하는 단일 OkHttpClient. Notion/Drive API 클라이언트가 각자 새
 * OkHttpClient()를 만들면 호출부마다 TCP/TLS 커넥션을 새로 맺어야 해서 첫 요청이 느려진다
 * (Notion 스키마 조회에서 실측된 문제, NotionApiClient 참고) — 커넥션 풀을 공유해 이 비용을
 * 한 번만 치르게 한다.
 */
object SharedHttpClient {
    val instance: OkHttpClient by lazy { OkHttpClient() }
}

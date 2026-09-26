package com.jongsun.runcal.ai

import com.jongsun.runcal.data.EventItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val MAX_ROUNDS = 5
private const val MAX_HISTORY = 40
private const val MAX_BATCH = 10

/**
 * 한 번의 명령에 대한 결과. [events]는 앱이 실제 제목으로 그릴 일정들(모델에는 제목이 나가지 않는다).
 * [proposals]가 있으면 아직 아무것도 실행되지 않았고 사용자의 확인을 기다리는 상태다. [notices]는 앱이 직접 만든 안내문.
 */
data class AssistantReply(
    val text: String,
    val events: List<EventItem>,
    val proposals: List<ProposalItem> = emptyList(),
    val notices: List<String> = emptyList(),
    val choice: ChoiceRequest? = null,
)

/** 대상이 여러 후보일 때 모델이 사용자에게 고르게 요청한 것. 후보는 앱이 실제 제목으로 보여준다. */
data class ChoiceRequest(val callKey: String, val question: String, val candidates: List<Pair<String, EventItem>>)

/**
 * 자연어 명령 → (Gemini 함수 호출 ↔ 앱의 로컬 실행) 루프.
 *
 * 쓰기 함수(createEvent/updateEvent/deleteEvent)는 여기서 절대 실행되지 않는다. 검증을 통과하면 [ProposalItem]으로
 * 바꿔 돌려주고 루프를 멈춘다 — 실행은 화면에서 사용자가 [실행]을 눌렀을 때 [AssistantViewModel]이 한다.
 * 사용자 결정 결과는 [resolve]로 알려주고, 모델에게는 다음 사용자 입력에 실어 보낸다(추가 호출 없음).
 */
class AssistantEngine(private val client: GeminiClient, private val data: AssistantDataSource) {
    private val labels = LabelBook()
    private val reader = ReadToolExecutor(data, labels)
    private val builder = ProposalBuilder(data, labels)
    private val history = ArrayList<JsonObject>()

    /** 다음 사용자 턴 앞에 붙일, 이미 결과가 정해진 functionResponse 파트들. */
    private val carry = ArrayList<JsonObject>()

    /** 사용자 결정을 기다리는 쓰기 호출들. */
    private val unresolved = LinkedHashMap<String, FunctionCall>()
    private var callSeq = 0

    /** 결과 문구(변경 전 → 후)를 만드는 데 쓰는 포매터. */
    val describer: ProposalBuilder get() = builder

    /** 앱이 새로 만든 일정을 모델이 이후 명령("방금 만든 거 삭제")에서 가리킬 수 있게 라벨을 부여한다. */
    fun registerEvent(event: EventItem): String = labels.eventLabel(event)

    fun reset() {
        history.clear()
        carry.clear()
        unresolved.clear()
        // 새 대화에서는 라벨도 처음부터 다시 매긴다.
        labels.reset()
    }

    /** 사용자가 제안을 실행/취소한 결과를 기록한다. 다음 [send]에서 모델 히스토리에 함께 들어간다. */
    fun resolve(callKey: String, result: JsonObject) {
        val call = unresolved.remove(callKey) ?: return
        carry += responsePart(call, result)
    }

    suspend fun send(userText: String, model: String): AssistantReply = run(model, userText)

    /** 사용자가 후보를 고른 뒤, 그 결과([resolve]로 기록됨)를 모델에게 넘겨 원래 명령을 이어가게 한다. */
    suspend fun continueAfterChoice(model: String): AssistantReply = run(model, null)

    private suspend fun run(model: String, userText: String?): AssistantReply {
        val savedHistory = history.size
        val savedCarry = ArrayList(carry)
        val savedUnresolved = LinkedHashMap(unresolved)
        reader.resetTurn()
        try {
            // 아직 답하지 않은 이전 제안은 새 명령이 들어온 것으로 자동 취소한다(API는 함수 호출마다 응답을 요구한다).
            unresolved.forEach { (_, call) ->
                carry += responsePart(call, buildJsonObject { put("status", "cancelled"); put("reason", "사용자가 새 명령을 보내 이전 제안이 취소됐다") })
            }
            unresolved.clear()
            if (userText != null) {
                history += userTurn(userText, carry)
            } else {
                if (carry.isEmpty()) throw AssistantException("이어갈 내용이 없습니다.")
                history += functionResponseTurn(carry)
            }
            carry.clear()

            repeat(MAX_ROUNDS) {
                val response = client.generate(model, systemPrompt(), history, AssistantToolSpecs.tools)
                history += response.modelContent
                if (response.calls.isEmpty()) {
                    trimHistory()
                    return AssistantReply(response.text.ifBlank { "처리했습니다." }, reader.lastShown)
                }

                val parts = ArrayList<JsonObject>()
                val proposals = ArrayList<ProposalItem>()
                val notices = ArrayList<String>()
                val newUnresolved = LinkedHashMap<String, FunctionCall>()
                var modelErrors = 0
                var reads = 0
                var batchCount = 0
                var choice: ChoiceRequest? = null
                response.calls.forEach { call ->
                    if (call.name == AssistantToolSpecs.CHOOSE) {
                        val key = "c${++callSeq}"
                        val request = buildChoice(key, call.args)
                        if (request == null || choice != null) {
                            parts += responsePart(call, error("후보 ref가 올바르지 않거나 이미 선택 요청이 있습니다. 조회 결과에 있던 ref 2~${MAX_BATCH}개를 candidateRefs로 넘기세요."))
                            modelErrors++
                        } else {
                            choice = request
                            newUnresolved[key] = call
                        }
                    } else if (call.name in AssistantToolSpecs.writeNames) {
                        val key = "c${++callSeq}"
                        if (++batchCount > MAX_BATCH) {
                            parts += responsePart(call, error("한 번에 최대 ${MAX_BATCH}건까지만 제안할 수 있습니다. 범위를 좁혀 다시 요청하게 하세요."))
                            modelErrors++
                            return@forEach
                        }
                        when (val outcome = builder.build(key, call.name, call.args)) {
                            is ProposalOutcome.Ok -> {
                                proposals += outcome.item
                                newUnresolved[key] = call
                            }
                            is ProposalOutcome.ModelError -> {
                                parts += responsePart(call, error(outcome.message))
                                modelErrors++
                            }
                            is ProposalOutcome.Blocked -> {
                                parts += responsePart(call, buildJsonObject { put("status", "blocked"); put("message", "앱이 사용자에게 직접 안내했다") })
                                notices += outcome.userMessage
                            }
                        }
                    } else {
                        parts += responsePart(call, reader.execute(call.name, call.args))
                        reads++
                    }
                }

                val pendingChoice = choice
                if (pendingChoice != null && proposals.isEmpty()) {
                    // 사용자에게 고르게 한다. 지금까지 정해진 응답은 다음 턴 앞에 붙이고, 선택 결과는 [resolve]로 들어온다.
                    carry += parts
                    unresolved.putAll(newUnresolved)
                    trimHistory()
                    return AssistantReply(pendingChoice.question, reader.lastShown, choice = pendingChoice)
                }
                if (pendingChoice != null) {
                    // 쓰기 제안과 선택 요청이 함께 오면 제안을 우선하고 선택 요청은 모델 오류로 돌려준다.
                    newUnresolved.remove(pendingChoice.callKey)
                    parts += buildJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", AssistantToolSpecs.CHOOSE)
                            put("response", error("쓰기 제안과 함께 선택 요청을 보낼 수 없습니다"))
                        }
                    }
                }
                if (proposals.isNotEmpty()) {
                    // 확인 대기: 지금까지 답이 정해진 것들은 다음 턴 앞에 붙이고, 제안은 사용자 결정을 기다린다.
                    carry += parts
                    unresolved.putAll(newUnresolved)
                    trimHistory()
                    return AssistantReply("아래 내용을 확인해 주세요. 실행 전에는 아무것도 바뀌지 않습니다.", reader.lastShown, proposals, notices)
                }
                if (notices.isNotEmpty() && modelErrors == 0 && reads == 0) {
                    carry += parts
                    trimHistory()
                    return AssistantReply(notices.joinToString("\n"), emptyList(), notices = notices)
                }
                history += functionResponseTurn(parts)
            }
            throw AssistantException("요청을 끝내지 못했습니다. 조금 더 구체적으로 다시 말씀해 주세요.")
        } catch (e: Exception) {
            // 실패한 턴은 히스토리에서 통째로 되돌린다(짝이 안 맞는 functionCall/functionResponse가 남으면 다음 요청이 거부된다).
            while (history.size > savedHistory) history.removeAt(history.lastIndex)
            carry.clear(); carry += savedCarry
            unresolved.clear(); unresolved.putAll(savedUnresolved)
            throw e
        }
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }

    /** 조회 결과에 있던 ref만 후보로 인정한다(2~[MAX_BATCH]개). 그 밖에는 모델 오류로 돌려준다. */
    private fun buildChoice(callKey: String, args: JsonObject): ChoiceRequest? {
        val refs = (args["candidateRefs"] as? JsonArray)?.mapNotNull { it.asStringOrNull() }?.distinct().orEmpty()
        val candidates = refs.mapNotNull { ref -> labels.event(ref)?.let { ref to it } }.take(MAX_BATCH)
        if (candidates.size < 2) return null
        val question = args["question"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: "어느 일정인가요?"
        return ChoiceRequest(callKey, question, candidates)
    }

    private fun userTurn(text: String, prefix: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", "user")
        put(
            "parts",
            buildJsonArray {
                prefix.forEach { add(it) }
                add(buildJsonObject { put("text", text) })
            },
        )
    }

    private fun responsePart(call: FunctionCall, result: JsonObject): JsonObject = buildJsonObject {
        putJsonObject("functionResponse") {
            put("name", call.name)
            call.id?.let { put("id", it) }
            put("response", result)
        }
    }

    private fun functionResponseTurn(parts: List<JsonObject>): JsonObject = buildJsonObject {
        put("role", "user")
        put("parts", buildJsonArray { parts.forEach { add(it) } })
    }

    /** 오래된 턴을 사용자 텍스트 턴 경계에서 잘라낸다(함수 호출/응답 짝을 깨지 않기 위해). */
    private fun trimHistory() {
        while (history.size > MAX_HISTORY) {
            val next = (1 until history.size).firstOrNull { isPlainUserTurn(history[it]) } ?: return
            repeat(next) { history.removeAt(0) }
        }
    }

    private fun isPlainUserTurn(content: JsonObject): Boolean {
        if (content["role"].asStringOrNull() != "user") return false
        val parts = content["parts"] as? JsonArray ?: return false
        return parts.none { (it as? JsonObject)?.containsKey("functionResponse") == true }
    }

    private fun systemPrompt(): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekStart = data.weekStartDay()
        val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(weekStart))
        fun dow(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        return """
            당신은 캘린더 앱의 명령 해석기입니다. 사용자의 한국어 요청을 도구 호출로 바꿔 일정을 조회·추가·수정·삭제합니다.
            - 오늘은 $today(${dow(today)}), 시간대는 ${zone.id}입니다.
            - 이 앱의 한 주는 ${weekStart.getDisplayName(TextStyle.FULL, Locale.KOREAN)}에 시작합니다. 이번 주는 $thisWeekStart~${thisWeekStart.plusDays(6)}, 다음 주는 ${thisWeekStart.plusDays(7)}~${thisWeekStart.plusDays(13)}, 지난주는 ${thisWeekStart.minusDays(7)}~${thisWeekStart.minusDays(1)}입니다. "다음 주 화요일" 같은 표현은 이 범위로 계산하세요.
            - 연도가 없는 날짜(예: 9/25)는 오늘에 가장 가까운 해로 해석하세요. 날짜는 항상 YYYY-MM-DD, 시각은 YYYY-MM-DDTHH:mm로 도구에 넘기세요. "저녁 7시"=19:00, "아침 7시"=07:00.
            - 개인정보 보호를 위해 기존 일정의 제목·장소·메모·캘린더 이름은 당신에게 보이지 않고, 일정은 익명 라벨(E1…)·날짜·시간·캘린더 라벨(C1…)로만 주어집니다. 제목을 추측하거나 지어내지 마세요.
            - 일정 목록은 앱이 실제 제목으로 직접 화면에 표시합니다. 답변에는 건수와 한 줄 요약만 짧게 쓰고, 라벨(E1 등)을 나열하지 마세요.
            - 기존 일정에 대한 질문·수정·삭제는 반드시 도구로 먼저 조회한 뒤 하세요. 키워드는 사용자가 말한 단어를 그대로 쓰세요.
            - 제목을 볼 수 없어서 사용자의 단어가 실제 제목과 다른 말(동의어, 약어, 영어 표기 등)일 수 있습니다. 키워드 검색이 0건이면 곧바로 "없다"고 하지 말고, 같은 기간을 keyword 없이 한 번 더 조회해 후보를 확인하세요.
            - 수정·삭제 대상이 시간·날짜만으로 하나로 확정되지 않으면(후보가 여러 건이거나 어느 것인지 알 수 없으면) 쓰기 도구를 호출하지 말고 askUserToChoose로 후보 ref(2~${MAX_BATCH}개)를 사용자에게 고르게 하세요. 후보는 앱이 실제 제목으로 보여줍니다. 절대 임의로 고르지 마세요. 후보가 ${MAX_BATCH}개를 넘으면 시간대나 키워드를 되물으세요. 사용자가 고른 ref가 돌아오면 그 일정으로 원래 요청(수정·삭제)을 이어가세요.
            - 사용자가 "전부", "모두"라고 분명히 말했을 때만 여러 일정에 대해 쓰기 도구를 여러 번 호출하세요(한 번에 최대 ${MAX_BATCH}건). 앱이 전체 목록을 한 번에 보여주고 확인을 받습니다.
            - createEvent/updateEvent/deleteEvent는 실행이 아니라 '제안'입니다. 호출하면 앱이 사용자에게 확인 카드를 보여주고 승인해야 실행됩니다. 실행했다고 말하지 마세요.
            - 일정 옮기기는 updateEvent의 newStart(필요하면 newEnd)로 표현하세요. 시간을 말하지 않고 날짜만 바꾸라고 하면 기존 시각(조회 결과의 start/end)을 유지하세요.
            - 반복 일정의 적용 범위(이번만/이후 전체/전체)를 사용자가 말하지 않았으면 scope를 생략하세요(앱이 묻습니다).
            - "어제 만든 거"처럼 일정이 만들어진 시점을 기준으로 한 요청은 알 수 없습니다(달력에 생성 시각이 없음). 그렇게 안내하고, 날짜나 키워드를 되물으세요.
            - Notion 항목(조회 결과 readOnly=true)은 수정·삭제할 수 없습니다.
            - 답변은 한국어로 간결하게.
        """.trimIndent()
    }
}

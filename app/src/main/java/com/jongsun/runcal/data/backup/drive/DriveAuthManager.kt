package com.jongsun.runcal.data.backup.drive

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.jongsun.runcal.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Drive appDataFolder에만 접근하는 스코프. Drive 파일 전체가 아니라 이 스코프만 요청한다. */
const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

/** [DriveAuthManager.authorize]의 결과 — 이미 승인돼 있으면 바로 토큰, 아니면 UI로 넘겨야 할 PendingIntent. */
sealed interface DriveAuthorizationOutcome {
    data class Authorized(val accessToken: String) : DriveAuthorizationOutcome
    data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : DriveAuthorizationOutcome
}

/**
 * Credential Manager(로그인) + Authorization API(권한 부여)를 조합한다 — 레거시
 * GoogleSignInClient는 쓰지 않는다. 두 API는 역할이 다르다: Credential Manager는 "누구인지"를
 * 확인하고(계정 선택/로그인), Authorization API는 그 계정에 대해 drive.appdata 스코프 접근을
 * 승인받는다. 액세스 토큰은 이 클래스가 저장하지 않는다 — Play services가 내부적으로 캐시하고
 * 있어서, 이미 승인된 상태라면 [authorize]/[silentAccessToken]을 다시 불러도 UI 없이 바로
 * 새 토큰을 받을 수 있다(공식 문서의 "silent re-authorization" 패턴).
 */
class DriveAuthManager(private val context: Context) {

    private fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE))).build()

    /** 설정 화면의 "Drive 연결" 버튼에서 호출 — 계정 선택(Credential Manager) 후 표시용 라벨을 반환한다. */
    suspend fun signIn(activity: Activity): String {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.DRIVE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = CredentialManager.create(activity).getCredential(activity, request)
        val credential = response.credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).id
        }
        error("예상하지 못한 credential 타입: ${credential.type}")
    }

    /**
     * drive.appdata 스코프 승인을 요청한다. 이미 승인돼 있으면 [DriveAuthorizationOutcome.Authorized]가
     * 바로 오고, 아니면 [DriveAuthorizationOutcome.NeedsConsent]로 PendingIntent를 돌려준다 —
     * 호출부(Composable)가 `ActivityResultContracts.StartIntentSenderForResult`로 직접 띄워야 한다.
     */
    suspend fun authorize(activity: Activity): DriveAuthorizationOutcome = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(activity).authorize(authorizationRequest())
            .addOnSuccessListener { result ->
                val outcome = if (result.hasResolution()) {
                    DriveAuthorizationOutcome.NeedsConsent(result.pendingIntent!!)
                } else {
                    DriveAuthorizationOutcome.Authorized(result.accessToken!!)
                }
                cont.resume(outcome)
            }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    /** [authorize]가 NeedsConsent를 돌려준 뒤, 사용자가 동의 화면을 마치고 돌아온 Intent를 여기 넘긴다. */
    fun finishAuthorization(intent: Intent?): DriveAuthorizationOutcome {
        val result: AuthorizationResult = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
        return DriveAuthorizationOutcome.Authorized(result.accessToken!!)
    }

    /**
     * 백그라운드(MonthlyBackupWorker)에서 쓰는 조용한 토큰 조회 — Activity가 없으므로 UI가
     * 필요하면(NeedsConsent) 그냥 null을 돌려주고 업로드를 건너뛴다(재연결은 사용자가 설정
     * 화면에서 해야 함). Context만으로 이미 승인된 스코프의 새 토큰을 받을 수 있다.
     */
    suspend fun silentAccessToken(): String? = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(authorizationRequest())
            .addOnSuccessListener { result ->
                cont.resume(if (result.hasResolution()) null else result.accessToken)
            }
            .addOnFailureListener { cont.resume(null) }
    }

    /** 연결 해제 — 로컬 표시 상태 정리는 호출부(설정 저장소) 몫이고, 여기서는 실제 Google 쪽 승인을 취소한다. */
    suspend fun revoke(accountEmail: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            val request = RevokeAccessRequest.builder()
                .setAccount(Account(accountEmail, "com.google"))
                .setScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
                .build()
            Identity.getAuthorizationClient(context).revokeAccess(request)
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resume(Unit) } // 실패해도 로컬 연결 해제는 계속 진행
        }
    }
}

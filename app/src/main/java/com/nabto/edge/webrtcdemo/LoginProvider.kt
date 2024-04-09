package com.nabto.edge.webrtcdemo

import android.util.Log
import com.amplifyframework.auth.AuthException
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.kotlin.core.Amplify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * LoggedInUserView holds user information that is visible to the UI
 */
data class LoggedInUserView(
    val displayName: String
)

/**
 * LoggedInUser @TODO: Description
 */
data class LoggedInUser(
    val displayName: String,
    val tokenPayload: String
)

/**
 * LoginResult is returned by a LoginProvider
 */
sealed class LoginResult {
    enum class Errors {
        AUTH_FAILED
    }

    data class Success(val view: LoggedInUserView) : LoginResult()
    data class Error(val error: Errors = Errors.AUTH_FAILED) : LoginResult()

    override fun toString(): String {
        return when (this) {
            is Success -> ""
            is Error -> "LoginResult exception: $error"
        }
    }
}

/**
 * LoginProvider abstracts over userpool providers like Cognito
 */
interface LoginProvider {
    suspend fun login(username: String, password: String): LoginResult
    suspend fun logout()
    suspend fun isLoggedIn(): Boolean
    suspend fun getLoggedInUser(): LoggedInUser
    val loggedInUserFlow: Flow<LoggedInUser?>
}

/**
 * LoginProvider that uses Cognito + Amplify
 */
class CognitoLoginProvider(scope: CoroutineScope) : LoginProvider {
    private val tag = this.javaClass.simpleName

    private val _loggedInUserFlow = MutableSharedFlow<LoggedInUser?>(replay = 1)
    override val loggedInUserFlow: Flow<LoggedInUser?> = _loggedInUserFlow.distinctUntilChanged()

    init {
        scope.launch {
            _loggedInUserFlow.emit(if (isLoggedIn()) getLoggedInUser() else null)
        }
    }

    private suspend fun getLoggedInUserView(): LoggedInUserView {
        val user = Amplify.Auth.getCurrentUser()
        return LoggedInUserView(user.username)
    }

    override suspend fun login(username: String, password: String): LoginResult {
        return try {
            val session = Amplify.Auth.fetchAuthSession()
            if (session.isSignedIn) {
                return LoginResult.Success(getLoggedInUserView())
            }

            val result = Amplify.Auth.signIn(username, password)
            if (result.isSignedIn) {
                _loggedInUserFlow.emit(getLoggedInUser())
                LoginResult.Success(getLoggedInUserView())
            } else {
                LoginResult.Error()
            }
        } catch (error: AuthException) {
            Log.e(tag, "Sign in failed", error)
            LoginResult.Error()
        }
    }

    override suspend fun logout() {
        try {
            val session = Amplify.Auth.fetchAuthSession()
            if (session.isSignedIn) {
                _loggedInUserFlow.emit(null)
                Amplify.Auth.signOut()
            }
        } catch (error: AuthException) {
            Log.e(tag, "Sign out failed", error)
        }
    }

    override suspend fun isLoggedIn(): Boolean {
        return try {
            Amplify.Auth.fetchAuthSession().isSignedIn
        } catch (error: AuthException) {
            false
        }
    }

    override suspend fun getLoggedInUser(): LoggedInUser {
        // @TODO: Catch exceptions for this Amplify usage
        val user = Amplify.Auth.getCurrentUser()
        val session = Amplify.Auth.fetchAuthSession() as AWSCognitoAuthSession
        val token = session.accessToken.orEmpty()
        return LoggedInUser(
            user.username,
            token
        )
    }
}

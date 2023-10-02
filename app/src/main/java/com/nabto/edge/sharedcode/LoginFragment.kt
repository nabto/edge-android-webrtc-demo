package com.nabto.edge.sharedcode

import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.NavHostFragment.Companion.findNavController
import androidx.navigation.fragment.findNavController
import com.amplifyframework.auth.AuthException
import com.amplifyframework.auth.CognitoCredentialsProvider
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.result.AuthSessionResult
import com.amplifyframework.kotlin.core.Amplify
import com.nabto.edge.sharedcode.databinding.FragmentLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.android.ext.android.inject
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import java.lang.IllegalStateException

data class LoginFormErrorState(
    val usernameError: Int? = null,
    val passwordError: Int? = null,
    val isValid: Boolean = false
)

class LoginViewModelFactory(private val provider: LoginProvider) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T: ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            return LoginViewModel(provider) as T
        }
        throw IllegalArgumentException("Excepted LoginViewModel class.")
    }
}

class LoginViewModel(private val provider: LoginProvider) : ViewModel() {
    private val _loginFormState = MutableSharedFlow<LoginFormErrorState>()
    val loginFormState: SharedFlow<LoginFormErrorState> = _loginFormState

    private val _loginResult = MutableSharedFlow<LoginResult>()
    val loginResult: SharedFlow<LoginResult> = _loginResult

    fun login(username: String, password: String) {
        viewModelScope.launch {
            val result = provider.login(username, password)
            _loginResult.emit(result)
        }
    }

    fun loginDataChanged(username: String, password: String) {
        viewModelScope.launch {
            if (!isUsernameValid(username)) {
                _loginFormState.emit(LoginFormErrorState(usernameError = R.string.invalid_username))
            } else if (!isPasswordValid(password)) {
                _loginFormState.emit(LoginFormErrorState(passwordError = R.string.invalid_password))
            } else {
                _loginFormState.emit(LoginFormErrorState(isValid = true))
            }
        }
    }

    suspend fun isLoggedIn(): Boolean {
        return provider.isLoggedIn()
    }

    private fun isUsernameValid(username: String): Boolean {
        return username.length > 1
    }

    private fun isPasswordValid(password: String): Boolean {
        return password.length > 1
    }
}

class LoginFragment : Fragment() {
    private val tag = this.javaClass.simpleName
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("$tag binding is null. It should only be used between onCreateView and onDestroyView")

    private val loginViewModel: LoginViewModel by viewModels {
        val provider: LoginProvider by inject()
        LoginViewModelFactory(provider)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.visibility = View.GONE

        val etUsername = binding.username
        val etPassword = binding.password
        val loginButton = binding.login
        val loadingProgressBar = binding.loading

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Send us to
                if (loginViewModel.isLoggedIn()) {
                    findNavController().navigate(AppRoute.home())
                    return@repeatOnLifecycle
                } else {
                    binding.root.visibility = View.VISIBLE
                }

                // Launch coroutine job to collect form state
                launch {
                    loginViewModel.loginFormState.collect { loginFormState ->
                        loginButton.isEnabled = loginFormState.isValid

                        loginFormState.usernameError?.let {
                            etUsername.error = getString(it)
                        }

                        loginFormState.passwordError?.let {
                            etPassword.error = getString(it)
                        }
                    }
                }

                // Launch coroutine job to collect login result after user presses login button
                launch {
                    loginViewModel.loginResult.collect {loginResult ->
                        loadingProgressBar.visibility = View.GONE
                        when (loginResult) {
                            is LoginResult.Success -> {
                                findNavController().navigate(AppRoute.home())
                            }

                            is LoginResult.Error -> {
                                // @TODO: Check which error with loginResult.error
                                // and send an appropriate message.
                                view.snack(getString(R.string.login_failed))
                            }
                        }
                    }
                }

                listOf(etUsername, etPassword).forEach {
                    it.doAfterTextChanged {
                        loginViewModel.loginDataChanged(
                            etUsername.text.toString(),
                            etPassword.text.toString()
                        )
                    }
                }

                loginButton.setOnClickListener {
                    loadingProgressBar.visibility = View.VISIBLE
                    loginViewModel.login(
                        etUsername.text.toString(),
                        etPassword.text.toString()
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
package com.nabto.edge.sharedcode

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.whenResumed
import com.nabto.edge.client.Coap
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.android.ext.android.inject
import org.webrtc.EglBase

@Serializable
data class NabtoToken(
    val access_token: String,
    val token_type: String,
    val expires_in: Int,
    val issued_token_type: String
)

class DevicePageFragment : Fragment() {
    private val TAG = javaClass.simpleName
    private val auth: LoginProvider by inject()
    private val productId by lazy { arguments?.getString("productId") ?: "" }
    private val deviceId by lazy { arguments?.getString("deviceId") ?: "" }
    private val manager: NabtoConnectionManager by inject()

    private lateinit var stsToken: NabtoToken
    private lateinit var rootEglBase: EglBase
    private lateinit var videoComponent: VideoComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rootEglBase = EglBase.create()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_device_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val handle = manager.requestConnection(Device(productId, deviceId))
        val conn = manager.getConnection(handle)
        requireAppActivity().actionBarTitle = "" // @TODO: Get device name for the action bar title.

        // @TODO: Navigate back out if connection isnt open.
        lifecycleScope.launch {
            whenResumed {
                val serviceUrl = AppConfig.CLOUD_SERVICE_URL
                val user = auth.getLoggedInUser()
                val formBody = FormBody.Builder().apply {
                    add("client_id", AppConfig.COGNITO_APP_CLIENT_ID)
                    add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                    add("subject_token", user.tokenPayload)
                    add("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                    add("resource", "nabto://device?productId=${productId}&deviceId=${deviceId}")
                }.build()
                val http = OkHttpClient()
                val req = Request.Builder().apply {
                    url("$serviceUrl/sts/token")
                    post(formBody)
                }.build()

                withContext(Dispatchers.IO) {
                    http.newCall(req).execute().use {
                        val body = it.body.string()
                        Log.i(tag, "STS token: $body")
                        val json = Json { ignoreUnknownKeys = true }
                        stsToken = json.decodeFromString(body)
                        Log.i(tag, "Decoded STS token: $stsToken")
                    }
                }

                val oauthCoap = conn.createCoap("POST", "/webrtc/oauth")
                oauthCoap.setRequestPayload(
                    Coap.ContentFormat.TEXT_PLAIN,
                    stsToken.access_token.toByteArray()
                )
                oauthCoap.execute()

                Log.i(tag, "Oauth response code: ${oauthCoap.responseStatusCode}")
                if (oauthCoap.responseStatusCode == 201) {
                    val frame =
                        view.findViewById<VideoTextureViewRenderer>(R.id.participantVideoRenderer)
                    videoComponent = VideoComponent(
                        requireContext(),
                        frame,
                        rootEglBase.eglBaseContext,
                        handle,
                        viewLifecycleOwner.lifecycleScope
                    )
                    videoComponent.start()
                } else {
                    Log.i(tag, "Failed to perform oauth login")
                }
            }
        }

    }

    override fun onStop() {
        super.onStop()
        videoComponent.stop()
    }
}
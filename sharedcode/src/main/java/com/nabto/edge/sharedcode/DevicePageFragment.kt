package com.nabto.edge.sharedcode

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.nabto.edge.client.Coap
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import org.koin.android.ext.android.inject
import com.nabto.edge.client.Stream
import com.nabto.edge.client.ktx.awaitWrite
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.lang.RuntimeException
import java.nio.ByteBuffer

@Serializable
data class NabtoToken(
    val access_token: String,
    val token_type: String,
    val expires_in: Int
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

        lifecycleScope.launch {
            // @TODO: Short test with hardcoded values, to be deleted.
            val serviceUrl = AppConfig.CLOUD_SERVICE_URL
            val user = auth.getLoggedInUser()
            val formBody = FormBody.Builder().apply {
                add("client_id", "5jcc2gjesirk155lh8u2ikf94r") 
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
                    stsToken = Json.decodeFromString(body)
                    Log.i(tag, "Decoded STS token: $stsToken")
                }
            }

            val oauthCoap = conn.createCoap("POST", "/webrtc/oauth")
            oauthCoap.setRequestPayload(Coap.ContentFormat.TEXT_PLAIN, stsToken.access_token.toByteArray())
            oauthCoap.execute()

            Log.i(tag, "Oauth response code: ${oauthCoap.responseStatusCode}")
            if (oauthCoap.responseStatusCode != 201) {
                Log.i(tag, "Failed to perform oauth login")
                return@launch
            }

            val frame = view.findViewById<VideoTextureViewRenderer>(R.id.participantVideoRenderer)
            videoComponent = VideoComponent(requireContext(), frame, rootEglBase.eglBaseContext, handle, viewLifecycleOwner.lifecycleScope)
            videoComponent.start() // @TODO: We need a stop function too
        }
    }
}
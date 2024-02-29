package com.nabto.edge.sharedcode

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.nabto.edge.client.Coap
import com.nabto.edge.client.Connection
import com.nabto.edge.client.ktx.awaitExecute
import com.nabto.edge.client.webrtc.EdgeVideoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.android.ext.android.inject
import com.nabto.edge.client.webrtc.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

@Serializable
data class NabtoToken(
    val access_token: String,
    val token_type: String,
    val expires_in: Int,
    val issued_token_type: String
)

enum class DeviceError {
    NONE,
    AUTH_FAILED,
    VIDEO_CALL_FAILED
}

class DevicePageViewModel : ViewModel() {
    private val tag = javaClass.simpleName
    private var stsToken: NabtoToken? = null
    private lateinit var peerConnection: EdgeWebrtcConnection
    private lateinit var remoteTrack: EdgeVideoTrack

    private val _errors = MutableStateFlow(DeviceError.NONE)
    val errors = _errors.asStateFlow()

    private var _videoView: WeakReference<EdgeVideoView> = WeakReference(null)
    var videoView: EdgeVideoView?
        get() = _videoView.get()
        set(value) {
            _videoView = WeakReference(value)
        }

    suspend fun authenticate(auth: LoginProvider, productId: String, deviceId: String, conn: Connection) {
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
                val json = Json { ignoreUnknownKeys = true }
                stsToken = json.decodeFromString(body)
            }
        }

        val oauthCoap = conn.createCoap("POST", "/webrtc/oauth")
        oauthCoap?.setRequestPayload(
            Coap.ContentFormat.TEXT_PLAIN,
            stsToken?.access_token?.toByteArray()
        )
        oauthCoap?.awaitExecute()

        if (oauthCoap?.responseStatusCode != 201) {
            _errors.emit(DeviceError.AUTH_FAILED)
        }
    }

    fun openVideoStream(conn: Connection) {
        peerConnection = EdgeWebRTCManager.getInstance().createRTCConnection(conn)
        val connRef = WeakReference(conn)

        peerConnection.onConnected {
            Log.i(tag, "Connected to peer")
            val trackInfo = """{"tracks": ["frontdoor-video", "frontdoor-audio"]}"""

            val coap = connRef.get()?.createCoap("POST", "/webrtc/tracks")
            coap?.setRequestPayload(50, trackInfo.toByteArray())
            coap?.execute()

            if (coap?.responseStatusCode != 201) {
                viewModelScope.launch {
                    _errors.emit(DeviceError.VIDEO_CALL_FAILED)
                }
            }
        }

        peerConnection.onTrack {track ->
            Log.i(tag, "Track of type ${track.type}")
            if (track.type == EdgeMediaTrackType.VIDEO) {
                remoteTrack = track as EdgeVideoTrack
                videoView?.let { remoteTrack.add(it) }
            }
        }

        peerConnection.onError { error ->

        }

        peerConnection.connect()
    }

    fun closeVideoStream() {
        peerConnection.close()
    }
}

class DevicePageFragment : Fragment() {
    private val auth: LoginProvider by inject()
    private val productId by lazy { arguments?.getString("productId") ?: "" }
    private val deviceId by lazy { arguments?.getString("deviceId") ?: "" }
    private val manager: NabtoConnectionManager by inject()
    private val viewModel: DevicePageViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_device_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val deviceHandle = manager.requestConnection(Device(productId, deviceId))
        val videoView = view.findViewById<EdgeVideoView>(R.id.participantVideoRenderer)
        EdgeWebRTCManager.getInstance().initVideoView(videoView)
        requireAppActivity().actionBarTitle = "" // @TODO: Get device name for the action bar title.

        lifecycleScope.launch {
            manager.getConnectionState(deviceHandle)?.asFlow()?.collect { state ->
                when (state) {
                    NabtoConnectionState.CLOSED -> {
                        findNavController().popBackStack()
                    }
                    NabtoConnectionState.CONNECTING -> {}
                    NabtoConnectionState.CONNECTED -> {}
                }
            }
        }

        lifecycleScope.launch {
            viewModel.errors.collect { error ->
                when (error) {
                    DeviceError.NONE -> {}
                    // @TODO: Return the user to the overview when these errors happen?
                    DeviceError.AUTH_FAILED -> view.snack("Authentication against central server failed.")
                    DeviceError.VIDEO_CALL_FAILED -> view.snack("Video stream could not be started.")
                }
            }
        }

        lifecycleScope.launch {
            val conn = manager.getConnection(deviceHandle)
            viewModel.videoView = videoView
            viewModel.authenticate(auth, productId, deviceId, conn)
            viewModel.openVideoStream(conn)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.closeVideoStream()
    }
}
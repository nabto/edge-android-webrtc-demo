package com.nabto.edge.webrtcdemo

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
import com.amplifyframework.auth.AuthException
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import java.io.IOException
import java.lang.ref.WeakReference

@Serializable
data class NabtoToken(
    val access_token: String,
    val token_type: String,
    val expires_in: Int,
    val issued_token_type: String
)

sealed class Error {
    data object none : Error()
    data object authFailed : Error()
    data object notLoggedIn : Error()
    data object videoCallFailed : Error()
    data class peerConnectionError(val err: EdgeWebrtcError) : Error()
}

class DevicePageViewModel : ViewModel() {
    private val tag = javaClass.simpleName
    private var stsToken: NabtoToken? = null
    private lateinit var peerConnection: EdgeWebrtcConnection
    private lateinit var remoteTrack: EdgeVideoTrack

    private val _errors = MutableStateFlow<Error>(Error.none)
    val errors = _errors.asStateFlow()

    private var _videoView: WeakReference<EdgeVideoView> = WeakReference(null)
    var videoView: EdgeVideoView?
        get() = _videoView.get()
        set(value) {
            _videoView = WeakReference(value)
        }

    suspend fun authenticate(auth: LoginProvider, productId: String, deviceId: String, conn: Connection): Boolean {
        val serviceUrl = AppConfig.CLOUD_SERVICE_URL

        val user = try {
            auth.getLoggedInUser()
        } catch (exception: AuthException) {
            _errors.emit(Error.notLoggedIn)
            return false
        }

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
            try {
                http.newCall(req).execute().use {
                    val body = it.body.string()
                    val json = Json { ignoreUnknownKeys = true }
                    stsToken = json.decodeFromString(body)
                }
            } catch (exception: IOException) {
                stsToken = null
            }
        }

        if (stsToken == null) {
            return false
        }

        val oauthCoap = conn.createCoap("POST", "/webrtc/oauth")
        oauthCoap?.setRequestPayload(
            Coap.ContentFormat.TEXT_PLAIN,
            stsToken?.access_token?.toByteArray()
        )
        oauthCoap?.awaitExecute()

        if (oauthCoap?.responseStatusCode != 201) {
            _errors.emit(Error.authFailed)
            return false
        }

        return true
    }

    suspend fun openVideoStream(conn: Connection) {
        peerConnection = EdgeWebrtcManager.getInstance().createRTCConnection(conn)
        val connRef = WeakReference(conn)

        peerConnection.onTrack { track, _ ->
            Log.i(tag, "Track of type ${track.type}")
            if (track.type == EdgeMediaTrackType.VIDEO) {
                remoteTrack = track as EdgeVideoTrack
                videoView?.let { remoteTrack.add(it) }
            }
        }

        peerConnection.onError { error ->
            viewModelScope.launch {
                _errors.emit(Error.peerConnectionError(error))
            }
        }

        peerConnection.connect().await()
        Log.i(tag, "Connected to peer")
        val trackInfo = """{"tracks": ["frontdoor-video", "frontdoor-audio"]}"""

        val coap = connRef.get()?.createCoap("POST", "/webrtc/tracks")
        coap?.setRequestPayload(50, trackInfo.toByteArray())
        coap?.execute()

        if (coap?.responseStatusCode != 201) {
            viewModelScope.launch {
                _errors.emit(Error.videoCallFailed)
            }
        }
    }

    fun closeVideoStream() {
        Log.i(tag, "Shutting down peer connection...")
        peerConnection.connectionClose()
    }
}

class DevicePageFragment : Fragment() {
    private val auth: LoginProvider by inject()
    private val productId by lazy { arguments?.getString("productId") ?: "" }
    private val deviceId by lazy { arguments?.getString("deviceId") ?: "" }
    private val deviceName by lazy { bookmarks.getFriendlyName(Device(productId, deviceId)) }
    private val manager: NabtoConnectionManager by inject()
    private val bookmarks: NabtoBookmarksRepository by inject()
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
        EdgeWebrtcManager.getInstance().initVideoView(videoView)
        requireAppActivity().actionBarTitle = deviceName

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
                    is Error.none -> {}
                    is Error.authFailed -> view.snack("Authentication against central server failed.")
                    is Error.videoCallFailed -> view.snack("Video stream could not be started.")
                    is Error.notLoggedIn -> {
                        view.snack("You are not logged in currently.")
                        // @TODO: return to login page
                    }
                    is Error.peerConnectionError -> view.snack("WebRTC error: ${error.err}")
                }
            }
        }

        lifecycleScope.launch {
            val conn = manager.getConnection(deviceHandle)
            viewModel.videoView = videoView
            val authResult = viewModel.authenticate(auth, productId, deviceId, conn)
            if (authResult) {
                viewModel.openVideoStream(conn)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.closeVideoStream()
    }
}
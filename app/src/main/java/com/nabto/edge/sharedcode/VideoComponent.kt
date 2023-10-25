package com.nabto.edge.sharedcode
import android.content.Context
import android.util.Log
import com.nabto.edge.client.Stream
import com.nabto.edge.client.ktx.*
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
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

@Serializable
data class RTCInfo(
    @Required @SerialName("FileStreamPort") val fileStreamPort: Int,
    @Required @SerialName("SignalingStreamPort") val signalingStreamPort: Int
)

enum class SignalMessageType(val num: Int) {
    OFFER(0),
    ANSWER(1),
    ICE_CANDIDATE(2),
    TURN_REQUEST(3),
    TURN_RESPONSE(4)
}

@Serializable
data class SDP(
    val type: String,
    val sdp: String
)

@Serializable
data class MetadataTrack(
    val mid: String,
    val trackId: String
)

@Serializable
data class SignalMessageMetadata(
    val tracks: List<MetadataTrack>,
    val noTrickle: Boolean
)

@Serializable
data class SignalMessage(
    val type: Int,
    val data: String?,
    val metadata: SignalMessageMetadata?
)

// @TODO: Rename
fun writeMessage(stream: Stream, msg: SignalMessage) {
    val json = Json.encodeToString(msg)
    val lenBytes = byteArrayOf(
        (json.length shr 0).toByte(),
        (json.length shr 8).toByte(),
        (json.length shr 16).toByte(),
        (json.length shr 24).toByte()
    )
    val res = lenBytes + json.toByteArray(Charsets.UTF_8)
    stream.write(res)
}

// @TODO: Rename
fun readMessage(stream: Stream): SignalMessage {
    val lenData = stream.readAll(4)
    val len =
        ((lenData[0].toUInt() and 0xFFu)) or
                ((lenData[1].toUInt() and 0xFFu) shl 8) or
                ((lenData[2].toUInt() and 0xFFu) shl 16) or
                ((lenData[3].toUInt() and 0xFFu) shl 24)

    val json = String(stream.readAll(len.toInt()), Charsets.UTF_8)
    return Json.decodeFromString<SignalMessage>(json)
}

private val dummySdpObserver = object : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

class VideoComponent(
    private val context: Context,
    private val renderer: VideoTextureViewRenderer,
    private val eglBaseContext: EglBase.Context,
    private val deviceConnectionHandle: ConnectionHandle,
    private val scope: CoroutineScope,
) : RendererCommon.RendererEvents, PeerConnection.Observer {
    private val tag = this.javaClass.simpleName

    // @TODO: Make iceServers a parameter.
    private val iceServers = mutableListOf(
        PeerConnection.IceServer.builder("stun:stun.nabto.net").createIceServer()
    )

    private lateinit var deviceStream: Stream
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var peer: PeerConnection

    private fun log(msg: String) {
        // @TODO: Conditional logging
        Log.d(tag, msg)
    }

    override fun onFirstFrameRendered() {}
    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceCandidate(candidate: IceCandidate?) {}
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
    override fun onAddStream(stream: MediaStream?) {}
    override fun onRemoveStream(stream: MediaStream?) {}
    override fun onDataChannel(dataChannel: DataChannel?) {}
    override fun onRenegotiationNeeded() {}

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
        if (state == PeerConnection.IceGatheringState.COMPLETE) {
            writeMessage(deviceStream, SignalMessage(
                type = SignalMessageType.OFFER.num,
                data = Json.encodeToString(SDP("offer", peer.localDescription.description)),
                metadata = SignalMessageMetadata(
                    tracks = listOf(
                        MetadataTrack(
                            mid = "0",
                            trackId = "frontdoor-video"
                        )
                    ),
                    noTrickle = true
                )
            ))

            val msg = readMessage(deviceStream)
            val answerData = Json.decodeFromString<SDP>(msg.data!!)
            val answer = SessionDescription(SessionDescription.Type.ANSWER, answerData.sdp)
            peer.setRemoteDescription(dummySdpObserver, answer)
        }
    }

    override fun onTrack(transceiver: RtpTransceiver?) {
        super.onTrack(transceiver) // @TODO: Delete this super call?
        if (transceiver?.receiver?.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
            val videoTrack = transceiver.receiver?.track() as VideoTrack
            videoTrack.addSink(renderer)
        }
    }

    private suspend fun startSignalingStream() {
        val manager = deviceConnectionHandle.manager
        val conn = manager.getConnection(deviceConnectionHandle)

        val coap = conn.createCoap("GET", "/webrtc/info")
        coap.awaitExecute()

        if (coap.responseStatusCode != 205) {
            log("Unexpected /webrtc/info return code ${coap.responseStatusCode}")
            // @TODO: Fatal error here.
        }

        val rtcInfo = Cbor.decodeFromByteArray<RTCInfo>(coap.responsePayload)
        deviceStream = conn.createStream()
        deviceStream.awaitOpen(rtcInfo.signalingStreamPort)
    }

    // @TODO: Better name for this than just "start"
    suspend fun start() {
        startSignalingStream()

        // Init our TextureView with the EGL context
        renderer.init(eglBaseContext, this)

        // THIS PART IS VERY IMPORTANT!
        // We need to specify that we want to use h264 encoding/decoding, otherwise the SDP
        // will not reflect this.
        val encoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)

        // Make a PeerConnectionFactory.InitializationOptions builder and build the InitializationOptions.
        // Then we can initialize the static parts of the PeerConnectionFactory class
        val staticOpts = PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        PeerConnectionFactory.initialize(staticOpts)

        // Now that the static part of the class is initialized, we make a PeerConnectionFactoryBuilder
        // so we can build our PeerConnectionFactory
        peerConnectionFactory = PeerConnectionFactory.builder().apply {
            setVideoEncoderFactory(encoderFactory)
            setVideoDecoderFactory(decoderFactory)
        }.createPeerConnectionFactory()

        // We create our PeerConnection.Observer and then our PeerConnection finally.
        peer = peerConnectionFactory.createPeerConnection(iceServers, this) ?: run {
            // @TODO: Error in a better way than throwing a vague exception
            throw RuntimeException("Could not create PeerConnection")
        }

        // Create a video track
        val source = peerConnectionFactory.createVideoSource(false)
        // @TODO: Should video0 always be the name of the video track?
        val videoTrack = peerConnectionFactory.createVideoTrack("video0", source)
        peer.addTrack(videoTrack)


        val constraints = MediaConstraints()
        constraints.mandatory.addAll(
            listOf(
                MediaConstraints.KeyValuePair("offerToReceiveVideo", "true")
            )
        )

        val offerObserver = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                peer.setLocalDescription(this, sdp!!)
            }

            override fun onSetSuccess() {
                // @TODO
            }

            override fun onCreateFailure(p0: String?) {
                // @TODO
            }

            override fun onSetFailure(p0: String?) {
                // @TODO
            }
        }
        peer.createOffer(offerObserver, constraints)
    }

    fun stop() {
        // @TODO: Check if peer and deviceStream are actually open before calling close.
        peer.close()
        deviceStream.closeCallback { _, _ -> }
    }
}
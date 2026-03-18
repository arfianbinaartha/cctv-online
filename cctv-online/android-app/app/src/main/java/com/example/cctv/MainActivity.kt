package com.example.cctv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        // URL signaling server (Railway)
        private const val SIGNALING_URL = "https://cctv-online-production.up.railway.app/"
    }

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var localView: SurfaceViewRenderer

    private var socket: Socket? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private var eglBase: EglBase? = null
    private var currentViewerId: String? = null

    private val executor = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.values.all { it }
        if (ok) initAndConnect() else updateStatus("Permission ditolak")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        localView = findViewById(R.id.localView)

        btnStart.setOnClickListener { requestPermissionsAndStart() }
        btnStop.setOnClickListener { stopStreaming() }
    }

    private fun requestPermissionsAndStart() {
        val needsCamera = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        val needsMic = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needsCamera || needsMic) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            initAndConnect()
        }
    }

    private fun initAndConnect() {
        if (peerConnectionFactory != null) return
        setupWebRTC()
        connectSocket()
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun setupWebRTC() {
        eglBase = EglBase.create()
        localView.init(eglBase!!.eglBaseContext, null)
        localView.setEnableHardwareScaler(true)
        localView.setMirror(true)

        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        videoCapturer = createVideoCapturer()
        if (videoCapturer == null) {
            updateStatus("Tidak ada kamera")
        }
    }

    private fun connectSocket() {
        socket = try {
            IO.socket(SIGNALING_URL)
        } catch (e: Exception) {
            updateStatus("URL signaling invalid: ${e.message}")
            return
        }
        socket?.on(Socket.EVENT_CONNECT) { runOnUiThread { updateStatus("Terhubung ke signaling") } }
        socket?.on("viewer-offer") { args ->
            try {
                val payload = args.firstOrNull() as? JSONObject ?: return@on
                val viewerId = payload.optString("viewerId")
                val offerJson = payload.optJSONObject("offer")
                val sdp = offerJson?.optString("sdp").orEmpty()
                if (viewerId.isBlank() || sdp.isBlank()) {
                    runOnUiThread { updateStatus("Offer tidak valid dari server") }
                    return@on
                }
                val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                runOnUiThread {
                    handleViewerOffer(viewerId, offer)
                }
            } catch (e: Exception) {
                runOnUiThread { updateStatus("Gagal proses offer: ${e.message}") }
            }
        }
        socket?.on("ice-candidate") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            val candidate = IceCandidate(
                json.optJSONObject("candidate")?.getString("sdpMid"),
                json.optJSONObject("candidate")?.getInt("sdpMLineIndex") ?: 0,
                json.optJSONObject("candidate")?.getString("candidate")
            )
            runOnUiThread { peerConnection?.addIceCandidate(candidate) }
        }
        socket?.on("control") { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@on
            val type = payload.optString("type")
            if (type == "stop") runOnUiThread { stopStreaming() }
        }
        socket?.connect()
        socket?.emit("register-camera")
    }

    private fun handleViewerOffer(viewerId: String, offer: SessionDescription) {
        currentViewerId = viewerId
        ensurePeerConnection(viewerId)
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), offer)
        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(answer: SessionDescription?) {
                answer ?: return
                peerConnection?.setLocalDescription(SimpleSdpObserver(), answer)
                socket?.emit("camera-answer", JSONObject().apply {
                    put("answer", JSONObject().apply {
                        put("type", answer.type.canonicalForm())
                        put("sdp", answer.description)
                    })
                    put("viewerId", viewerId)
                })
            }
        }, MediaConstraints())
    }

    private fun ensurePeerConnection(viewerId: String) {
        if (peerConnection != null) return
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                socket?.emit("ice-candidate", JSONObject().apply {
                    put("target", viewerId)
                    put("candidate", JSONObject().apply {
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                        put("candidate", candidate.sdp)
                    })
                })
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                runOnUiThread { updateStatus("PC: $newState") }
            }
            override fun onAddStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        })
        startLocalVideo()
    }

    private fun startLocalVideo() {
        if (videoCapturer == null || peerConnectionFactory == null) {
            updateStatus("Kamera belum siap")
            return
        }
        if (localVideoSource != null) return
        try {
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
            localVideoSource = peerConnectionFactory!!.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer!!.initialize(surfaceTextureHelper, applicationContext, localVideoSource!!.capturerObserver)
            videoCapturer!!.startCapture(1280, 720, 30)
            localTrack = peerConnectionFactory!!.createVideoTrack("ARDAMSv0", localVideoSource)
            localTrack!!.addSink(localView)
            peerConnection?.addTrack(localTrack)
            updateStatus("Streaming...")
        } catch (e: Exception) {
            updateStatus("Gagal start kamera: ${e.message}")
        }
    }

    private fun stopStreaming() {
        peerConnection?.close()
        peerConnection = null
        currentViewerId = null
        localTrack?.dispose()
        localTrack = null
        localVideoSource?.dispose()
        localVideoSource = null
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        updateStatus("Stopped")
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        val deviceNames = enumerator.deviceNames
        val front = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val back = deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        val cameraName = front ?: back
        return cameraName?.let { enumerator.createCapturer(it, null) }
    }

    private fun updateStatus(text: String) {
        statusText.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        socket?.disconnect()
        socket?.close()
        executor.shutdown()
        localView.release()
        eglBase?.release()
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

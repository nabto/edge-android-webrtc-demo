package com.nabto.edge.webrtcdemo

import android.util.Log
import androidx.lifecycle.asFlow
import com.amplifyframework.auth.AuthException
import com.nabto.edge.iamutil.IamException
import com.nabto.edge.iamutil.IamUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

enum class BookmarkStatus {
    ONLINE,
    CONNECTING,
    OFFLINE,
    UNPAIRED,
    WRONG_FINGERPRINT
}

data class DeviceBookmark(
    val device: Device,
    val status: BookmarkStatus
)

@Serializable
data class CloudDevice(
    val id: String,
    val owner: String,
    val name: String,
    val nabtoProductId: String,
    val nabtoDeviceId: String,
    val nabtoSct: String?,
    val fingerprint: String
)

interface NabtoBookmarksRepository {
    fun getDevices(): Flow<List<DeviceBookmark>?>
    fun getStatus(device: Device): BookmarkStatus
    fun getFriendlyName(device: Device): String
    fun release()
    //fun releaseExcept(devices: Set<Device>)
    fun disconnectBookmarksExcept(devices: Set<Device>)
    fun refresh()
}

private sealed class FlowMessage {
    class DeviceList(val list: List<Device>): FlowMessage()
    class User(val user: LoggedInUser?): FlowMessage()
}

class NabtoBookmarksRepositoryImpl(
    private val database: DeviceDatabase,
    private val manager: NabtoConnectionManager,
    private val provider: LoginProvider,
    private val scope: CoroutineScope
): NabtoBookmarksRepository {
    private val TAG = NabtoBookmarksRepository::class.java.simpleName

    private val _deviceFlow = MutableStateFlow<List<DeviceBookmark>?>(null)

    data class BookmarkData(
        val handle: ConnectionHandle,
        var status: BookmarkStatus,
        val job: Job,
        val name: String
    )

    private val bookmarks = mutableMapOf<Device, BookmarkData>()
    private val updateFlow = MutableSharedFlow<List<Device>>()

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient()
    private val cloudServiceUrl = AppConfig.CLOUD_SERVICE_URL

    init {
        scope.launch {
            val dbFlow = database.deviceDao().getAll().map { FlowMessage.DeviceList(it) }
            val userFlow = provider.loggedInUserFlow.map { FlowMessage.User(it) }
            val flow = merge(dbFlow, userFlow)

            flow.collect { msg ->
                when (msg) {
                    is FlowMessage.DeviceList -> {
                        // @TODO: Figure out how to merge cloud devices list together with database devices.
                        //databaseDevices = msg.list
                    }

                    is FlowMessage.User -> {
                        if (msg.user != null) {
                            updateCloudDevices()
                        }
                    }
                }
            }
        }

        scope.launch {
            updateFlow.collect { after ->
                Log.i(TAG, "Received new devices in update: ${after.size}")
                val before = bookmarks.keys
                val added = after - before
                val removed = before - after.toSet()

                for (dev in added) {
                    val handle = manager.requestConnection(dev)

                    val job = scope.launch {
                        manager.getConnectionState(handle)?.asFlow()?.collect { state ->
                            Log.i("TEST", "Updating device state...")
                            bookmarks[dev]?.let { data ->
                                data.status = when (state) {
                                    NabtoConnectionState.CLOSED -> BookmarkStatus.OFFLINE
                                    NabtoConnectionState.CONNECTING -> BookmarkStatus.CONNECTING
                                    NabtoConnectionState.CONNECTED -> getConnectedStatus(handle, dev)
                                }
                            }

                            // update if any device has changed connection state
                            postBookmarks()
                        }
                    }

                    bookmarks[dev] = BookmarkData(
                        handle = handle,
                        status = BookmarkStatus.OFFLINE,
                        job = job,
                        name = dev.friendlyName
                    )

                    manager.connect(handle)
                }

                for (key in removed) {
                    bookmarks.remove(key)?.let { data ->
                        manager.releaseHandle(data.handle)
                    }
                }

                if (removed.isNotEmpty() || added.isNotEmpty()) {
                    // update if devices have been added/removed
                    postBookmarks()
                }
            }
        }
    }

    private suspend fun updateCloudDevices() {
        val devs = getDevicesFromCloudService().map {
            Device(
                productId = it.nabtoProductId,
                deviceId = it.nabtoDeviceId,
                fingerprint = it.fingerprint,
                SCT = it.nabtoSct ?: "demosct",
                friendlyName = it.name
            )
        }

        Log.i(TAG, "updateCloudDevices: ${devs.size}")
        updateFlow.emit(devs)
    }

    private suspend fun getDevicesFromCloudService(): List<CloudDevice> {
        val user = try {
            provider.getLoggedInUser()
        } catch (exception: AuthException) {
            return listOf()
        }

        Log.i(TAG, "Getting devices...")
        val request = Request.Builder().run {
            url("$cloudServiceUrl/webrtc/devices")
            header("Authorization", "Bearer ${user.tokenPayload}")
            build()
        }

        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Cloud service gave unexpected response $response")
                    listOf<CloudDevice>()
                } else {
                    val body = response.body.string()
                    Log.i(TAG, body);
                    json.decodeFromString<List<CloudDevice>>(body)
                }
            }
        }
    }

    private fun postBookmarks() {
        scope.launch {
            val bookmarks = bookmarks.keys.map { dev ->
                DeviceBookmark(dev, getStatus(dev))
            }
            _deviceFlow.emit(bookmarks)
        }
    }

    private suspend fun getConnectedStatus(handle: ConnectionHandle, device: Device): BookmarkStatus {
        val iam = IamUtil.create()
        return try {
            /*
            val localFingerprint = device.fingerprint
            val fp = manager.getConnection(handle).deviceFingerprint
            if (localFingerprint != fp) {
                return BookmarkStatus.WRONG_FINGERPRINT
            }

            val paired = iam.awaitIsCurrentUserPaired(manager.getConnection(handle))
            if (paired) {
                BookmarkStatus.ONLINE
            } else {
                BookmarkStatus.UNPAIRED
            }
             */
            BookmarkStatus.ONLINE
        } catch (e: IamException) {
            Log.i(TAG, "getConnectedStatus caught exception $e")
            BookmarkStatus.OFFLINE
        }
    }

    override fun getDevices(): Flow<List<DeviceBookmark>?> {
        return _deviceFlow
    }

    override fun getStatus(device: Device): BookmarkStatus {
        return bookmarks[device]?.status ?: BookmarkStatus.OFFLINE
    }

    override fun getFriendlyName(device: Device): String {
        return bookmarks[device]?.name ?: "Unknown device"
    }

    override fun refresh() {
        scope.launch {
            updateCloudDevices()
            for ((dev, data) in bookmarks) {
                manager.connect(data.handle)
            }
        }
    }

    override fun release() {
        for ((_, data) in bookmarks) {
            manager.releaseHandle(data.handle)
        }
        bookmarks.clear()
    }

    override fun disconnectBookmarksExcept(devices: Set<Device>) {
        for ((dev, data) in bookmarks) {
            if (!devices.contains(dev)) {
                manager.disconnect(data.handle)
            }
        }
    }
}


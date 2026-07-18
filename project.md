```markdown
# Role & Project Scope
You are an elite principal engineer specializing in Kotlin Multiplatform (KMP), Compose Multiplatform, and local-first peer-to-peer networking. We are building the foundational architecture for an app named **OmniNode** (a local-first, decentralized, symmetrical file-explorer network for Android and macOS, with future iOS target capability).

# Core Technical Stack
- Project Name: OmniNode
- Framework: KMP with Compose Multiplatform for shared UI.
- Database: Room 3.0 (KMP Native configured inside commonMain).
- Network Protocol: HTTP/2 via Ktor Server (CIO engine for multiplatform background hosting) and Ktor Client.
- File I/O: Pure multiplatform 'kotlinx.io' to preserve absolute compatibility between Android, Desktop, and iOS targets. No 'java.io.File' inside commonMain.
- Authentication: Mutual TLS (mTLS) using transient X.509 Elliptic Curve (ECDSA) keys generated locally. No cloud dependencies.
- Discovery: mDNS (Network Service Discovery on Android, Bonjour on macOS).

# Execution Strategy & Requirements
- Generate full, production-ready file implementations. Do not provide partial snippets, inline truncation, or placeholder comments.
- Organize the output cleanly by file path.

---

## 1. Project Configuration & Room 3.0 Setup

### Path: `shared/src/commonMain/kotlin/com/omninode/data/db/OmniNodeDatabase.kt`
```kotlin
package com.omninode.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val lastKnownIp: String,
    val port: Int,
    val publicKeyHash: String
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM paired_devices")
    fun getAllDevices(): Flow<List<PairedDeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: PairedDeviceEntity)

    @Query("DELETE FROM paired_devices WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)
}

@Database(entities = [PairedDeviceEntity::class], version = 1)
abstract class OmniNodeDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
}

expect class RoomDbBuilder {
    fun builder(): RoomDatabase.Builder<OmniNodeDatabase>
}

```

### Path: `shared/src/androidMain/kotlin/com/omninode/data/db/OmniNodeDatabase.android.kt`

```kotlin
package com.omninode.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

actual class RoomDbBuilder(private val context: Context) {
    actual fun builder(): RoomDatabase.Builder<OmniNodeDatabase> {
        val dbFile = context.getDatabasePath("omninode.db")
        return Room.databaseBuilder<OmniNodeDatabase>(
            context = context,
            name = dbFile.absolutePath
        )
    }
}

```

### Path: `shared/src/desktopMain/kotlin/com/omninode/data/db/OmniNodeDatabase.desktop.kt`

```kotlin
package com.omninode.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

actual class RoomDbBuilder {
    actual fun builder(): RoomDatabase.Builder<OmniNodeDatabase> {
        val dbFile = File(System.getProperty("user.home"), ".omninode/omninode.db")
        if (!dbFile.parentFile.exists()) {
            dbFile.parentFile.mkdirs()
        }
        return Room.databaseBuilder<OmniNodeDatabase>(
            name = dbFile.absolutePath
        )
    }
}

```

---

## 2. Domain Layer & File Virtualization Core

### Path: `shared/src/commonMain/kotlin/com/omninode/domain/model/RemoteFileItem.kt`

```kotlin
package com.omninode.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RemoteFileItem(
    val id: String,
    val name: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val mimeType: String
)

data class ClipboardPayload(
    val sourceDeviceId: String,
    val remoteAbsolutePath: String,
    val sizeBytes: Long,
    val mimeType: String
)

```

---

## 3. Symmetrical Ktor CIO Server Implementation (KMP Native)

### Path: `shared/src/commonMain/kotlin/com/omninode/network/OmniNodeServer.kt`

```kotlin
package com.omninode.network

import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import com.omninode.domain.model.RemoteFileItem
import kotlinx.io.files.Path
import kotlinx.io.files.FileSystem
import kotlinx.io.buffered
import io.ktor.utils.io.asSink

class OmniNodeServer(private val port: Int) {
    private var serverEngine: CIOApplicationEngine? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        serverEngine = embeddedServer(CIO, port = port) {
            routing {
                get("/api/v1/files/list") {
                    val pathStr = call.request.queryParameters["path"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val basePath = Path(pathStr)
                    
                    if (FileSystem.SYSTEM.exists(basePath)) {
                        val items = FileSystem.SYSTEM.list(basePath).map { path ->
                            val metadata = FileSystem.SYSTEM.metadataOrNull(path)
                            RemoteFileItem(
                                id = path.toString().hashCode().toString(),
                                name = path.name,
                                absolutePath = path.toString(),
                                sizeBytes = metadata?.size ?: 0L,
                                lastModified = 0L,
                                isDirectory = metadata?.isDirectory ?: false,
                                mimeType = if (metadata?.isDirectory == true) "inode/directory" else "application/octet-stream"
                            )
                        }
                        call.respondText(Json.encodeToString(items), ContentType.Application.Json)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                get("/api/v1/files/stream") {
                    val pathStr = call.request.queryParameters["path"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val filePath = Path(pathStr)
                    
                    if (FileSystem.SYSTEM.exists(filePath)) {
                        call.respondBytesWriter(ContentType.parse("application/octet-stream"), HttpStatusCode.OK) {
                            val channelSink = this.asSink()
                            FileSystem.SYSTEM.source(filePath).buffered().use { source ->
                                channelSink.buffered().use { sink ->
                                    val buffer = ByteArray(8192)
                                    while (!source.exhausted()) {
                                        val read = source.readAtMostTo(buffer)
                                        if (read > 0) {
                                            sink.write(buffer, 0, read)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                post("/api/v1/files/upload") {
                    val targetPathStr = call.request.queryParameters["targetPath"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val targetPath = Path(targetPathStr)
                    
                    val channel = call.receiveChannel()
                    FileSystem.SYSTEM.sink(targetPath).buffered().use { sink ->
                        val buffer = ByteArray(8192)
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read > 0) {
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                    call.respond(HttpStatusCode.Created)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        serverEngine?.stop(1000, 2000)
        serverScope.cancel()
    }
}

```

### Path: `androidMain/kotlin/com/omninode/network/FileShareServerService.kt`

```kotlin
package com.omninode.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class FileShareServerService : Service() {
    private var serverInstance: OmniNodeServer? = null
    private val CHANNEL_ID = "OmniNodeServerChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OmniNode Server Active")
                .setContentText("Local WiFi secure ecosystem running...")
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("OmniNode Server Active")
                .setContentText("Local WiFi secure ecosystem running...")
                .build()
        }
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverInstance = OmniNodeServer(port = 8080).apply { start() }
        return START_STICKY
    }

    override fun onDestroy() {
        serverInstance?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "OmniNode Background Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}

```

---

## 4. Adaptive UI Layer (Compose Multiplatform)

### Path: `shared/src/commonMain/kotlin/com/omninode/ui/AdaptiveExplorerView.kt`

```kotlin
package com.omninode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omninode.domain.model.RemoteFileItem

@Composable
fun AdaptiveExplorerView(
    isWideDisplay: Boolean,
    directories: List<RemoteFileItem>,
    files: List<RemoteFileItem>,
    onItemClick: (RemoteFileItem) -> Unit
) {
    if (isWideDisplay) {
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                items(directories) { dir ->
                    Text(
                        text = "📁 ${dir.name}",
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(1.dp))
            LazyColumn(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                items(files) { file ->
                    Text(
                        text = "📄 ${file.name} (${file.sizeBytes} B)",
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(directories + files) { item ->
                val prefix = if (item.isDirectory) "📁" else "📄"
                Text(
                    text = "$prefix${item.name}",
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
            }
        }
    }
}

```

```

```
package com.fileapex.ui.carousel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.settings.CarouselGate
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private fun carouselAvailabilityFlow(): Flow<Boolean> {
    val platform = currentPlatformLabel()
    return FileApexServices.deviceRepository.observeDevices()
        .map { devices ->
            val selfId = FileApexServices.localIdentity.deviceId
            val peers = devices.map { it.deviceId }
                .filter { it != LocalIdentity.LOCAL_DEVICE_ID && it != selfId }
                .toSet().size
            CarouselGate.available(platform, peers)
        }
        .distinctUntilChanged()
}

/** True on a Mac that has enough paired devices for the Carousel theme. Optimistic on a Mac until the first count. */
@Composable
fun rememberCarouselAvailable(): Boolean {
    val flow = remember { carouselAvailabilityFlow() }
    val available by flow.collectAsState(initial = currentPlatformLabel() == "Mac")
    return available
}

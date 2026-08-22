package com.fileapex.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesFold
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.LaptopWindows
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.fileapex.presentation.DeviceHardwareProfile
import com.fileapex.presentation.DeviceIconKind
import com.fileapex.presentation.DeviceIconProfile
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.resolveDeviceIconKind
import com.fileapex.ui.theme.FileApexTealDark

@Composable
fun DeviceEntryIcon(
    row: DeviceListRow,
    modifier: Modifier = Modifier,
    tint: Color = FileApexTealDark
) {
    DeviceEntryIcon(
        profile = DeviceIconProfile(
            deviceId = row.deviceId,
            deviceName = row.deviceName,
            hardware = DeviceHardwareProfile.from(row)
        ),
        modifier = modifier,
        tint = tint
    )
}

@Composable
fun DeviceEntryIcon(
    profile: DeviceIconProfile,
    modifier: Modifier = Modifier,
    tint: Color = FileApexTealDark
) {
    Icon(
        imageVector = deviceIconVector(resolveDeviceIconKind(profile)),
        contentDescription = deviceIconContentDescription(resolveDeviceIconKind(profile)),
        modifier = modifier,
        tint = tint
    )
}

fun deviceIconVector(kind: DeviceIconKind): ImageVector = when (kind) {
    DeviceIconKind.MacDesktop -> Icons.Filled.LaptopMac
    DeviceIconKind.WindowsPc -> Icons.Filled.LaptopWindows
    DeviceIconKind.FoldablePhone -> Icons.Filled.DevicesFold
    DeviceIconKind.FlipPhone -> Icons.Filled.FlipCameraAndroid
    DeviceIconKind.PixelPhone -> Icons.Filled.Smartphone
    DeviceIconKind.SamsungPhone -> Icons.Filled.PhoneAndroid
    DeviceIconKind.AndroidTablet -> Icons.Filled.TabletAndroid
    DeviceIconKind.AndroidPhone -> Icons.Filled.PhoneAndroid
    DeviceIconKind.GenericDesktop -> Icons.Filled.Computer
}

private fun deviceIconContentDescription(kind: DeviceIconKind): String = when (kind) {
    DeviceIconKind.MacDesktop -> "Mac"
    DeviceIconKind.WindowsPc -> "Windows PC"
    DeviceIconKind.FoldablePhone -> "Foldable phone"
    DeviceIconKind.FlipPhone -> "Flip phone"
    DeviceIconKind.PixelPhone -> "Google Pixel phone"
    DeviceIconKind.SamsungPhone -> "Samsung phone"
    DeviceIconKind.AndroidTablet -> "Tablet"
    DeviceIconKind.AndroidPhone -> "Android phone"
    DeviceIconKind.GenericDesktop -> "Desktop"
}

@Composable
fun DeviceEntryIconLarge(
    row: DeviceListRow,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    DeviceEntryIcon(
        row = row,
        modifier = modifier,
        tint = tint
    )
}

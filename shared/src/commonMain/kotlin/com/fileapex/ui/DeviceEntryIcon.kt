package com.fileapex.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.presentation.DeviceHardwareProfile
import com.fileapex.presentation.DeviceIconKind
import com.fileapex.presentation.DeviceIconProfile
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.resolveDeviceIconKind
import com.fileapex.presentation.resolveFluxDrawable
import com.fileapex.presentation.resolveFreestyleDrawable
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.FileApexTealDark
import org.jetbrains.compose.resources.painterResource

@Composable
fun DeviceEntryIcon(
    row: DeviceListRow,
    modifier: Modifier = Modifier,
    tint: Color = FileApexTealDark,
    iconStyle: ThemeIconStyle = LocalThemeIconStyle.current
) {
    DeviceEntryIcon(
        profile = DeviceIconProfile(
            deviceId = row.deviceId,
            deviceName = row.deviceName,
            hardware = DeviceHardwareProfile.from(row)
        ),
        modifier = modifier,
        tint = tint,
        iconStyle = iconStyle
    )
}

@Composable
fun DeviceEntryIcon(
    profile: DeviceIconProfile,
    modifier: Modifier = Modifier,
    tint: Color = FileApexTealDark,
    iconStyle: ThemeIconStyle = LocalThemeIconStyle.current
) {
    val description = stringRes(deviceIconDescriptionKey(resolveDeviceIconKind(profile)))
    when (iconStyle) {
        ThemeIconStyle.STANDARD -> {
            Icon(
                imageVector = deviceIconVector(resolveDeviceIconKind(profile)),
                contentDescription = description,
                modifier = modifier,
                tint = tint
            )
        }
        ThemeIconStyle.FLUX -> {
            Image(
                painter = painterResource(resolveFluxDrawable(profile)),
                contentDescription = description,
                modifier = modifier.clip(CircleShape),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center
            )
        }
        ThemeIconStyle.FREESTYLE -> {
            Image(
                painter = painterResource(resolveFreestyleDrawable(profile)),
                contentDescription = description,
                modifier = modifier.clip(CircleShape),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center
            )
        }
    }
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

private fun deviceIconDescriptionKey(kind: DeviceIconKind): String = when (kind) {
    DeviceIconKind.MacDesktop -> "icon_mac"
    DeviceIconKind.WindowsPc -> "icon_windows_pc"
    DeviceIconKind.FoldablePhone -> "icon_foldable_phone"
    DeviceIconKind.FlipPhone -> "icon_flip_phone"
    DeviceIconKind.PixelPhone -> "icon_pixel_phone"
    DeviceIconKind.SamsungPhone -> "icon_samsung_phone"
    DeviceIconKind.AndroidTablet -> "icon_tablet"
    DeviceIconKind.AndroidPhone -> "icon_android_phone"
    DeviceIconKind.GenericDesktop -> "icon_desktop"
}

@Composable
fun DeviceEntryIconLarge(
    row: DeviceListRow,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    iconStyle: ThemeIconStyle = LocalThemeIconStyle.current
) {
    DeviceEntryIcon(
        row = row,
        modifier = modifier,
        tint = tint,
        iconStyle = iconStyle
    )
}

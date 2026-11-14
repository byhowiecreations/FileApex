package com.fileapex.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesFold
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.LaptopWindows
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DevicesFold
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.LaptopWindows
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.data.settings.traits
import com.fileapex.presentation.DeviceHardwareProfile
import com.fileapex.presentation.DeviceIconKind
import com.fileapex.presentation.DeviceIconProfile
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.resolveDeviceIconKind
import com.fileapex.presentation.resolveFluxDrawable
import com.fileapex.presentation.resolveFreestyleDrawable
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.CleanModernPalette
import com.fileapex.ui.theme.FileApexTealDark
import com.fileapex.ui.theme.FluxGlassPalette
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Ids of pinned friends shown as device rows. */
const val FRIEND_ROW_PREFIX = "friend:"

@Composable
fun DeviceEntryIcon(
    row: DeviceListRow,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    iconStyle: ThemeIconStyle = LocalThemeIconStyle.current
) {
    if (row.isFriend) {
        com.fileapex.friends.ui.FriendGlyph(row.deviceName, row.friendGroup.orEmpty(), row.deviceId.removePrefix(FRIEND_ROW_PREFIX), modifier)
        return
    }
    DeviceEntryIcon(
        profile = DeviceIconProfile(
            deviceId = row.deviceId,
            deviceName = row.deviceName,
            hardware = DeviceHardwareProfile.from(row),
            clientVersion = row.appVersion.orEmpty()
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
    tint: Color = Color.Unspecified,
    iconStyle: ThemeIconStyle = LocalThemeIconStyle.current
) {
    val kind = resolveDeviceIconKind(profile)
    val description = stringRes(deviceIconDescriptionKey(kind))
    val currentTheme = LocalAppTheme.current
    val useModernOutlined = iconStyle == ThemeIconStyle.STANDARD &&
        (currentTheme == AppTheme.CLEAN || currentTheme.traits.fluxSurfaces)
    val resolvedTint = when {
        tint != Color.Unspecified -> tint
        currentTheme.traits.fluxSurfaces -> FluxGlassPalette.iconBadgeTint
        currentTheme == AppTheme.CLEAN -> CleanModernPalette.IconBadgeTint
        else -> FileApexTealDark
    }
    when (iconStyle) {
        ThemeIconStyle.STANDARD -> {
            Icon(
                imageVector = if (useModernOutlined) deviceOutlinedIconVector(kind) else deviceIconVector(kind),
                contentDescription = description,
                modifier = modifier,
                tint = resolvedTint
            )
        }
        ThemeIconStyle.FLUX -> {
            ThemeDeviceIconImage(
                resource = resolveFluxDrawable(profile),
                contentDescription = description,
                modifier = modifier
            )
        }
        ThemeIconStyle.FREESTYLE -> {
            ThemeDeviceIconImage(
                resource = resolveFreestyleDrawable(profile),
                contentDescription = description,
                modifier = modifier
            )
        }
    }
}

@Composable
internal fun ThemeDeviceIconImage(
    resource: DrawableResource,
    contentDescription: String,
    modifier: Modifier
) {
    val cached = ThemeDeviceIconPreloader.cached(resource)
    if (cached != null) {
        Image(
            bitmap = cached,
            contentDescription = contentDescription,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center
        )
    } else {
        Image(
            painter = painterResource(resource),
            contentDescription = contentDescription,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center
        )
    }
}

fun deviceIconFillsOrb(iconStyle: ThemeIconStyle): Boolean =
    iconStyle != ThemeIconStyle.STANDARD

fun deviceIconVector(kind: DeviceIconKind): ImageVector = when (kind) {
    DeviceIconKind.MacDesktop -> Icons.Filled.LaptopMac
    DeviceIconKind.WindowsPc -> Icons.Filled.LaptopWindows
    DeviceIconKind.Docker -> Icons.Filled.Dns
    DeviceIconKind.LinuxServer -> Icons.Filled.Computer
    DeviceIconKind.FoldablePhone -> Icons.Filled.DevicesFold
    DeviceIconKind.FlipPhone -> Icons.Filled.FlipCameraAndroid
    DeviceIconKind.PixelPhone -> Icons.Filled.Smartphone
    DeviceIconKind.SamsungPhone -> Icons.Filled.PhoneAndroid
    DeviceIconKind.AndroidTablet -> Icons.Filled.TabletAndroid
    DeviceIconKind.AndroidPhone -> Icons.Filled.PhoneAndroid
    DeviceIconKind.GenericDesktop -> Icons.Filled.Computer
}

internal fun deviceOutlinedIconVector(kind: DeviceIconKind): ImageVector = when (kind) {
    DeviceIconKind.MacDesktop -> Icons.Outlined.LaptopMac
    DeviceIconKind.WindowsPc -> Icons.Outlined.LaptopWindows
    DeviceIconKind.Docker -> Icons.Outlined.Dns
    DeviceIconKind.LinuxServer -> Icons.Outlined.Computer
    DeviceIconKind.FoldablePhone -> Icons.Outlined.DevicesFold
    DeviceIconKind.FlipPhone -> Icons.Outlined.FlipCameraAndroid
    DeviceIconKind.PixelPhone -> Icons.Outlined.Smartphone
    DeviceIconKind.SamsungPhone -> Icons.Outlined.PhoneAndroid
    DeviceIconKind.AndroidTablet -> Icons.Outlined.TabletAndroid
    DeviceIconKind.AndroidPhone -> Icons.Outlined.PhoneAndroid
    DeviceIconKind.GenericDesktop -> Icons.Outlined.Computer
}

internal fun deviceIconDescriptionKey(kind: DeviceIconKind): String = when (kind) {
    DeviceIconKind.MacDesktop -> "icon_mac"
    DeviceIconKind.WindowsPc -> "icon_windows_pc"
    DeviceIconKind.Docker -> "icon_docker"
    DeviceIconKind.LinuxServer -> "icon_linux"
    DeviceIconKind.FoldablePhone -> "icon_foldable_phone"
    DeviceIconKind.FlipPhone -> "icon_flip_phone"
    DeviceIconKind.PixelPhone -> "icon_pixel_phone"
    DeviceIconKind.SamsungPhone -> "icon_samsung_phone"
    DeviceIconKind.AndroidTablet -> "icon_tablet"
    DeviceIconKind.AndroidPhone -> "icon_android_phone"
    DeviceIconKind.GenericDesktop -> "icon_desktop"
}

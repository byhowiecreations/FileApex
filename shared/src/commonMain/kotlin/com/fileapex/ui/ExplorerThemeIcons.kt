package com.fileapex.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.automirrored.sharp.*
import androidx.compose.material.icons.automirrored.twotone.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.sharp.*
import androidx.compose.material.icons.twotone.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle

/** Every glyph the file manager's menus and tools draw, so each theme can swap the whole set at once. */
internal enum class ExplorerIcon {
    Open, Info, Select, Copy, SendTo, Favorite, FavoriteOn, Rename, Delete, Compress, Uncompress, Download,
    Images, Videos, Audio, Apk, Documents, Trash, Largest, Restore, Folder, File, Back, Close,
    Duplicates, FolderSizes, Screenshots, OldDownloads, Move,
    GridView, ListView, SplitView, Refresh, Power, CardsVertical, CardsHorizontal
}

internal enum class ExplorerIconFamily { Outlined, Rounded, Sharp, TwoTone }

/** Simple and Clean are outlined, Flux Glass rounded, Kinetic Sphere (and Jaded Steel) sharp-edged, Freestyle two-tone. */
@Composable
internal fun explorerIconFamily(): ExplorerIconFamily = when (LocalAppTheme.current) {
    AppTheme.SIMPLE, AppTheme.CLEAN -> ExplorerIconFamily.Outlined
    AppTheme.FLUX_GLASS -> ExplorerIconFamily.Rounded
    AppTheme.KINETIC_SPHERE ->
        if (LocalKineticStyle.current == KineticStyle.JADED_STEEL) ExplorerIconFamily.Sharp else ExplorerIconFamily.Rounded
    AppTheme.FREESTYLE -> ExplorerIconFamily.TwoTone
}

@Composable
internal fun explorerIcon(icon: ExplorerIcon): ImageVector = explorerIcon(icon, explorerIconFamily())

internal fun explorerIcon(icon: ExplorerIcon, family: ExplorerIconFamily): ImageVector = when (family) {
    ExplorerIconFamily.Outlined -> when (icon) {
        ExplorerIcon.Open -> Icons.Outlined.FolderOpen
        ExplorerIcon.Info -> Icons.Outlined.Info
        ExplorerIcon.Select -> Icons.Outlined.CheckCircle
        ExplorerIcon.Copy -> Icons.Outlined.ContentCopy
        ExplorerIcon.SendTo -> Icons.Outlined.Share
        ExplorerIcon.Favorite -> Icons.Outlined.StarBorder
        ExplorerIcon.FavoriteOn -> Icons.Outlined.Star
        ExplorerIcon.Rename -> Icons.Outlined.Edit
        ExplorerIcon.Delete -> Icons.Outlined.Delete
        ExplorerIcon.Compress -> Icons.Outlined.FolderZip
        ExplorerIcon.Uncompress -> Icons.Outlined.Unarchive
        ExplorerIcon.Download -> Icons.Outlined.Download
        ExplorerIcon.Images -> Icons.Outlined.Image
        ExplorerIcon.Videos -> Icons.Outlined.VideoLibrary
        ExplorerIcon.Audio -> Icons.Outlined.MusicNote
        ExplorerIcon.Apk -> Icons.Outlined.Android
        ExplorerIcon.Documents -> Icons.Outlined.Description
        ExplorerIcon.Trash -> Icons.Outlined.DeleteSweep
        ExplorerIcon.Largest -> Icons.Outlined.DataUsage
        ExplorerIcon.Restore -> Icons.Outlined.RestoreFromTrash
        ExplorerIcon.Folder -> Icons.Outlined.Folder
        ExplorerIcon.File -> Icons.AutoMirrored.Outlined.InsertDriveFile
        ExplorerIcon.Back -> Icons.AutoMirrored.Outlined.ArrowBack
        ExplorerIcon.Close -> Icons.Outlined.Close
        ExplorerIcon.Duplicates -> Icons.Outlined.FileCopy
        ExplorerIcon.FolderSizes -> Icons.Outlined.PieChart
        ExplorerIcon.Screenshots -> Icons.Outlined.Screenshot
        ExplorerIcon.OldDownloads -> Icons.Outlined.Download
        ExplorerIcon.Move -> Icons.AutoMirrored.Outlined.DriveFileMove
        ExplorerIcon.GridView -> Icons.Outlined.GridView
        ExplorerIcon.ListView -> Icons.AutoMirrored.Outlined.ViewList
        ExplorerIcon.SplitView -> Icons.Outlined.VerticalSplit
        ExplorerIcon.Refresh -> Icons.Outlined.Refresh
        ExplorerIcon.Power -> Icons.Outlined.PowerSettingsNew
        ExplorerIcon.CardsVertical -> Icons.Outlined.TableRows
        ExplorerIcon.CardsHorizontal -> Icons.Outlined.ViewColumn
    }
    ExplorerIconFamily.Rounded -> when (icon) {
        ExplorerIcon.Open -> Icons.Rounded.FolderOpen
        ExplorerIcon.Info -> Icons.Rounded.Info
        ExplorerIcon.Select -> Icons.Rounded.CheckCircle
        ExplorerIcon.Copy -> Icons.Rounded.ContentCopy
        ExplorerIcon.SendTo -> Icons.Rounded.Share
        ExplorerIcon.Favorite -> Icons.Rounded.StarBorder
        ExplorerIcon.FavoriteOn -> Icons.Rounded.Star
        ExplorerIcon.Rename -> Icons.Rounded.Edit
        ExplorerIcon.Delete -> Icons.Rounded.Delete
        ExplorerIcon.Compress -> Icons.Rounded.FolderZip
        ExplorerIcon.Uncompress -> Icons.Rounded.Unarchive
        ExplorerIcon.Download -> Icons.Rounded.Download
        ExplorerIcon.Images -> Icons.Rounded.Image
        ExplorerIcon.Videos -> Icons.Rounded.VideoLibrary
        ExplorerIcon.Audio -> Icons.Rounded.MusicNote
        ExplorerIcon.Apk -> Icons.Rounded.Android
        ExplorerIcon.Documents -> Icons.Rounded.Description
        ExplorerIcon.Trash -> Icons.Rounded.DeleteSweep
        ExplorerIcon.Largest -> Icons.Rounded.DataUsage
        ExplorerIcon.Restore -> Icons.Rounded.RestoreFromTrash
        ExplorerIcon.Folder -> Icons.Rounded.Folder
        ExplorerIcon.File -> Icons.AutoMirrored.Rounded.InsertDriveFile
        ExplorerIcon.Back -> Icons.AutoMirrored.Rounded.ArrowBack
        ExplorerIcon.Close -> Icons.Rounded.Close
        ExplorerIcon.Duplicates -> Icons.Rounded.FileCopy
        ExplorerIcon.FolderSizes -> Icons.Rounded.PieChart
        ExplorerIcon.Screenshots -> Icons.Rounded.Screenshot
        ExplorerIcon.OldDownloads -> Icons.Rounded.Download
        ExplorerIcon.Move -> Icons.AutoMirrored.Rounded.DriveFileMove
        ExplorerIcon.GridView -> Icons.Rounded.GridView
        ExplorerIcon.ListView -> Icons.AutoMirrored.Rounded.ViewList
        ExplorerIcon.SplitView -> Icons.Rounded.VerticalSplit
        ExplorerIcon.Refresh -> Icons.Rounded.Refresh
        ExplorerIcon.Power -> Icons.Rounded.PowerSettingsNew
        ExplorerIcon.CardsVertical -> Icons.Rounded.TableRows
        ExplorerIcon.CardsHorizontal -> Icons.Rounded.ViewColumn
    }
    ExplorerIconFamily.Sharp -> when (icon) {
        ExplorerIcon.Open -> Icons.Sharp.FolderOpen
        ExplorerIcon.Info -> Icons.Sharp.Info
        ExplorerIcon.Select -> Icons.Sharp.CheckCircle
        ExplorerIcon.Copy -> Icons.Sharp.ContentCopy
        ExplorerIcon.SendTo -> Icons.Sharp.Share
        ExplorerIcon.Favorite -> Icons.Sharp.StarBorder
        ExplorerIcon.FavoriteOn -> Icons.Sharp.Star
        ExplorerIcon.Rename -> Icons.Sharp.Edit
        ExplorerIcon.Delete -> Icons.Sharp.Delete
        ExplorerIcon.Compress -> Icons.Sharp.FolderZip
        ExplorerIcon.Uncompress -> Icons.Sharp.Unarchive
        ExplorerIcon.Download -> Icons.Sharp.Download
        ExplorerIcon.Images -> Icons.Sharp.Image
        ExplorerIcon.Videos -> Icons.Sharp.VideoLibrary
        ExplorerIcon.Audio -> Icons.Sharp.MusicNote
        ExplorerIcon.Apk -> Icons.Sharp.Android
        ExplorerIcon.Documents -> Icons.Sharp.Description
        ExplorerIcon.Trash -> Icons.Sharp.DeleteSweep
        ExplorerIcon.Largest -> Icons.Sharp.DataUsage
        ExplorerIcon.Restore -> Icons.Sharp.RestoreFromTrash
        ExplorerIcon.Folder -> Icons.Sharp.Folder
        ExplorerIcon.File -> Icons.AutoMirrored.Sharp.InsertDriveFile
        ExplorerIcon.Back -> Icons.AutoMirrored.Sharp.ArrowBack
        ExplorerIcon.Close -> Icons.Sharp.Close
        ExplorerIcon.Duplicates -> Icons.Sharp.FileCopy
        ExplorerIcon.FolderSizes -> Icons.Sharp.PieChart
        ExplorerIcon.Screenshots -> Icons.Sharp.Screenshot
        ExplorerIcon.OldDownloads -> Icons.Sharp.Download
        ExplorerIcon.Move -> Icons.AutoMirrored.Sharp.DriveFileMove
        ExplorerIcon.GridView -> Icons.Sharp.GridView
        ExplorerIcon.ListView -> Icons.AutoMirrored.Sharp.ViewList
        ExplorerIcon.SplitView -> Icons.Sharp.VerticalSplit
        ExplorerIcon.Refresh -> Icons.Sharp.Refresh
        ExplorerIcon.Power -> Icons.Sharp.PowerSettingsNew
        ExplorerIcon.CardsVertical -> Icons.Sharp.TableRows
        ExplorerIcon.CardsHorizontal -> Icons.Sharp.ViewColumn
    }
    ExplorerIconFamily.TwoTone -> when (icon) {
        ExplorerIcon.Open -> Icons.TwoTone.FolderOpen
        ExplorerIcon.Info -> Icons.TwoTone.Info
        ExplorerIcon.Select -> Icons.TwoTone.CheckCircle
        ExplorerIcon.Copy -> Icons.TwoTone.ContentCopy
        ExplorerIcon.SendTo -> Icons.TwoTone.Share
        ExplorerIcon.Favorite -> Icons.TwoTone.StarBorder
        ExplorerIcon.FavoriteOn -> Icons.TwoTone.Star
        ExplorerIcon.Rename -> Icons.TwoTone.Edit
        ExplorerIcon.Delete -> Icons.TwoTone.Delete
        ExplorerIcon.Compress -> Icons.TwoTone.FolderZip
        ExplorerIcon.Uncompress -> Icons.TwoTone.Unarchive
        ExplorerIcon.Download -> Icons.TwoTone.Download
        ExplorerIcon.Images -> Icons.TwoTone.Image
        ExplorerIcon.Videos -> Icons.TwoTone.VideoLibrary
        ExplorerIcon.Audio -> Icons.TwoTone.MusicNote
        ExplorerIcon.Apk -> Icons.TwoTone.Android
        ExplorerIcon.Documents -> Icons.TwoTone.Description
        ExplorerIcon.Trash -> Icons.TwoTone.DeleteSweep
        ExplorerIcon.Largest -> Icons.TwoTone.DataUsage
        ExplorerIcon.Restore -> Icons.TwoTone.RestoreFromTrash
        ExplorerIcon.Folder -> Icons.TwoTone.Folder
        ExplorerIcon.File -> Icons.AutoMirrored.TwoTone.InsertDriveFile
        ExplorerIcon.Back -> Icons.AutoMirrored.TwoTone.ArrowBack
        ExplorerIcon.Close -> Icons.TwoTone.Close
        ExplorerIcon.Duplicates -> Icons.TwoTone.FileCopy
        ExplorerIcon.FolderSizes -> Icons.TwoTone.PieChart
        ExplorerIcon.Screenshots -> Icons.TwoTone.Screenshot
        ExplorerIcon.OldDownloads -> Icons.TwoTone.Download
        ExplorerIcon.Move -> Icons.AutoMirrored.TwoTone.DriveFileMove
        ExplorerIcon.GridView -> Icons.TwoTone.GridView
        ExplorerIcon.ListView -> Icons.AutoMirrored.TwoTone.ViewList
        ExplorerIcon.SplitView -> Icons.TwoTone.VerticalSplit
        ExplorerIcon.Refresh -> Icons.TwoTone.Refresh
        ExplorerIcon.Power -> Icons.TwoTone.PowerSettingsNew
        ExplorerIcon.CardsVertical -> Icons.TwoTone.TableRows
        ExplorerIcon.CardsHorizontal -> Icons.TwoTone.ViewColumn
    }
}

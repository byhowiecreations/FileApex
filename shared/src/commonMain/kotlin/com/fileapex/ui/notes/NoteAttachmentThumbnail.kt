package com.fileapex.ui.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes

@Composable
internal fun NoteAttachmentThumbnail(thumbnail: ImageBitmap, name: String, textColor: Color) {
    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            bitmap = thumbnail,
            contentDescription = stringRes("open_image"),
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

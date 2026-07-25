package com.fileapex.data.db

import android.content.Context

fun createFileApexDatabase(context: Context): FileApexDatabase {
    return RoomDbBuilder(context).builder().build()
}

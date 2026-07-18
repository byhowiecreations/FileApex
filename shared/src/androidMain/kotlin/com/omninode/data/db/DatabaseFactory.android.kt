package com.omninode.data.db

import android.content.Context

fun createOmniNodeDatabase(context: Context): OmniNodeDatabase {
    return RoomDbBuilder(context).builder().build()
}

package com.omninode.data.db

fun createOmniNodeDatabase(): OmniNodeDatabase {
    return RoomDbBuilder().builder().build()
}

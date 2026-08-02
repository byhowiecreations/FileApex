package com.fileapex.data.db

fun createFileApexDatabase(): FileApexDatabase {
    return RoomDbBuilder().builder().build()
}

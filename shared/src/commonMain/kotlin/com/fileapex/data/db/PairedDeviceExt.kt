package com.fileapex.data.db

/** The Docker daemon identifies itself with this client version. */
fun PairedDeviceEntity.isDockerNode(): Boolean = clientVersion.trim().equals("docker", ignoreCase = true)

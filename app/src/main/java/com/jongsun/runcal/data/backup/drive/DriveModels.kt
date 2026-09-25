package com.jongsun.runcal.data.backup.drive

import kotlinx.serialization.Serializable

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val modifiedTime: String? = null,
    val size: String? = null,
)

@Serializable
data class DriveFileListResponse(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

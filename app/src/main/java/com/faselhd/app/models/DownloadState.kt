package com.faselhd.app.models

enum class DownloadState {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED
}
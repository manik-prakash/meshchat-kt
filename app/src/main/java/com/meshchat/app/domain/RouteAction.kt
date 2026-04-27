package com.meshchat.app.domain

enum class RouteAction {
    ORIGINATED,
    FORWARDED,
    DELIVERED,
    DROPPED_TTL,
    DROPPED_DUPLICATE,
    DROPPED_INVALID_SIG,
    QUEUED,
    DEQUEUED
}

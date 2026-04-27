package com.meshchat.app.domain

enum class RouteAction {
    ORIGINATED,
    FORWARDED,
    DELIVERED,
    DROPPED_TTL,
    DROPPED_DUPLICATE,
    QUEUED,
    DEQUEUED
}

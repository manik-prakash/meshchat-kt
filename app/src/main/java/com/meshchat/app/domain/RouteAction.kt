package com.meshchat.app.domain

enum class RouteAction {
    ORIGINATED,
    FORWARDED,
    /** Packet advanced in perimeter (void-bypass) mode. Logged with void hop count in reason. */
    PERIMETER_HOP,
    /** Perimeter void-hop budget exhausted; packet held in queue for a better neighbor. */
    VOID_LIMIT_EXCEEDED,
    DELIVERED,
    DROPPED_TTL,
    DROPPED_DUPLICATE,
    DROPPED_INVALID_SIG,
    QUEUED,
    DEQUEUED
}

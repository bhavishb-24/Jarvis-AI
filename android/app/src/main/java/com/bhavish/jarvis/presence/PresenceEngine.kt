package com.bhavish.jarvis.presence

enum class PresenceSignal { WIFI, BLUETOOTH, CAMERA_PERSON, CAMERA_MOTION, SOUND, EXTERNAL, DESKTOP }
enum class PresenceState { ABSENT, ARRIVING, PRESENT, LEAVING }
enum class PresenceTransition { NONE, ARRIVED, DEPARTED }

data class PresenceEvidence(val signal: PresenceSignal, val confidence: Double, val observedAt: Long)
data class PresenceDecision(val state: PresenceState, val transition: PresenceTransition, val score: Double)

/** Pure, monotonic-time state machine. No images or audio samples are stored here. */
class PresenceEngine(
    private val arrivalThreshold: Double = 0.7,
    private val departureThreshold: Double = 0.25,
    private val arrivalDwellMs: Long = 15_000,
    private val departureDwellMs: Long = 60_000,
    private val signalTtlMs: Long = 20_000,
    private val cooldownMs: Long = 120_000
) {
    private val weights = mapOf(
        PresenceSignal.WIFI to 0.22,
        PresenceSignal.BLUETOOTH to 0.23,
        PresenceSignal.CAMERA_PERSON to 0.40,
        PresenceSignal.CAMERA_MOTION to 0.12,
        PresenceSignal.SOUND to 0.08,
        PresenceSignal.EXTERNAL to 0.45,
        PresenceSignal.DESKTOP to 0.20
    )
    private val evidence = mutableMapOf<PresenceSignal, PresenceEvidence>()
    private var state = PresenceState.ABSENT
    private var candidateSince: Long? = null
    private var lastTransitionAt = Long.MIN_VALUE

    fun record(value: PresenceEvidence) {
        require(value.confidence in 0.0..1.0)
        evidence[value.signal] = value
    }

    fun evaluate(now: Long): PresenceDecision {
        val fresh = evidence.values.filter { it.observedAt <= now && now - it.observedAt <= signalTtlMs }
        val score = fresh.sumOf { (weights.getValue(it.signal) * it.confidence) }.coerceAtMost(1.0)
        val visualOrProximity = fresh.any {
            it.confidence >= 0.5 && it.signal in setOf(PresenceSignal.BLUETOOTH,
                PresenceSignal.CAMERA_PERSON, PresenceSignal.EXTERNAL)
        }
        val independent = fresh.count { it.confidence >= 0.5 && it.signal != PresenceSignal.SOUND } >= 2
        val arrival = score >= arrivalThreshold && visualOrProximity && independent
        val departure = score <= departureThreshold
        val cooledDown = lastTransitionAt == Long.MIN_VALUE || now - lastTransitionAt >= cooldownMs
        var transition = PresenceTransition.NONE
        when (state) {
            PresenceState.ABSENT -> if (arrival && cooledDown) {
                state = PresenceState.ARRIVING
                candidateSince = now
            }
            PresenceState.ARRIVING -> if (!arrival) {
                state = PresenceState.ABSENT
                candidateSince = null
            } else if (now - (candidateSince ?: now) >= arrivalDwellMs) {
                state = PresenceState.PRESENT
                candidateSince = null
                lastTransitionAt = now
                transition = PresenceTransition.ARRIVED
            }
            PresenceState.PRESENT -> if (departure && cooledDown) {
                state = PresenceState.LEAVING
                candidateSince = now
            }
            PresenceState.LEAVING -> if (!departure) {
                state = PresenceState.PRESENT
                candidateSince = null
            } else if (now - (candidateSince ?: now) >= departureDwellMs) {
                state = PresenceState.ABSENT
                candidateSince = null
                lastTransitionAt = now
                transition = PresenceTransition.DEPARTED
            }
        }
        return PresenceDecision(state, transition, score)
    }

    fun clear() {
        evidence.clear()
        state = PresenceState.ABSENT
        candidateSince = null
    }
}

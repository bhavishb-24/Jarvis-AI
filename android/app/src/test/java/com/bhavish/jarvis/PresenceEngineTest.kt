package com.bhavish.jarvis

import com.bhavish.jarvis.presence.PresenceEngine
import com.bhavish.jarvis.presence.PresenceEvidence
import com.bhavish.jarvis.presence.PresenceSignal
import com.bhavish.jarvis.presence.PresenceState
import com.bhavish.jarvis.presence.PresenceTransition
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceEngineTest {
    @Test fun soundAndWifiCannotTriggerArrival() {
        val engine = PresenceEngine(arrivalThreshold = 0.2, arrivalDwellMs = 0)
        engine.record(PresenceEvidence(PresenceSignal.SOUND, 1.0, 0))
        engine.record(PresenceEvidence(PresenceSignal.WIFI, 1.0, 0))
        assertEquals(PresenceState.ABSENT, engine.evaluate(0).state)
    }

    @Test fun independentSignalsRequireDwellAndDepartureConfirmation() {
        val engine = PresenceEngine(arrivalThreshold = 0.6, arrivalDwellMs = 1000,
            departureDwellMs = 2000, signalTtlMs = 1500, cooldownMs = 0)
        engine.record(PresenceEvidence(PresenceSignal.CAMERA_PERSON, 1.0, 0))
        engine.record(PresenceEvidence(PresenceSignal.BLUETOOTH, 1.0, 0))
        assertEquals(PresenceState.ARRIVING, engine.evaluate(0).state)
        assertEquals(PresenceTransition.ARRIVED, engine.evaluate(1000).transition)
        assertEquals(PresenceState.LEAVING, engine.evaluate(2000).state)
        assertEquals(PresenceTransition.DEPARTED, engine.evaluate(4000).transition)
    }

    @Test fun staleSignalsAndBriefAbsenceDoNotCauseDeparture() {
        val engine = PresenceEngine(arrivalThreshold = 0.6, arrivalDwellMs = 0,
            departureDwellMs = 2000, signalTtlMs = 1000, cooldownMs = 0)
        engine.record(PresenceEvidence(PresenceSignal.CAMERA_PERSON, 1.0, 0))
        engine.record(PresenceEvidence(PresenceSignal.BLUETOOTH, 1.0, 0))
        engine.evaluate(0)
        engine.evaluate(0)
        assertEquals(PresenceState.LEAVING, engine.evaluate(1100).state)
        engine.record(PresenceEvidence(PresenceSignal.CAMERA_PERSON, 1.0, 1200))
        engine.record(PresenceEvidence(PresenceSignal.BLUETOOTH, 1.0, 1200))
        assertEquals(PresenceState.PRESENT, engine.evaluate(1200).state)
    }
}

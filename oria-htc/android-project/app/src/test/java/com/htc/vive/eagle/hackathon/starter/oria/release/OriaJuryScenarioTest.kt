package com.htc.vive.eagle.hackathon.starter.oria.release

import com.htc.vive.eagle.hackathon.starter.oria.audio.*
import com.htc.vive.eagle.hackathon.starter.oria.core.*
import com.htc.vive.eagle.hackathon.starter.oria.navigation.*
import org.junit.Assert.*
import org.junit.Test

/** Five virtual minutes through production policy owners. This is not a physical demo. */
class OriaJuryScenarioTest {
    @Test fun threeConsecutiveFiveMinuteScenariosResetEveryOwnerAndReachArrival() {
        val runs = (1L..3L).map(::runScenario)
        assertEquals(3, runs.size)
        runs.forEach { result ->
            assertEquals(300_000L, result.virtualDurationMs)
            assertTrue(result.multiObject)
            assertTrue(result.prioritySelected)
            assertTrue(result.escalated)
            assertTrue(result.descriptionDispatched)
            assertTrue(result.twoManeuvers)
            assertTrue(result.dangerPreemptedNavigation)
            assertTrue(result.navigationResumedFresh)
            assertTrue(result.recalculated)
            assertTrue(result.arrived)
            assertTrue(result.oldGenerationRejected)
        }
    }

    private fun runScenario(generation: Long): Result {
        val rgb = RgbAlertEngine(RgbAlertConfig(confirmationSamples = 1)).also { it.start(generation, 0) }
        val audio = OriaAudioScheduler().also { it.reset(generation) }
        val navigation = NavigationEngine()
        val origin = GeoPoint(48.85660, 2.35220)
        val turn = GeoPoint(48.85680, 2.35220)
        val destination = GeocodedPlace("jury", "Destination jury", GeoPoint(48.85680, 2.35260))
        val route1 = route(1, origin, turn, destination)
        navigation.start(generation, route1, 1_000)

        val firstNavigation = (navigation.onLocation(fix(generation, 1_100, origin)).single() as NavigationEvent.Speak).speech
        audio.offer(audio(firstNavigation.id, generation, OriaAudioKind.NAVIGATION,
            OriaAudioPriority.NAVIGATION, firstNavigation.text, 1_100, 9_100), 1_100)

        val detections = listOf(
            Detection(0, .96f, Box(.08f, .20f, .32f, .92f)),
            Detection(1, .91f, Box(.42f, .15f, .68f, .96f)),
        )
        val perception = rgb.evaluate(DetectionFrame(generation, 1, 1_200, detections), 1_200)
        val danger = audio.offer(audio("danger", generation, OriaAudioKind.DANGER,
            OriaAudioPriority.DANGER_HIGH, "Danger prioritaire", 1_200, 1_700), 1_200)
        navigation.audioInterrupted(1_200)
        val escalation = audio.offer(audio("critical", generation, OriaAudioKind.DANGER,
            OriaAudioPriority.DANGER_CRITICAL, "Danger critique", 1_250, 1_750), 1_250)
        audio.finish("critical", generation, 1_300)

        val description = audio.offer(audio("description", generation, OriaAudioKind.COMMAND_RESPONSE,
            OriaAudioPriority.DESCRIPTION, "Devant : véhicule et piéton.", 1_350, 8_000), 1_350)
        audio.finish("description", generation, 1_400)
        val resumed = navigation.onLocation(fix(generation, 1_500, origin))
        val secondManeuver = navigation.onLocation(fix(generation, 2_000, turn))

        val far = GeoPoint(48.85850, 2.35500)
        navigation.onLocation(fix(generation, 3_000, far))
        val recalculation = navigation.onLocation(fix(generation, 3_100, far))
        val route2 = route(2, origin, turn, destination)
        assertTrue(navigation.replaceRoute(generation, route2, 3_200))
        navigation.onLocation(fix(generation, 290_000, destination.point))
        val arrival = navigation.onLocation(fix(generation, 299_000, destination.point))

        audio.reset(generation + 1)
        val old = audio.offer(audio("old", generation, OriaAudioKind.DANGER,
            OriaAudioPriority.DANGER_CRITICAL, "ancienne sortie", 299_500, 300_000), 299_500)
        return Result(
            virtualDurationMs = 300_000,
            multiObject = perception.tracks.size == 2,
            prioritySelected = perception.selected != null,
            escalated = escalation.cancel?.id == "danger" && escalation.dispatch?.id == "critical",
            descriptionDispatched = description.dispatch?.id == "description",
            twoManeuvers = secondManeuver.filterIsInstance<NavigationEvent.Speak>().any {
                it.speech.text.contains("droite")
            },
            dangerPreemptedNavigation = danger.cancel?.id == firstNavigation.id,
            navigationResumedFresh = resumed.filterIsInstance<NavigationEvent.Speak>().isNotEmpty(),
            recalculated = recalculation.singleOrNull() is NavigationEvent.Recalculate,
            arrived = arrival.singleOrNull() is NavigationEvent.Arrived &&
                navigation.snapshot().phase == NavigationPhase.ARRIVED,
            oldGenerationRejected = old.reason == OriaAudioDecisionReason.OLD_GENERATION,
        )
    }

    private fun route(version: Long, origin: GeoPoint, turn: GeoPoint, destination: GeocodedPlace) =
        NavigationRoute(version, destination, listOf(origin, turn, destination.point), listOf(
            RouteManeuver("straight-$version", version, 0, "Avancez tout droit", turn),
            RouteManeuver("right-$version", version, 1, "Tournez à droite", destination.point),
        ), 60.0, 50.0, false, "fixture jury")

    private fun fix(generation: Long, at: Long, point: GeoPoint) =
        LocationFix(generation, at, point, 3f)

    private fun audio(id: String, generation: Long, kind: OriaAudioKind,
                      priority: OriaAudioPriority, text: String, at: Long, expires: Long) =
        OriaAudioRequest(id, generation, kind, priority, text, SpeechPan.CENTER, at, at, expires)

    private data class Result(
        val virtualDurationMs: Long,
        val multiObject: Boolean,
        val prioritySelected: Boolean,
        val escalated: Boolean,
        val descriptionDispatched: Boolean,
        val twoManeuvers: Boolean,
        val dangerPreemptedNavigation: Boolean,
        val navigationResumedFresh: Boolean,
        val recalculated: Boolean,
        val arrived: Boolean,
        val oldGenerationRejected: Boolean,
    )
}

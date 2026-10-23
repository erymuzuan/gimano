package my.gimano.app.core

import my.gimano.app.core.format.Lang
import my.gimano.app.core.nav.ManeuverFormatter
import my.gimano.app.core.nav.ManeuverIcon
import my.gimano.app.data.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverFormatterTest {
    private fun m(type: String, street: String? = null, ref: String? = null, exit: String? = null, instr: String = "") =
        Maneuver(type = type, instruction = instr, streetName = street, roadRef = ref, exitNumber = exit)

    @Test fun turnsInEnglish() {
        assertEquals("Turn left onto Jalan Ampang", ManeuverFormatter.instruction(m("left", "Jalan Ampang"), Lang.EN))
        assertEquals("Turn right", ManeuverFormatter.instruction(m("right"), Lang.EN))
        assertEquals("Bear left onto Jalan Duta", ManeuverFormatter.instruction(m("slight_left", "Jalan Duta"), Lang.EN))
        assertEquals("Make a U-turn", ManeuverFormatter.instruction(m("uturn_right"), Lang.EN))
        assertEquals("Continue straight on Jalan Tun Razak", ManeuverFormatter.instruction(m("straight", "Jalan Tun Razak"), Lang.EN))
    }

    @Test fun turnsInMalay() {
        assertEquals("Belok kiri ke Jalan Ampang", ManeuverFormatter.instruction(m("left", "Jalan Ampang"), Lang.MS))
        assertEquals("Belok kanan", ManeuverFormatter.instruction(m("right"), Lang.MS))
        assertEquals("Kekal di kiri di Lebuhraya Mahameru", ManeuverFormatter.instruction(m("keep_left", "Lebuhraya Mahameru"), Lang.MS))
        assertEquals("Anda telah tiba", ManeuverFormatter.instruction(m("arrive", "Suria KLCC"), Lang.MS))
    }

    @Test fun exitsAndRoundabouts() {
        assertEquals("Take exit 1107 on the left towards E11 Lebuhraya Damansara–Puchong",
            ManeuverFormatter.instruction(m("exit_left", "Lebuhraya Damansara–Puchong", "E11", "1107"), Lang.EN))
        assertEquals("Ambil susur keluar 1107 di sebelah kiri menuju E11 Lebuhraya Damansara–Puchong",
            ManeuverFormatter.instruction(m("exit_left", "Lebuhraya Damansara–Puchong", "E11", "1107"), Lang.MS))
        assertEquals("At the roundabout, take exit 2 onto Jalan Kuching", ManeuverFormatter.instruction(m("roundabout_enter", "Jalan Kuching", exit = "2"), Lang.EN))
        assertEquals("Di bulatan, ambil susur keluar 2", ManeuverFormatter.instruction(m("roundabout_enter", exit = "2"), Lang.MS))
    }

    @Test fun roadNameCombinesRefWithoutDuplicating() {
        assertEquals("E11 LDP", ManeuverFormatter.road(m("left", "LDP", "E11")))
        assertEquals("E11 LDP", ManeuverFormatter.road(m("left", "E11 LDP", "E11")))
        assertEquals("E23", ManeuverFormatter.road(m("left", null, "E23")))
        assertEquals(null, ManeuverFormatter.road(m("left", " ", null)))
    }

    @Test fun unknownTypeFallsBackToServerInstruction() {
        assertEquals("Do the thing", ManeuverFormatter.instruction(m("teleport", instr = "Do the thing"), Lang.EN))
    }

    @Test fun spokenPrompts() {
        assertEquals("In 500 metres, turn left onto Jalan Ampang", ManeuverFormatter.spokenApproach(m("left", "Jalan Ampang"), 490.0, Lang.EN))
        assertEquals("Dalam 2 kilometer, belok kanan ke Jalan Duta", ManeuverFormatter.spokenApproach(m("right", "Jalan Duta"), 2_000.0, Lang.MS))
        assertEquals("1.5 kilometres", ManeuverFormatter.spokenDistance(1_520.0, Lang.EN))
        assertEquals("1 kilometre", ManeuverFormatter.spokenDistance(1_000.0, Lang.EN))
        assertEquals("80 metres", ManeuverFormatter.spokenDistance(84.0, Lang.EN))
    }

    @Test fun iconMapping() {
        assertEquals(ManeuverIcon.LEFT, ManeuverFormatter.icon("left"))
        assertEquals(ManeuverIcon.ROUNDABOUT, ManeuverFormatter.icon("roundabout_exit"))
        assertEquals(ManeuverIcon.RAMP_RIGHT, ManeuverFormatter.icon("exit_right"))
        assertEquals(ManeuverIcon.FORK_LEFT, ManeuverFormatter.icon("keep_left"))
        assertEquals(ManeuverIcon.STRAIGHT, ManeuverFormatter.icon("something_new"))
    }
}

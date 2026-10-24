package my.gimano.app.core.events

import java.time.LocalTime

/*
 * The "Acara baharu" wizard (canvas EwFlow): Jenis → Bila → (Di mana | Laluan) → Info → Semak. Template defaults
 * come from the EwFlow table. Suggestions are only chips: nothing is added to an event without a tap.
 */

/** One suggested time ("11:00 – 16:00"). */
data class TimeChoice(val start: LocalTime, val end: LocalTime)

/** A suggested extra place: stop [kind] plus an optional preset label key (e.g. "lobby" → "Lobi"). */
data class PlaceChoice(val kind: String, val preset: String? = null)

data class TemplateDefaults(
    val template: String,
    /** Has `routes`: "Perjalanan" (Laluan step), else "Satu tempat" (Di mana step). */
    val journey: Boolean,
    val times: List<TimeChoice>,
    /** "hari": suggest "Lebih dari sehari". */
    val days: Boolean,
    val places: List<PlaceChoice>,
    /** Info chips suggested first: [HostInfo.AGENDA] or an `info[].kind`. */
    val info: List<String>,
    /** Travel mode of a journey's routes. */
    val mode: String = EventTravelMode.CAR.wire,
)

object EventWizard {
    enum class Step { TYPE, WHEN, PLACE, INFO, REVIEW }

    val PLACE_TEMPLATES = listOf("kenduri", "meeting", "seminar", "dinner", "picnic", "other")
    val JOURNEY_TEMPLATES = listOf("trip", "company", "ride", "convoy", "hike")

    private fun t(a: String, b: String) = TimeChoice(LocalTime.parse(a), LocalTime.parse(b))
    private val DAY = t("08:00", "18:00")

    private val TABLE: Map<String, TemplateDefaults> = listOf(
        TemplateDefaults("kenduri", false, listOf(t("11:00", "16:00"), t("19:30", "22:30")), false,
            listOf(PlaceChoice("parking"), PlaceChoice("entrance"), PlaceChoice("dropoff")), listOf(HostInfo.AGENDA, "dress")),
        TemplateDefaults("meeting", false, listOf(t("09:00", "12:00"), t("14:00", "17:00")), false,
            listOf(PlaceChoice("parking"), PlaceChoice("entrance", "lobby")), listOf("documents", "contact")),
        TemplateDefaults("seminar", false, listOf(t("08:30", "17:00")), true,
            listOf(PlaceChoice("parking"), PlaceChoice("entrance", "registration")), listOf(HostInfo.AGENDA, "documents")),
        TemplateDefaults("dinner", false, listOf(t("19:30", "22:30")), false, listOf(PlaceChoice("parking")), listOf("dress")),
        TemplateDefaults("picnic", false, listOf(t("09:00", "17:00")), false,
            listOf(PlaceChoice("parking"), PlaceChoice("other", "toilet"), PlaceChoice("prayer")), listOf("bring", "safety")),
        TemplateDefaults("other", false, listOf(t("10:00", "12:00")), false, listOf(PlaceChoice("parking")), emptyList()),
        TemplateDefaults("trip", true, listOf(DAY), true,
            listOf(PlaceChoice("rest"), PlaceChoice("fuel"), PlaceChoice("accommodation")), listOf("bring", "documents")),
        TemplateDefaults("company", true, listOf(DAY), true,
            listOf(PlaceChoice("other", "gather"), PlaceChoice("rest")), listOf("contact", "documents")),
        TemplateDefaults("ride", true, listOf(t("07:00", "13:00")), false,
            listOf(PlaceChoice("water"), PlaceChoice("checkpoint")), listOf("safety", "bring"), EventTravelMode.BICYCLE.wire),
        TemplateDefaults("convoy", true, listOf(DAY), true,
            listOf(PlaceChoice("fuel"), PlaceChoice("rest")), listOf("safety"), EventTravelMode.MOTORCYCLE.wire),
        TemplateDefaults("hike", true, listOf(t("07:00", "12:00")), false,
            listOf(PlaceChoice("water"), PlaceChoice("checkpoint")), listOf("safety", "bring"), EventTravelMode.WALK.wire),
    ).associateBy { it.template }

    fun defaults(template: String): TemplateDefaults = TABLE[template] ?: TABLE.getValue("other")

    fun isJourney(template: String): Boolean = template in JOURNEY_TEMPLATES

    /** Extra places offered on "Di mana" (always these five, the template's first). */
    val VENUE_CHIPS = listOf(PlaceChoice("parking"), PlaceChoice("entrance"), PlaceChoice("dropoff"), PlaceChoice("prayer"), PlaceChoice("other"))

    /** Stops offered on "Laluan": Hentian, Air, Rehat, then the template's own (Minyak, Penginapan, Checkpoint…). */
    fun routeChips(template: String): List<PlaceChoice> =
        (listOf(PlaceChoice("other", "stop"), PlaceChoice("water"), PlaceChoice("rest")) + defaults(template).places).distinctBy { it.kind + "|" + it.preset }

    /** Info chips: the template's suggestions first, then every other kind; kinds already on the event are left out (except "other"). */
    fun infoChips(template: String, present: Collection<String>): List<String> {
        val all = listOf(HostInfo.AGENDA, "dress", "safety", "documents", "bring", "contact", "other")
        return (defaults(template).info + all).distinct().filter { it == "other" || it !in present }
    }

    /** The journey's default travel mode: the wizard's choice, else the template's. */
    fun mode(d: HostDraft): String = d.mode?.takeIf { m -> EventTravelMode.entries.any { it.wire == m } } ?: defaults(d.template).mode

    /**
     * Template chosen on "Jenis": the time defaults to the template's first suggestion, and the name prefix follows
     * (only while the name is still empty or just the previous template's prefix).
     */
    fun chooseTemplate(d: HostDraft, template: String, prefix: (String) -> String, untouchedTime: Boolean): HostDraft {
        val def = defaults(template)
        val oldPrefix = prefix(d.template)
        val title = if (d.title.isBlank() || d.title == oldPrefix) prefix(template) else d.title
        var out = d.copy(template = template, title = title, mode = null)
        if (untouchedTime) out = out.copy(startTime = hm(def.times.first().start), endTime = hm(def.times.first().end))
        return out
    }

    fun hm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    /** The steps a draft can be on; [Step.PLACE] is "Di mana" or "Laluan" by template. */
    val STEPS = Step.entries

    /** What the next step needs before "Seterusnya" (no message = fine). */
    fun blocking(d: HostDraft, step: Step): DraftIssue.Code? = when (step) {
        Step.TYPE -> null
        Step.WHEN -> when {
            d.title.isBlank() -> DraftIssue.Code.TITLE_REQUIRED
            HostDraftRules.chars(d.title.trim()) > EventLimits.TITLE_CHARS -> DraftIssue.Code.TITLE_TOO_LONG
            runCatching { d.start(); d.end() }.isFailure -> DraftIssue.Code.BAD_TIME
            runCatching { java.time.Duration.between(d.start(), d.end()) > java.time.Duration.ofDays(EventLimits.MAX_EVENT_DAYS) }.getOrDefault(false) ->
                DraftIssue.Code.TOO_LONG_EVENT
            else -> null
        }
        Step.PLACE -> if (d.stops.isEmpty()) DraftIssue.Code.NO_STOPS else null
        Step.INFO, Step.REVIEW -> null
    }

    /** The wizard step to fix [issue] in (Semak's rows and "Kongsi jemputan" problems jump there). */
    fun stepFor(issue: DraftIssue): Step = when (issue.code) {
        DraftIssue.Code.TITLE_REQUIRED, DraftIssue.Code.TITLE_TOO_LONG, DraftIssue.Code.BAD_TIME, DraftIssue.Code.TOO_LONG_EVENT -> Step.WHEN
        DraftIssue.Code.ALIAS_TOO_LONG, DraftIssue.Code.DESCRIPTION_TOO_LONG, DraftIssue.Code.EXPIRED, DraftIssue.Code.INFO_TOO_LONG,
        DraftIssue.Code.TOO_MANY_INFO, DraftIssue.Code.AGENDA_INVALID -> Step.INFO
        DraftIssue.Code.INVALID -> Step.REVIEW
        else -> Step.PLACE
    }
}

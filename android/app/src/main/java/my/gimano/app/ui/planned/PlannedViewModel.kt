package my.gimano.app.ui.planned

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import my.gimano.app.AppContainer
import my.gimano.app.core.trip.PlannedSchedule
import my.gimano.app.data.planned.PlacePoint
import my.gimano.app.data.planned.PlannedDrive
import java.time.Instant
import java.time.LocalDate

data class PlanEditor(val drive: PlannedDrive, val isNew: Boolean, val leaveBy: Instant? = null, val arrive: Instant? = null, val computing: Boolean = false)

class PlannedViewModel(private val c: AppContainer) : ViewModel() {
    val drives = c.planned.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _editor = MutableStateFlow<PlanEditor?>(null)
    val editor: StateFlow<PlanEditor?> = _editor.asStateFlow()
    val canExact: Boolean get() = c.planned.canExact

    fun newPlan(dest: PlacePoint, origin: PlacePoint?, waypoints: List<PlacePoint>, vehicle: String) {
        val d = PlannedDrive(
            c.planned.newId(), dest.name, origin, dest, waypoints, vehicle,
            PlannedSchedule.repeating("08:00", PlannedSchedule.WEEKDAYS), Instant.now().toString(),
        )
        _editor.value = PlanEditor(d, isNew = true)
        recompute()
    }

    /** (arrival, leave-by) for the next occurrence. */
    suspend fun leaveByFor(d: PlannedDrive): Pair<Instant, Instant>? = c.planned.leaveBy(d)

    fun edit(d: PlannedDrive) { _editor.value = PlanEditor(d, isNew = false); recompute() }
    fun close() { _editor.value = null }

    fun update(f: (PlannedDrive) -> PlannedDrive) {
        _editor.update { it?.copy(drive = f(it.drive)) }
        recompute()
    }

    private val zone get() = java.time.ZoneId.systemDefault()

    fun setTime(hhmm: String) = update { d ->
        d.copy(schedule = if (d.schedule.repeating) d.schedule.copy(arriveBy = hhmm)
        else PlannedSchedule.oneOff(oneOffDate(d.schedule), hhmm, zone))
    }

    fun setOneOff(date: LocalDate) = update { d -> d.copy(schedule = PlannedSchedule.oneOff(date, d.schedule.timeOfDay(zone), zone)) }

    private fun oneOffDate(s: PlannedSchedule): LocalDate =
        s.arriveAt?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate() }.getOrNull() } ?: LocalDate.now().plusDays(1)

    fun toggleDay(code: String) = update { d ->
        val cur = d.schedule.days.orEmpty()
        val days = if (code in cur) cur - code else (cur + code).sortedBy { PlannedSchedule.WEEK.indexOf(it) }
        val time = d.schedule.timeOfDay(zone)
        d.copy(schedule = if (days.isEmpty()) PlannedSchedule.oneOff(LocalDate.now().plusDays(1), time, zone) else PlannedSchedule.repeating(time, days))
    }

    private var job: Job? = null
    private fun recompute() {
        val e = _editor.value ?: return
        job?.cancel()
        _editor.update { it?.copy(computing = true) }
        job = viewModelScope.launch {
            val r = c.planned.leaveBy(e.drive)
            _editor.update { it?.copy(arrive = r?.first, leaveBy = r?.second, computing = false) }
        }
    }

    fun save() {
        val e = _editor.value ?: return
        viewModelScope.launch {
            c.planned.save(e.drive)
            _editor.value = null
        }
    }

    fun delete(id: String) = viewModelScope.launch { c.planned.delete(id); _editor.value = null }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlannedViewModel(c) as T
    }
}

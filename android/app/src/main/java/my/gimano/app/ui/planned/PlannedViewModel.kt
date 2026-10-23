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
            PlannedSchedule("08:00", days = PlannedSchedule.WEEKDAYS), Instant.now().toString(),
        )
        _editor.value = PlanEditor(d, isNew = true)
        recompute()
    }

    fun edit(d: PlannedDrive) { _editor.value = PlanEditor(d, isNew = false); recompute() }
    fun close() { _editor.value = null }

    fun update(f: (PlannedDrive) -> PlannedDrive) {
        _editor.update { it?.copy(drive = f(it.drive)) }
        recompute()
    }

    fun setOneOff(date: LocalDate) = update { it.copy(schedule = it.schedule.copy(days = emptyList(), date = date.toString())) }
    fun toggleDay(code: String) = update { d ->
        val days = if (code in d.schedule.days) d.schedule.days - code else (d.schedule.days + code).sortedBy { PlannedSchedule.WEEK.indexOf(it) }
        d.copy(schedule = d.schedule.copy(days = days, date = if (days.isEmpty()) LocalDate.now().plusDays(1).toString() else null))
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

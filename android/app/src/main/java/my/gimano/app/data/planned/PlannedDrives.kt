package my.gimano.app.data.planned

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import my.gimano.app.GimanoApp
import my.gimano.app.MainActivity
import my.gimano.app.R
import my.gimano.app.core.format.Formatters
import my.gimano.app.core.format.Lang
import my.gimano.app.core.trip.LeaveBy
import my.gimano.app.core.trip.PlannedSchedule
import my.gimano.app.data.local.PlannedDriveDao
import my.gimano.app.data.local.PlannedDriveEntity
import my.gimano.app.data.remote.GimanoJson
import my.gimano.app.data.repo.GimanoRepository
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Serializable
data class PlacePoint(val lat: Double, val lng: Double, val name: String = "", val address: String = "", val placeId: String? = null)

data class PlannedDrive(
    val id: String,
    val name: String,
    val origin: PlacePoint?,          // null = "where I am when it's time"
    val destination: PlacePoint,
    val waypoints: List<PlacePoint>,
    val vehicle: String,
    val schedule: PlannedSchedule,
    val createdAt: String,
)

private val placeList = kotlinx.serialization.builtins.ListSerializer(PlacePoint.serializer())

fun PlannedDriveEntity.toModel(): PlannedDrive? = runCatching {
    PlannedDrive(
        id, name ?: "", originJson?.let { GimanoJson.decodeFromString(PlacePoint.serializer(), it) },
        GimanoJson.decodeFromString(PlacePoint.serializer(), destinationJson),
        waypointsJson?.let { GimanoJson.decodeFromString(placeList, it) }.orEmpty(),
        vehicle,
        departAt?.let { GimanoJson.decodeFromString(PlannedSchedule.serializer(), it) } ?: PlannedSchedule("08:00"),
        createdAt,
    )
}.getOrNull()

fun PlannedDrive.toEntity() = PlannedDriveEntity(
    id, name, origin?.let { GimanoJson.encodeToString(PlacePoint.serializer(), it) },
    GimanoJson.encodeToString(PlacePoint.serializer(), destination),
    waypoints.takeIf { it.isNotEmpty() }?.let { GimanoJson.encodeToString(placeList, it) },
    vehicle, GimanoJson.encodeToString(PlannedSchedule.serializer(), schedule), createdAt,
)

/** Planned drives: local table + "leave by" reminders (personal data: never sent to the server). */
class PlannedDrives(
    private val context: Context,
    private val dao: PlannedDriveDao,
    private val repo: GimanoRepository,
    private val here: () -> Pair<Double, Double>?,
    private val lang: () -> Lang,
    private val use24h: () -> Boolean,
) {
    val all: Flow<List<PlannedDrive>> = dao.observeAll().map { l -> l.mapNotNull { it.toModel() } }

    suspend fun save(d: PlannedDrive) { dao.upsert(d.toEntity()); schedule(d) }

    suspend fun delete(id: String) {
        dao.delete(id)
        alarms().cancel(pending(id))
    }

    fun newId() = UUID.randomUUID().toString()

    /** Leave-by for the next occurrence, from GET /routes/departure-times (origin/dest not stored by the server). */
    suspend fun leaveBy(d: PlannedDrive, now: Instant = Instant.now()): Pair<Instant, Instant>? {
        val zone = ZoneId.systemDefault()
        val arrive = d.schedule.nextArrival(now, zone) ?: return null
        val o = d.origin?.let { it.lat to it.lng } ?: here() ?: return arrive.toInstant() to arrive.toInstant().minusSeconds(1800)
        val slots = runCatching { repo.departureTimes(o.first, o.second, d.destination.lat, d.destination.lng, d.vehicle) }.getOrDefault(emptyList())
            .mapNotNull { s -> Formatters.parseInstant(s.departAt)?.let { LeaveBy.Slot(it, s.durationS) } }
            .filter { it.departAt.atZone(zone).toLocalDate() == arrive.toLocalDate() }
        return arrive.toInstant() to LeaveBy.compute(arrive.toInstant(), slots, 1800.0)
    }

    val canExact: Boolean get() = Build.VERSION.SDK_INT < 31 || alarms().canScheduleExactAlarms()

    /** Exact alarm when allowed; otherwise a 10-minute window (Android 14 denies exact alarms by default). */
    suspend fun schedule(d: PlannedDrive) {
        val (arrive, leave) = leaveBy(d) ?: return
        val at = LeaveBy.reminderAt(leave).toEpochMilli().coerceAtLeast(System.currentTimeMillis() + 5_000)
        val pi = pending(d.id, arrive.toEpochMilli(), leave.toEpochMilli())
        val am = alarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, at - 5 * 60_000, 10 * 60_000, pi)
    }

    suspend fun rescheduleAll() = dao.all().mapNotNull { it.toModel() }.forEach { schedule(it) }

    private fun alarms() = context.getSystemService(AlarmManager::class.java)

    private fun pending(id: String, arrive: Long = 0, leave: Long = 0) = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, PlannedDriveReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, id).putExtra("arrive", arrive).putExtra("leave", leave),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun notify(d: PlannedDrive, arrive: Instant, leave: Instant) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.planned_drives), NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val z = ZoneId.systemDefault()
        val l = lang()
        val leaveS = Formatters.clock(leave.atZone(z), use24h(), l)
        val arriveS = Formatters.clock(arrive.atZone(z), use24h(), l)
        val text = context.getString(R.string.planned_notify, leaveS, arriveS, d.name.ifBlank { d.destination.name })
        val open = PendingIntent.getActivity(
            context, d.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_ID, d.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(d.id.hashCode(), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_gimano)
            .setContentTitle(context.getString(R.string.planned_notify_title)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build())
    }

    suspend fun byId(id: String): PlannedDrive? = dao.all().firstOrNull { it.id == id }?.toModel()

    companion object {
        const val ACTION = "my.gimano.app.PLANNED_DRIVE"
        const val EXTRA_ID = "plannedDriveId"
        const val CHANNEL = "planned"
    }
}

/** Fires the "leave by" reminder, then schedules the next occurrence of a repeating drive. */
class PlannedDriveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as GimanoApp
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val c = app.container
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    c.planned.rescheduleAll(); return@launch
                }
                val id = intent.getStringExtra(PlannedDrives.EXTRA_ID) ?: return@launch
                val d = c.planned.byId(id) ?: return@launch
                c.planned.notify(d, Instant.ofEpochMilli(intent.getLongExtra("arrive", 0)), Instant.ofEpochMilli(intent.getLongExtra("leave", 0)))
                if (d.schedule.repeating) c.planned.schedule(d)
            } finally {
                pending.finish()
            }
        }
    }
}

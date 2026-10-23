import Foundation

/// Simulated GPS that drives along a route — used in demo mode, in the Simulator, or when
/// "Simulate drive" is enabled in Debug settings.
@MainActor
final class DriveSimulator {
    private var task: Task<Void, Never>?
    private(set) var isRunning = false

    /// Starts emitting fixes along `points` at roughly `speedKmh`, accelerated by `speedup`.
    func start(points: [LatLng], speedKmh: Double = 50, speedup: Double = 3, onFix: @escaping @MainActor (LocationFix) -> Void) {
        stop()
        guard points.count >= 2 else { return }
        isRunning = true
        let cumulative = Geo.cumulativeDistances(points)
        let total = cumulative.last ?? 0
        let tick = 0.5
        task = Task { @MainActor [weak self] in
            var along = 0.0
            var t = 0.0
            while !Task.isCancelled, along <= total {
                let (p, seg) = Geo.point(along: points, cumulative: cumulative, distance: along)
                let next = points[min(seg + 1, points.count - 1)]
                let course = Geo.bearing(points[seg], next)
                // Gentle speed variation so the speedometer looks alive.
                let v = speedKmh + 8 * sin(t / 6)
                onFix(LocationFix(coordinate: p, speedKmh: v, course: course, accuracyM: 5, timestamp: .now))
                try? await Task.sleep(for: .seconds(tick))
                along += v / 3.6 * tick * speedup
                t += tick
            }
            if !Task.isCancelled, let last = points.last {
                onFix(LocationFix(coordinate: last, speedKmh: 0, course: nil, accuracyM: 5, timestamp: .now))
            }
            self?.isRunning = false
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        isRunning = false
    }
}

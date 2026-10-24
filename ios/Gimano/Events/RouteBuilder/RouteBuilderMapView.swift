import MapLibre
import SwiftUI
import UIKit

/// One "Lukis" stroke. One finger draws; when a second finger lands the stroke is cancelled (nothing is added, the
/// map pans and zooms with the two fingers instead) and stays cancelled until every finger lifts, so the jump to the
/// two fingers' centre never becomes a zig-zag in the line.
struct DrawStroke<Coordinate> {
    /// A point is kept at least this far (pt) from the previous one (desktop map.ts).
    static var minSpacing: CGFloat { 4 }

    private(set) var screen: [CGPoint] = []
    private(set) var line: [Coordinate] = []
    private(set) var isCancelled = false

    var isActive: Bool { !line.isEmpty && !isCancelled }

    /// A new stroke: only with exactly one finger.
    mutating func begin(at p: CGPoint, _ c: Coordinate, touches: Int) {
        screen = []
        line = []
        isCancelled = touches != 1
        if !isCancelled { screen = [p]; line = [c] }
    }

    /// Returns true when the live line changed.
    mutating func move(to p: CGPoint, _ c: @autoclosure () -> Coordinate, touches: Int) -> Bool {
        guard !isCancelled, !line.isEmpty else { return false }
        if touches != 1 {
            cancel()
            return true
        }
        if let last = screen.last, hypot(p.x - last.x, p.y - last.y) < Self.minSpacing { return false }
        screen.append(p)
        line.append(c())
        return true
    }

    mutating func cancel() {
        isCancelled = true
        screen = []
        line = []
    }

    /// The finished line (at least 2 points), or nil when cancelled or too short. Resets the stroke.
    mutating func finish() -> [Coordinate]? {
        defer { screen = []; line = []; isCancelled = false }
        return !isCancelled && line.count >= 2 ? line : nil
    }
}

/// The builder's full-screen map. Taps add points, a point can be dragged, a tap on the line inserts a point, and in
/// "Lukis" one finger draws (two fingers still pan and zoom). Road legs are solid cobalt, sketches dashed, legs still
/// to calculate are a thin dotted grey connector (never drawn as a road), unmatched drawing stretches dashed orange.
struct RouteBuilderMapView: UIViewRepresentable {
    let model: RouteBuilderModel
    let styleURL: URL
    let start: LatLng
    /// Map centre, for "Tambah titik di tengah peta".
    var onCenter: (LatLng) -> Void = { _ in }

    func makeCoordinator() -> Coordinator { Coordinator(model: model, onCenter: onCenter) }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: styleURL)
        map.delegate = context.coordinator
        map.logoView.isHidden = true
        map.attributionButton.isHidden = true   // our own "© OpenStreetMap contributors" label is shown
        map.compassView.compassVisibility = .hidden
        map.showsUserLocation = false
        map.isPitchEnabled = false
        map.allowsRotating = false
        map.setCenter(start.coordinate, zoomLevel: 13, animated: false)
        map.accessibilityIdentifier = "routeBuilderMap"
        context.coordinator.attach(map)
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {}

    static func dismantleUIView(_ map: MLNMapView, coordinator: Coordinator) { coordinator.map = nil }

    @MainActor
    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        let model: RouteBuilderModel
        let onCenter: (LatLng) -> Void
        weak var map: MLNMapView?
        private var style: MLNStyle?
        private var mapPan: UIPanGestureRecognizer?
        private var drawPan: UIPanGestureRecognizer!
        private var pointPan: UIPanGestureRecognizer!
        private var dragging: (index: Int, at: LatLng)?
        private var stroke = DrawStroke<LatLng>()
        private var lastRendered: RouteBuilderModel.Snapshot?
        private var lastSelected: Int?
        private var fitted = false
        private var imageNames = Set<String>()

        init(model: RouteBuilderModel, onCenter: @escaping (LatLng) -> Void) {
            self.model = model
            self.onCenter = onCenter
        }

        func attach(_ map: MLNMapView) {
            self.map = map
            let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
            for g in map.gestureRecognizers ?? [] where (g as? UITapGestureRecognizer)?.numberOfTapsRequired == 2 { tap.require(toFail: g) }
            map.addGestureRecognizer(tap)

            pointPan = UIPanGestureRecognizer(target: self, action: #selector(handlePointPan(_:)))
            pointPan.maximumNumberOfTouches = 1
            pointPan.delegate = self
            map.addGestureRecognizer(pointPan)

            drawPan = UIPanGestureRecognizer(target: self, action: #selector(handleDraw(_:)))
            // Two, so a second finger landing mid-stroke is seen (and cancels the stroke) instead of being ignored.
            drawPan.maximumNumberOfTouches = 2
            drawPan.delegate = self
            map.addGestureRecognizer(drawPan)

            mapPan = (map.gestureRecognizers ?? []).first { $0 is UIPanGestureRecognizer && $0 !== pointPan && $0 !== drawPan } as? UIPanGestureRecognizer
            mapPan?.require(toFail: pointPan)
            observe()
        }

        private func observe() {
            withObservationTracking { apply() } onChange: { [weak self] in Task { @MainActor in self?.observe() } }
        }

        private func apply() {
            let snap = model.snapshot
            let selected = model.selectedPoint
            let tool = model.tool
            // In "Lukis" one finger draws; the map still pans with two fingers.
            mapPan?.minimumNumberOfTouches = tool == .draw ? 2 : 1
            drawPan?.isEnabled = tool == .draw
            guard let map, style != nil else { return }
            if snap != lastRendered || selected != lastSelected {
                render(snap, selected: selected)
                lastRendered = snap
                lastSelected = selected
            }
            if !fitted {
                fitted = true
                let pts = snap.route.points.map(\.location) + snap.route.joinedGeometry + snap.stops.map(\.location)
                if pts.count > 1, let b = BBox.around(pts) {
                    let bounds = MLNCoordinateBounds(sw: CLLocationCoordinate2D(latitude: b.minLat, longitude: b.minLng),
                                                     ne: CLLocationCoordinate2D(latitude: b.maxLat, longitude: b.maxLng))
                    map.setCamera(map.cameraThatFitsCoordinateBounds(bounds, edgePadding: UIEdgeInsets(top: 140, left: 48, bottom: 330, right: 48)), animated: false)
                } else if let p = pts.first {
                    map.setCenter(p.coordinate, zoomLevel: 14, animated: false)
                }
            }
        }

        // MARK: style

        fileprivate func styleLoaded(_ style: MLNStyle) {
            self.style = style
            imageNames = []
            let traits = map?.traitCollection ?? .current
            if let bg = style.layers.first(where: { $0 is MLNBackgroundStyleLayer }) as? MLNBackgroundStyleLayer {
                bg.backgroundColor = NSExpression(forConstantValue: Tokens.Palette.mapBackground.resolvedColor(with: traits))
            }
            for id in ["rb-legs", "rb-unmatched", "rb-live", "rb-points", "rb-stops"] { style.addSource(MLNShapeSource(identifier: id, shape: nil)) }
            let legs = style.source(withIdentifier: "rb-legs")!
            let cobalt = UIColor(hex: 0x0047AB), ink = UIColor(hex: 0x0B1B3A), orange = UIColor(hex: 0xF08A24)

            func line(_ id: String, _ source: MLNSource, _ color: UIColor, _ width: Double, kind: String?, dash: [Double]? = nil) {
                let l = MLNLineStyleLayer(identifier: id, source: source)
                l.lineColor = NSExpression(forConstantValue: color)
                l.lineWidth = NSExpression(forConstantValue: width)
                l.lineJoin = NSExpression(forConstantValue: "round")
                l.lineCap = NSExpression(forConstantValue: dash == nil ? "round" : "butt")
                if let dash { l.lineDashPattern = NSExpression(forConstantValue: dash) }
                if let kind { l.predicate = NSPredicate(format: "kind == %@", kind) }
                style.addLayer(l)
            }
            line("rb-road-casing", legs, .white, 10, kind: "road")
            line("rb-road", legs, cobalt, 6, kind: "road")
            line("rb-sketch-casing", legs, .white, 8, kind: "sketch")
            line("rb-sketch", legs, cobalt, 4.5, kind: "sketch", dash: [2, 1.5])
            line("rb-pending", legs, UIColor(hex: 0x4E5D78), 2.5, kind: "pending", dash: [0.6, 2])
            line("rb-unresolved", legs, UIColor(hex: 0xE5484D), 2.5, kind: "unresolved", dash: [0.6, 2])
            line("rb-unmatched", style.source(withIdentifier: "rb-unmatched")!, orange, 4.5, kind: nil, dash: [2, 1.5])
            line("rb-live", style.source(withIdentifier: "rb-live")!, ink, 4, kind: nil)

            let stops = MLNSymbolStyleLayer(identifier: "rb-stops", source: style.source(withIdentifier: "rb-stops")!)
            stops.iconImageName = NSExpression(forConstantValue: "rb-stop")
            stops.iconAllowsOverlap = NSExpression(forConstantValue: true)
            stops.iconAnchor = NSExpression(forConstantValue: "bottom")
            style.setImage(Self.stopImage(), forName: "rb-stop")
            style.addLayer(stops)

            let pts = MLNSymbolStyleLayer(identifier: "rb-points", source: style.source(withIdentifier: "rb-points")!)
            pts.iconImageName = NSExpression(forKeyPath: "icon")
            pts.iconAllowsOverlap = NSExpression(forConstantValue: true)
            pts.iconIgnoresPlacement = NSExpression(forConstantValue: true)
            style.addLayer(pts)
            lastRendered = nil
            apply()
        }

        private func source(_ id: String) -> MLNShapeSource? { style?.source(withIdentifier: id) as? MLNShapeSource }

        private func polyline(_ pts: [LatLng], _ attrs: [String: Any] = [:]) -> MLNPolylineFeature {
            var c = pts.map(\.coordinate)
            let f = MLNPolylineFeature(coordinates: &c, count: UInt(c.count))
            f.attributes = attrs
            return f
        }

        private func render(_ snap: RouteBuilderModel.Snapshot, selected: Int?) {
            let r = snap.route
            var legFeatures: [MLNPolylineFeature] = []
            var unmatched: [MLNPolylineFeature] = []
            for (i, l) in r.legs.enumerated() {
                let a = r.points[i].location, b = r.points[i + 1].location
                switch l.state {
                case .routed, .matched:
                    legFeatures.append(polyline(l.geometry, ["kind": "road", "leg": i]))
                    if let d = l.drawn {
                        for u in l.unmatched {
                            let lo = max(0, u.from - 1), hi = min(d.count - 1, u.to + 1)
                            if hi > lo { unmatched.append(polyline(Array(d[lo...hi]))) }
                        }
                    }
                case .sketch:
                    legFeatures.append(polyline(l.geometry, ["kind": "sketch", "leg": i]))
                case .pending:
                    legFeatures.append(polyline(l.drawn ?? [a, b], ["kind": "pending", "leg": i]))
                case .unresolved:
                    legFeatures.append(polyline(l.drawn ?? [a, b], ["kind": "unresolved", "leg": i]))
                }
            }
            source("rb-legs")?.shape = MLNShapeCollectionFeature(shapes: legFeatures)
            source("rb-unmatched")?.shape = MLNShapeCollectionFeature(shapes: unmatched)

            var pointFeatures: [MLNPointFeature] = []
            for (i, p) in r.points.enumerated() {
                let loc = dragging?.index == i ? dragging!.at : p.location
                let role = i == 0 ? "start" : (i == r.points.count - 1 ? "end" : "mid")
                let name = "rb-pt-\(role)-\(i + 1)-\(p.stopId != nil ? "s" : "p")-\(selected == i ? "x" : "o")"
                if !imageNames.contains(name), let style {
                    style.setImage(Self.pointImage(number: i + 1, role: role, linked: p.stopId != nil, selected: selected == i), forName: name)
                    imageNames.insert(name)
                }
                let f = MLNPointFeature()
                f.coordinate = loc.coordinate
                f.attributes = ["icon": name, "index": i]
                pointFeatures.append(f)
            }
            source("rb-points")?.shape = MLNShapeCollectionFeature(shapes: pointFeatures)

            let linked = Set(r.points.compactMap(\.stopId))
            let stopFeatures: [MLNPointFeature] = snap.stops.filter { !linked.contains($0.id) }.map { s in
                let f = MLNPointFeature()
                f.coordinate = s.location.coordinate
                f.attributes = ["stopId": s.id]
                return f
            }
            source("rb-stops")?.shape = MLNShapeCollectionFeature(shapes: stopFeatures)
        }

        // MARK: hit testing

        private func pointIndex(near pt: CGPoint, radius: CGFloat = 26) -> Int? {
            guard let map else { return nil }
            var best: (Int, CGFloat)?
            for (i, p) in model.route.points.enumerated() {
                let s = map.convert(p.location.coordinate, toPointTo: map)
                let d = hypot(s.x - pt.x, s.y - pt.y)
                if d <= radius, d < (best?.1 ?? .infinity) { best = (i, d) }
            }
            return best?.0
        }

        private func stopId(near pt: CGPoint) -> String? {
            guard let map else { return nil }
            let linked = Set(model.route.points.compactMap(\.stopId))
            return model.stops.filter { !linked.contains($0.id) }.first { s in
                let p = map.convert(s.location.coordinate, toPointTo: map)
                return hypot(p.x - pt.x, p.y - pt.y + 14) <= 26
            }?.id
        }

        /// The leg whose line passes within 16 pt of the tap.
        private func legIndex(near pt: CGPoint) -> Int? {
            guard let map else { return nil }
            let rect = CGRect(x: pt.x - 16, y: pt.y - 16, width: 32, height: 32)
            let f = map.visibleFeatures(in: rect, styleLayerIdentifiers: ["rb-road", "rb-sketch"]).first
            return (f?.attribute(forKey: "leg") as? NSNumber)?.intValue
        }

        // MARK: gestures

        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            guard let map else { return false }
            if g === pointPan { return model.tool == .points && pointIndex(near: g.location(in: map)) != nil }
            // Only one finger starts a stroke; two fingers go to the map (pan, pinch zoom).
            if g === drawPan { return model.tool == .draw && g.numberOfTouches == 1 }
            return true
        }

        /// While a stroke is under way, the map's two-finger pan and pinch may still start: the second finger then
        /// cancels the stroke and moves the map.
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            guard g === drawPan || other === drawPan else { return false }
            let mapGesture = g === drawPan ? other : g
            return mapGesture === mapPan || mapGesture is UIPinchGestureRecognizer || mapGesture is UIRotationGestureRecognizer
        }

        @objc func handleTap(_ g: UITapGestureRecognizer) {
            guard let map, model.tool == .points else { return }
            let pt = g.location(in: map)
            if let i = pointIndex(near: pt) {
                model.selectedPoint = i
                return
            }
            if model.selectedPoint != nil { model.selectedPoint = nil; return }
            let c = LatLng(map.convert(pt, toCoordinateFrom: map))
            if let sid = stopId(near: pt) {
                model.tap(c, stopId: sid)
            } else if let leg = legIndex(near: pt) {
                model.insert(c, inLeg: leg)
            } else {
                model.tap(c)
            }
        }

        @objc func handlePointPan(_ g: UIPanGestureRecognizer) {
            guard let map else { return }
            let c = LatLng(map.convert(g.location(in: map), toCoordinateFrom: map))
            switch g.state {
            case .began:
                if let i = pointIndex(near: g.location(in: map), radius: 40) { dragging = (i, c); UISelectionFeedbackGenerator().selectionChanged() }
            case .changed:
                if let d = dragging { dragging = (d.index, c); render(model.snapshot, selected: model.selectedPoint) }
            case .ended:
                if let d = dragging { dragging = nil; model.move(d.index, to: c) }
            default:
                dragging = nil
                render(model.snapshot, selected: model.selectedPoint)
            }
        }

        @objc func handleDraw(_ g: UIPanGestureRecognizer) {
            guard let map else { return }
            let pt = g.location(in: map)
            switch g.state {
            case .began:
                stroke.begin(at: pt, LatLng(map.convert(pt, toCoordinateFrom: map)), touches: g.numberOfTouches)
            case .changed:
                guard stroke.move(to: pt, LatLng(map.convert(pt, toCoordinateFrom: map)), touches: g.numberOfTouches) else { return }
                source("rb-live")?.shape = stroke.isActive ? polyline(stroke.line) : nil
            case .ended:
                let line = stroke.finish()
                source("rb-live")?.shape = nil
                if let line { model.draw(line) }
            default:
                stroke.cancel()
                _ = stroke.finish()
                source("rb-live")?.shape = nil
            }
        }

        fileprivate func regionDidChange() {
            guard let map else { return }
            onCenter(LatLng(map.centerCoordinate))
        }

        // MARK: images (Manotu's own glyphs)

        static func pointImage(number: Int, role: String, linked: Bool, selected: Bool) -> UIImage {
            let d: CGFloat = selected ? 34 : 28
            let size = CGSize(width: d + 8, height: d + 8)
            let cobalt = UIColor(hex: 0x0047AB), ink = UIColor(hex: 0x0B1B3A), pale = UIColor(hex: 0xE3ECFA)
            return UIGraphicsImageRenderer(size: size).image { ctx in
                let c = ctx.cgContext
                let r = CGRect(x: 4, y: 4, width: d, height: d)
                c.setShadow(offset: CGSize(width: 0, height: 1), blur: 3, color: UIColor.black.withAlphaComponent(0.3).cgColor)
                UIColor.white.setFill()
                (linked ? UIBezierPath(roundedRect: r, cornerRadius: 8) : UIBezierPath(ovalIn: r)).fill()
                c.setShadow(offset: .zero, blur: 0, color: nil)
                let inner = r.insetBy(dx: 3, dy: 3)
                let fill: UIColor = role == "start" ? cobalt : role == "end" ? ink : pale
                fill.setFill()
                (linked ? UIBezierPath(roundedRect: inner, cornerRadius: 6) : UIBezierPath(ovalIn: inner)).fill()
                if selected {
                    cobalt.setStroke()
                    let ring = linked ? UIBezierPath(roundedRect: r.insetBy(dx: -2, dy: -2), cornerRadius: 10) : UIBezierPath(ovalIn: r.insetBy(dx: -2, dy: -2))
                    ring.lineWidth = 3
                    ring.stroke()
                }
                let text = "\(number)" as NSString
                let attrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: number > 99 ? 10 : 13, weight: .heavy),
                    .foregroundColor: role == "mid" ? cobalt : UIColor.white,
                ]
                let ts = text.size(withAttributes: attrs)
                text.draw(at: CGPoint(x: r.midX - ts.width / 2, y: r.midY - ts.height / 2), withAttributes: attrs)
            }
        }

        static func stopImage() -> UIImage {
            UIGraphicsImageRenderer(size: CGSize(width: 30, height: 38)).image { ctx in
                let c = ctx.cgContext
                c.setShadow(offset: CGSize(width: 0, height: 1), blur: 2, color: UIColor.black.withAlphaComponent(0.25).cgColor)
                UIColor.white.setFill()
                UIBezierPath(roundedRect: CGRect(x: 3, y: 2, width: 24, height: 24), cornerRadius: 7).fill()
                let tail = UIBezierPath()
                tail.move(to: CGPoint(x: 11, y: 25)); tail.addLine(to: CGPoint(x: 15, y: 34)); tail.addLine(to: CGPoint(x: 19, y: 25)); tail.close()
                tail.fill()
                c.setShadow(offset: .zero, blur: 0, color: nil)
                UIColor(hex: 0x8FB0E8).setFill()
                UIBezierPath(roundedRect: CGRect(x: 6, y: 5, width: 18, height: 18), cornerRadius: 5).fill()
                UIImage(systemName: "flag.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 10, weight: .bold))?
                    .withTintColor(UIColor(hex: 0x0B1B3A), renderingMode: .alwaysOriginal).draw(in: CGRect(x: 9, y: 8, width: 12, height: 12))
            }
        }
    }
}

extension RouteBuilderMapView.Coordinator: @preconcurrency MLNMapViewDelegate {
    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) { styleLoaded(style) }
    func mapView(_ mapView: MLNMapView, regionDidChangeWith reason: MLNCameraChangeReason, animated: Bool) { regionDidChange() }
}

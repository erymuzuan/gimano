import CoreGraphics
import SwiftUI
import UIKit

/// Parses the SVG path subset used by Gimano's icon set (M/L/H/V/C/S/Q/A/Z, absolute and relative)
/// into a stroke path and a fill path that contains only the closed sub-paths.
struct SVGPath {
    let stroke: CGPath
    let fill: CGPath

    static func parse(_ d: String) -> SVGPath {
        var p = Parser(Array(d.utf8))
        return p.run()
    }

    private struct Parser {
        let s: [UInt8]
        var i = 0
        let stroke = CGMutablePath()
        let fill = CGMutablePath()
        var sub = CGMutablePath()
        var subClosed = false
        var subSegments = 0
        var cur = CGPoint.zero
        var start = CGPoint.zero
        var lastCubicCtrl: CGPoint?
        var lastQuadCtrl: CGPoint?

        init(_ s: [UInt8]) { self.s = s }

        mutating func skipSeparators() {
            while i < s.count, s[i] == 32 || s[i] == 44 || s[i] == 9 || s[i] == 10 || s[i] == 13 { i += 1 }
        }

        mutating func hasNumber() -> Bool {
            skipSeparators()
            guard i < s.count else { return false }
            let c = s[i]
            return (c >= 48 && c <= 57) || c == 45 || c == 43 || c == 46
        }

        mutating func number() -> CGFloat {
            skipSeparators()
            let begin = i
            if i < s.count, s[i] == 45 || s[i] == 43 { i += 1 }
            var seenDot = false, seenExp = false
            while i < s.count {
                let c = s[i]
                if c >= 48 && c <= 57 { i += 1 }
                else if c == 46 && !seenDot && !seenExp { seenDot = true; i += 1 }
                else if (c == 101 || c == 69) && !seenExp { seenExp = true; i += 1; if i < s.count, s[i] == 45 || s[i] == 43 { i += 1 } }
                else { break }
            }
            return CGFloat(Double(String(decoding: s[begin..<i], as: UTF8.self)) ?? 0)
        }

        /// Arc flags may be packed without separators ("0 1 1 0-18" or "011").
        mutating func flag() -> Bool {
            skipSeparators()
            guard i < s.count else { return false }
            let v = s[i] == 49
            i += 1
            return v
        }

        mutating func flushSub() {
            if subSegments > 0 {
                stroke.addPath(sub)
                let endsAtStart = hypot(cur.x - start.x, cur.y - start.y) < 0.01
                if subClosed || endsAtStart { fill.addPath(sub) }
            }
            sub = CGMutablePath()
            subClosed = false
            subSegments = 0
        }

        mutating func moveTo(_ p: CGPoint) {
            flushSub()
            sub.move(to: p)
            cur = p
            start = p
        }

        mutating func lineTo(_ p: CGPoint) {
            sub.addLine(to: p)
            cur = p
            subSegments += 1
        }

        mutating func run() -> SVGPath {
            var cmd: UInt8 = 0
            while true {
                skipSeparators()
                guard i < s.count else { break }
                let c = s[i]
                if (c >= 65 && c <= 90) || (c >= 97 && c <= 122) {
                    cmd = c
                    i += 1
                } else if cmd == 0 {
                    i += 1
                    continue
                }
                let rel = cmd >= 97
                let upper = rel ? cmd - 32 : cmd
                let o = rel ? cur : .zero
                switch upper {
                case 77: // M
                    let p = CGPoint(x: o.x + number(), y: o.y + number())
                    moveTo(p)
                    lastCubicCtrl = nil; lastQuadCtrl = nil
                    // Subsequent pairs are implicit line-tos.
                    while hasNumber() {
                        let o2 = rel ? cur : .zero
                        lineTo(CGPoint(x: o2.x + number(), y: o2.y + number()))
                    }
                case 76: // L
                    repeat {
                        let o2 = rel ? cur : .zero
                        lineTo(CGPoint(x: o2.x + number(), y: o2.y + number()))
                    } while hasNumber()
                    lastCubicCtrl = nil; lastQuadCtrl = nil
                case 72: // H
                    repeat { let x = number(); lineTo(CGPoint(x: rel ? cur.x + x : x, y: cur.y)) } while hasNumber()
                    lastCubicCtrl = nil; lastQuadCtrl = nil
                case 86: // V
                    repeat { let y = number(); lineTo(CGPoint(x: cur.x, y: rel ? cur.y + y : y)) } while hasNumber()
                    lastCubicCtrl = nil; lastQuadCtrl = nil
                case 67: // C
                    repeat {
                        let o2 = rel ? cur : .zero
                        let c1 = CGPoint(x: o2.x + number(), y: o2.y + number())
                        let c2 = CGPoint(x: o2.x + number(), y: o2.y + number())
                        let e = CGPoint(x: o2.x + number(), y: o2.y + number())
                        sub.addCurve(to: e, control1: c1, control2: c2)
                        subSegments += 1
                        lastCubicCtrl = c2
                        cur = e
                    } while hasNumber()
                    lastQuadCtrl = nil
                case 83: // S
                    repeat {
                        let o2 = rel ? cur : .zero
                        let c1 = lastCubicCtrl.map { CGPoint(x: 2 * cur.x - $0.x, y: 2 * cur.y - $0.y) } ?? cur
                        let c2 = CGPoint(x: o2.x + number(), y: o2.y + number())
                        let e = CGPoint(x: o2.x + number(), y: o2.y + number())
                        sub.addCurve(to: e, control1: c1, control2: c2)
                        subSegments += 1
                        lastCubicCtrl = c2
                        cur = e
                    } while hasNumber()
                    lastQuadCtrl = nil
                case 81: // Q
                    repeat {
                        let o2 = rel ? cur : .zero
                        let c = CGPoint(x: o2.x + number(), y: o2.y + number())
                        let e = CGPoint(x: o2.x + number(), y: o2.y + number())
                        sub.addQuadCurve(to: e, control: c)
                        subSegments += 1
                        lastQuadCtrl = c
                        cur = e
                    } while hasNumber()
                    lastCubicCtrl = nil
                case 65: // A
                    repeat {
                        let rx = abs(number()), ry = abs(number())
                        let rot = number()
                        let large = flag(), sweep = flag()
                        let o2 = rel ? cur : .zero
                        let e = CGPoint(x: o2.x + number(), y: o2.y + number())
                        addArc(rx: rx, ry: ry, rotation: rot, large: large, sweep: sweep, to: e)
                    } while hasNumber()
                    lastCubicCtrl = nil; lastQuadCtrl = nil
                case 90: // Z
                    sub.closeSubpath()
                    subClosed = true
                    cur = start
                    lastCubicCtrl = nil; lastQuadCtrl = nil
                default:
                    break
                }
            }
            flushSub()
            return SVGPath(stroke: stroke, fill: fill)
        }

        /// SVG endpoint arc → cubic Béziers (SVG 1.1 implementation notes F.6.5).
        mutating func addArc(rx inRx: CGFloat, ry inRy: CGFloat, rotation: CGFloat, large: Bool, sweep: Bool, to e: CGPoint) {
            let p0 = cur
            guard inRx > 0, inRy > 0, p0 != e else { lineTo(e); return }
            var rx = inRx, ry = inRy
            let phi = rotation * .pi / 180
            let cosP = cos(phi), sinP = sin(phi)
            let dx = (p0.x - e.x) / 2, dy = (p0.y - e.y) / 2
            let x1 = cosP * dx + sinP * dy
            let y1 = -sinP * dx + cosP * dy
            let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
            if lambda > 1 { rx *= sqrt(lambda); ry *= sqrt(lambda) }
            let num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
            let den = rx * rx * y1 * y1 + ry * ry * x1 * x1
            var coef = den == 0 ? 0 : sqrt(max(0, num / den))
            if large == sweep { coef = -coef }
            let cxp = coef * rx * y1 / ry
            let cyp = -coef * ry * x1 / rx
            let cx = cosP * cxp - sinP * cyp + (p0.x + e.x) / 2
            let cy = sinP * cxp + cosP * cyp + (p0.y + e.y) / 2

            func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
                let a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
                return a
            }
            let theta1 = angle(1, 0, (x1 - cxp) / rx, (y1 - cyp) / ry)
            var delta = angle((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry)
            if !sweep && delta > 0 { delta -= 2 * .pi }
            if sweep && delta < 0 { delta += 2 * .pi }

            let segments = max(1, Int(ceil(abs(delta) / (.pi / 2))))
            let step = delta / CGFloat(segments)
            let k = 4.0 / 3.0 * tan(step / 4)
            var t = theta1
            func point(_ a: CGFloat) -> CGPoint {
                CGPoint(x: cx + rx * cos(a) * cosP - ry * sin(a) * sinP, y: cy + rx * cos(a) * sinP + ry * sin(a) * cosP)
            }
            func deriv(_ a: CGFloat) -> CGPoint {
                CGPoint(x: -rx * sin(a) * cosP - ry * cos(a) * sinP, y: -rx * sin(a) * sinP + ry * cos(a) * cosP)
            }
            for n in 0..<segments {
                let t2 = t + step
                let a = point(t), b = n == segments - 1 ? e : point(t2)
                let da = deriv(t), db = deriv(t2)
                sub.addCurve(to: b,
                             control1: CGPoint(x: a.x + k * da.x, y: a.y + k * da.y),
                             control2: CGPoint(x: b.x - k * db.x, y: b.y - k * db.y))
                t = t2
            }
            subSegments += 1
            cur = e
        }
    }
}

extension GIcon {
    nonisolated(unsafe) private static let cache: [GIcon: SVGPath] = Dictionary(uniqueKeysWithValues: GIcon.allCases.map {
        ($0, SVGPath.parse(GimanoIconData.paths[$0.rawValue] ?? ""))
    })

    var path: SVGPath { Self.cache[self]! }

    /// Stroke width in grid units: 2.0, or 1.9 for icons drawn at 60 px and larger.
    static func strokeUnits(forSize size: CGFloat) -> CGFloat { size >= 60 ? 1.9 : 2.0 }
}

private struct IconShape: Shape {
    let icon: GIcon
    let fillPart: Bool
    func path(in rect: CGRect) -> Path {
        let path = fillPart ? icon.path.fill : icon.path.stroke
        let scale = min(rect.width, rect.height) / GimanoIconData.grid
        var t = CGAffineTransform(translationX: rect.midX - 12 * scale, y: rect.midY - 12 * scale).scaledBy(x: scale, y: scale)
        return Path(path.copy(using: &t) ?? path)
    }
}

/// Rounded duotone icon: tint fill at 60 % on closed shapes + 2.0 stroke, round caps/joins.
struct GimanoIcon: View {
    let icon: GIcon
    var size: CGFloat = 24
    var stroke: Color = .brandInk
    var fill: Color = .brandTint

    var body: some View {
        let lw = GIcon.strokeUnits(forSize: size) * size / GimanoIconData.grid
        ZStack {
            IconShape(icon: icon, fillPart: true).fill(fill.opacity(0.6))
            IconShape(icon: icon, fillPart: false).stroke(stroke, style: StrokeStyle(lineWidth: lw, lineCap: .round, lineJoin: .round))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

extension GIcon {
    /// Renders the icon into a UIImage (for MapLibre symbol layers).
    func draw(in ctx: CGContext, rect: CGRect, stroke: UIColor, fill: UIColor) {
        let scale = rect.width / GimanoIconData.grid
        ctx.saveGState()
        ctx.translateBy(x: rect.minX, y: rect.minY)
        ctx.scaleBy(x: scale, y: scale)
        ctx.addPath(path.fill)
        ctx.setFillColor(fill.withAlphaComponent(0.6).cgColor)
        ctx.fillPath()
        ctx.addPath(path.stroke)
        ctx.setStrokeColor(stroke.cgColor)
        ctx.setLineWidth(GIcon.strokeUnits(forSize: rect.width))
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        ctx.strokePath()
        ctx.restoreGState()
    }
}

import Foundation

enum CorridorZone: CaseIterable {
    case left
    case center
    case right

    var messageSuffix: String {
        switch self {
        case .left: return "avant-gauche"
        case .center: return "devant"
        case .right: return "avant-droite"
        }
    }

    var voiceSuffix: String {
        switch self {
        case .left: return "avant-gauche"
        case .center: return "devant"
        case .right: return "avant-droite"
        }
    }

    var debugIdentifier: String {
        switch self {
        case .left: return "left"
        case .center: return "center"
        case .right: return "right"
        }
    }
}
struct ForwardObstruction {
    let nearestDepth: Float
    let occupancyRatio: Float
    let dominantZone: CorridorZone
    let wideObstruction: Bool
}
enum WarningLevel {
    case none
    case low
    case medium
    case high

    var severityRank: Int {
        switch self {
        case .none: return 0
        case .low: return 1
        case .medium: return 2
        case .high: return 3
        }
    }

    var debugIdentifier: String {
        switch self {
        case .none: return "none"
        case .low: return "low"
        case .medium: return "medium"
        case .high: return "high"
        }
    }
}
enum DangerResolutionSource {
    case semanticCurrent
    case semanticVisualProxy
    case semanticVisualMemory
    case semanticMemory
    case genericFallback
    case wideFallback
    case limitedSensors

    var isSemantic: Bool {
        switch self {
        case .semanticCurrent, .semanticVisualProxy, .semanticVisualMemory, .semanticMemory:
            return true
        case .genericFallback, .wideFallback, .limitedSensors:
            return false
        }
    }
}
struct DangerResolutionCandidate {
    let source: DangerResolutionSource
    let level: WarningLevel
    let score: Float
    let distance: Float
    let zone: CorridorZone
}
struct DangerResolutionContext {
    let obstruction: ForwardObstruction
    let hasPotentialSemanticContext: Bool
    let hasRepeatedVisualSemanticContext: Bool
}
enum DangerResolutionPolicy {
    static func shouldPreferLimitedSensors(
        _ limitedSensors: DangerResolutionCandidate,
        over fallback: DangerResolutionCandidate?,
        semantic: DangerResolutionCandidate?,
        context: DangerResolutionContext,
        baseLevel: WarningLevel
    ) -> Bool {
        let obstruction = context.obstruction

        guard semantic == nil else { return false }
        guard let fallback else { return true }

        let stronglyGroundLikeWideFallback =
            fallback.source == .wideFallback &&
            obstruction.nearestDepth > 1.0 &&
            obstruction.nearestDepth < 1.78 &&
            limitedSensors.score >= fallback.score + 0.2

        if stronglyGroundLikeWideFallback {
            return true
        }

        guard baseLevel != .high else { return false }

        let groundLikeWideFallback =
            fallback.source == .wideFallback &&
            obstruction.nearestDepth > 1.0 &&
            obstruction.occupancyRatio < 0.36

        guard groundLikeWideFallback || !obstructionLooksWallLikeStrong(obstruction) else {
            return false
        }

        if fallback.source == .genericFallback && obstruction.occupancyRatio < 0.3 {
            return true
        }

        if fallback.source == .wideFallback &&
            (
                groundLikeWideFallback ||
                (
                    obstruction.occupancyRatio < 0.24 &&
                    obstruction.nearestDepth > 1.02
                )
            ) {
            return true
        }

        return false
    }

    static func shouldPreferSemantic(
        _ semantic: DangerResolutionCandidate,
        over fallback: DangerResolutionCandidate?,
        context: DangerResolutionContext
    ) -> Bool {
        guard let fallback else { return true }

        let obstruction = context.obstruction
        let wallLike = obstructionLooksWallLikeStrong(obstruction)
        let ambiguousFallback = isAmbiguousFallback(fallback, obstruction: obstruction)
        let zoneConflict = semantic.zone != fallback.zone
        let repeatedVisual = context.hasRepeatedVisualSemanticContext

        switch semantic.source {
        case .semanticCurrent, .semanticMemory:
            // A semantic label from another corridor cannot own a strong wall-like
            // surface. Same-zone detections may still enrich that surface.
            if wallLike && zoneConflict {
                return false
            }

            if semantic.distance < 1.55 {
                return true
            }

            if semantic.score + (ambiguousFallback ? 0.02 : 0.18) >= fallback.score {
                return true
            }

            return zoneConflict && ambiguousFallback

        case .semanticVisualProxy, .semanticVisualMemory:
            guard !wallLike else { return false }

            if repeatedVisual && zoneConflict && ambiguousFallback {
                return true
            }

            if semantic.score >= fallback.score + 0.22 {
                return true
            }

            return ambiguousFallback && semantic.score + 0.08 >= fallback.score

        case .genericFallback, .wideFallback, .limitedSensors:
            return false
        }
    }

    static func shouldSuppressAmbiguousFallback(
        _ fallback: DangerResolutionCandidate,
        context: DangerResolutionContext
    ) -> Bool {
        guard isAmbiguousFallback(fallback, obstruction: context.obstruction) else { return false }
        return context.hasPotentialSemanticContext
    }

    static func shouldPreferFallback(
        _ fallback: DangerResolutionCandidate,
        over semantic: DangerResolutionCandidate?,
        context: DangerResolutionContext
    ) -> Bool {
        guard let semantic else { return true }

        if obstructionLooksWallLikeStrong(context.obstruction) {
            if semanticCanOverrideWallLikeFallback(semantic, fallback: fallback, context: context) {
                return false
            }
            return true
        }

        return fallback.score >= semantic.score + 0.45
    }

    private static func semanticCanOverrideWallLikeFallback(
        _ semantic: DangerResolutionCandidate,
        fallback: DangerResolutionCandidate,
        context: DangerResolutionContext
    ) -> Bool {
        let obstruction = context.obstruction
        let closeToDepthSurface = semantic.distance <= max(1.32, obstruction.nearestDepth + 0.16)
        let closeRange = semantic.distance < 1.48 || obstruction.nearestDepth < 1.32
        let severityCoherent = semantic.level.severityRank >= max(1, fallback.level.severityRank - 1)
        let scoreCoherent = semantic.score + 0.2 >= fallback.score

        switch semantic.source {
        case .semanticCurrent, .semanticMemory:
            return semantic.zone == fallback.zone &&
                closeToDepthSurface &&
                closeRange &&
                severityCoherent &&
                scoreCoherent
        case .semanticVisualProxy, .semanticVisualMemory:
            return context.hasRepeatedVisualSemanticContext &&
                closeRange &&
                semantic.score >= fallback.score + 0.18
        case .genericFallback, .wideFallback, .limitedSensors:
            return false
        }
    }

    static func isAmbiguousFallback(
        _ fallback: DangerResolutionCandidate,
        obstruction: ForwardObstruction
    ) -> Bool {
        guard !obstructionLooksWallLikeStrong(obstruction) else { return false }

        if fallback.source == .wideFallback {
            return obstruction.occupancyRatio < 0.22 && obstruction.nearestDepth > 1.35
        }

        return !obstruction.wideObstruction && obstruction.occupancyRatio < 0.28
    }

    static func obstructionLooksWallLikeStrong(_ obstruction: ForwardObstruction) -> Bool {
        if obstruction.nearestDepth < 0.88 {
            return true
        }

        if obstruction.occupancyRatio > 0.58 {
            return true
        }

        if obstruction.wideObstruction &&
            (obstruction.occupancyRatio > 0.24 || obstruction.nearestDepth < 1.22) {
            return true
        }

        return false
    }
}
func source(_ value: String) -> DangerResolutionSource {
    switch value {
    case "semantic_current": return .semanticCurrent
    case "semantic_visual_proxy": return .semanticVisualProxy
    case "semantic_visual_memory": return .semanticVisualMemory
    case "semantic_memory": return .semanticMemory
    case "generic_fallback": return .genericFallback
    case "wide_fallback": return .wideFallback
    case "limited_sensors": return .limitedSensors
    default: fatalError("unknown source")
    }
}
func zone(_ value: String) -> CorridorZone {
    switch value { case "left": return .left; case "right": return .right; default: return .center }
}
func level(_ value: String) -> WarningLevel {
    switch value { case "high": return .high; case "medium": return .medium; case "low": return .low; default: return .none }
}
func candidate(_ sourceValue: String, _ score: Float, _ distance: Float,
               _ zoneValue: String = "center") -> DangerResolutionCandidate {
    DangerResolutionCandidate(source: source(sourceValue), level: .medium, score: score,
                              distance: distance, zone: zone(zoneValue))
}
let path = CommandLine.arguments[1]
for line in try String(contentsOfFile: path, encoding: .utf8).split(separator: "\n") {
    let row = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
    func number(_ name: String) -> Double { (row[name] as! NSNumber).doubleValue }
    func bool(_ name: String) -> Bool { row[name] as! Bool }
    func string(_ name: String) -> String { row[name] as! String }
    let obstruction = ForwardObstruction(nearestDepth: Float(number("depth")),
        occupancyRatio: Float(number("occupancy")), dominantZone: .center,
        wideObstruction: bool("wide"))
    let context = DangerResolutionContext(obstruction: obstruction,
        hasPotentialSemanticContext: true,
        hasRepeatedVisualSemanticContext: (row["repeated"] as? Bool) ?? false)
    let result: Bool
    switch string("type") {
    case "wallLike":
        result = DangerResolutionPolicy.obstructionLooksWallLikeStrong(obstruction)
    case "ambiguous":
        result = DangerResolutionPolicy.isAmbiguousFallback(
            candidate(string("source"), 1, Float(number("depth"))), obstruction: obstruction)
    case "preferSemantic":
        result = DangerResolutionPolicy.shouldPreferSemantic(
            candidate(string("semanticSource"), Float(number("semanticScore")),
                      Float(number("semanticDistance")), string("semanticZone")),
            over: candidate(string("fallbackSource"), Float(number("fallbackScore")),
                            Float(number("depth")), string("fallbackZone")), context: context)
    case "preferLimited":
        result = DangerResolutionPolicy.shouldPreferLimitedSensors(
            candidate("limited_sensors", Float(number("limitedScore")), Float(number("depth"))),
            over: candidate(string("fallbackSource"), Float(number("fallbackScore")), Float(number("depth"))),
            semantic: nil, context: context, baseLevel: level(string("baseLevel")))
    default: fatalError("unknown fixture")
    }
    let data = try JSONSerialization.data(withJSONObject: ["id": row["id"]!, "result": result],
                                          options: [.sortedKeys])
    print(String(data: data, encoding: .utf8)!)
}

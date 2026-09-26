import Foundation
import CoreGraphics
enum SemanticObjectCategory {
    case person
    case vehicle
    case bikeScooter
    case pole
    case trafficLight
    case trafficSign
    case obstacle
    case unknown

    var alertName: String? {
        switch self {
        case .person: return "Piéton"
        case .vehicle: return "Véhicule"
        case .bikeScooter: return "Deux-roues"
        case .pole: return "Poteau"
        case .obstacle: return "Obstacle"
        case .trafficLight, .trafficSign, .unknown: return nil
        }
    }

    var influencesAlert: Bool {
        switch self {
        case .person, .vehicle, .bikeScooter, .pole, .obstacle:
            return true
        case .trafficLight, .trafficSign, .unknown:
            return false
        }
    }

    var priorityBias: Float {
        switch self {
        case .vehicle: return 1.5
        case .bikeScooter: return 1.35
        case .person: return 1.0
        case .pole: return 0.85
        case .obstacle: return 0.75
        case .trafficLight, .trafficSign: return 0.2
        case .unknown: return 0.1
        }
    }

    var debugIdentifier: String {
        switch self {
        case .person: return "person"
        case .vehicle: return "vehicle"
        case .bikeScooter: return "bike_scooter"
        case .pole: return "pole"
        case .trafficLight: return "traffic_light"
        case .trafficSign: return "traffic_sign"
        case .obstacle: return "obstacle"
        case .unknown: return "unknown"
        }
    }
}
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
enum SemanticMemoryLifetimePolicy {
    static func isFresh(
        memoryTimestamp: CFTimeInterval,
        currentTimestamp: CFTimeInterval,
        lifetime: CFTimeInterval
    ) -> Bool {
        currentTimestamp - memoryTimestamp <= lifetime
    }

    static func hasExpired(
        memoryTimestamp: CFTimeInterval,
        currentTimestamp: CFTimeInterval,
        lifetime: CFTimeInterval
    ) -> Bool {
        currentTimestamp - memoryTimestamp > lifetime
    }
}
struct DetectedItem {
    let category: SemanticObjectCategory
    let score: Float
    let normalizedRect: CGRect
    let zone: CorridorZone
    let distanceMeters: Float? = nil
}
struct Reference {
func corridorZone(forNormalizedX midX: CGFloat) -> CorridorZone {
            if midX < 0.39 { return .left }
            if midX > 0.61 { return .right }
            return .center
        }
func qualifiesForVisualOnlySemanticCue(_ item: DetectedItem) -> Bool {
            guard item.distanceMeters == nil else { return false }
            guard item.score >= visualOnlyConfidenceThreshold(for: item.category) else { return false }

            let area = Float(item.normalizedRect.width * item.normalizedRect.height)
            let maxY = Float(item.normalizedRect.maxY)
            let centerBoost = item.zone == .center

            switch item.category {
            case .vehicle:
                return maxY >= 0.5 && (area >= 0.035 || (centerBoost && area >= 0.026))
            case .bikeScooter:
                return maxY >= 0.52 && (area >= 0.032 || (centerBoost && area >= 0.024))
            case .person:
                return maxY >= 0.5 && (area >= 0.026 || (centerBoost && area >= 0.02))
            case .pole:
                return maxY >= 0.45 && (area >= 0.014 || (centerBoost && area >= 0.01))
            case .obstacle:
                return maxY >= 0.52 && area >= 0.032
            case .trafficLight, .trafficSign, .unknown:
                return false
            }
        }
func visualOnlyConfidenceThreshold(for category: SemanticObjectCategory) -> Float {
            switch category {
            case .vehicle: return 0.82
            case .bikeScooter: return 0.82
            case .person: return 0.84
            case .pole: return 0.86
            case .obstacle: return 0.86
            case .trafficLight, .trafficSign, .unknown:
                return 1
            }
        }
}
let reference = Reference()
let path = CommandLine.arguments[1]
for line in try String(contentsOfFile: path, encoding: .utf8).split(separator: "\n") {
    let row = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
    func number(_ name: String) -> Double { (row[name] as! NSNumber).doubleValue }
    let kind = row["type"] as! String
    let result: Any
    switch kind {
    case "zone":
        result = reference.corridorZone(forNormalizedX: CGFloat(number("x"))).voiceSuffix
    case "qualification":
        let categories: [SemanticObjectCategory] = [.person, .vehicle, .bikeScooter, .pole, .trafficLight, .trafficSign]
        let rect = CGRect(x: number("left"), y: number("top"),
                          width: number("right") - number("left"),
                          height: number("bottom") - number("top"))
        result = reference.qualifiesForVisualOnlySemanticCue(DetectedItem(
            category: categories[Int(number("classId"))], score: Float(number("confidence")),
            normalizedRect: rect, zone: reference.corridorZone(forNormalizedX: rect.midX)))
    case "fresh":
        result = SemanticMemoryLifetimePolicy.isFresh(memoryTimestamp: number("observedAtMs") / 1000,
            currentTimestamp: number("nowMs") / 1000, lifetime: number("lifetimeMs") / 1000)
    case "expired":
        result = SemanticMemoryLifetimePolicy.hasExpired(memoryTimestamp: number("observedAtMs") / 1000,
            currentTimestamp: number("nowMs") / 1000, lifetime: number("lifetimeMs") / 1000)
    default: fatalError("Unknown fixture")
    }
    let data = try JSONSerialization.data(withJSONObject: ["id": row["id"]!, "result": result], options: [.sortedKeys])
    print(String(data: data, encoding: .utf8)!)
}

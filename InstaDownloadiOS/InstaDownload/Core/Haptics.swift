import UIKit

@MainActor
enum Haptics {
    static func start(enabled: Bool) {
        guard enabled else { return }
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.prepare()
        generator.impactOccurred(intensity: 0.8)
    }

    static func complete(enabled: Bool) {
        guard enabled else { return }
        notify(.success, enabled: enabled)
    }

    static func failure(enabled: Bool) {
        notify(.error, enabled: enabled)
    }

    private static func notify(_ type: UINotificationFeedbackGenerator.FeedbackType, enabled: Bool) {
        guard enabled else { return }
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(type)
    }
}

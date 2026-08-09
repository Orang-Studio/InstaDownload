import SwiftUI

enum Brand {
    static let purple = Color(hex: 0x833AB4)
    static let pink = Color(hex: 0xE1306C)
    static let orange = Color(hex: 0xF77737)

    static let purpleDark = Color(hex: 0x2D1B2E)
    static let pinkDark = Color(hex: 0x4A1428)
    static let orangeDark = Color(hex: 0x3D1A0A)
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

enum AppTheme: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "System default"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var symbolName: String {
        switch self {
        case .system: "iphone"
        case .light: "sun.max"
        case .dark: "moon"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct BrandBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        LinearGradient(
            colors: colorScheme == .dark
                ? [Brand.purpleDark, Brand.pinkDark, Brand.orangeDark]
                : [Brand.purple, Brand.pink, Brand.orange],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea()
    }
}

import SwiftUI

struct GlassGroup<Content: View>: View {
    var spacing: CGFloat = 8
    @ViewBuilder var content: Content

    var body: some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content }
        } else {
            content
        }
        #else
        content
        #endif
    }
}

private struct GlassSurface<S: Shape>: ViewModifier {
    let shape: S
    let tint: Color?
    let interactive: Bool

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    @ViewBuilder
    func body(content: Content) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            content.glassEffect(glass, in: shape)
        } else {
            content.background { legacyBackground }
        }
        #else
        content.background { legacyBackground }
        #endif
    }

    #if compiler(>=6.2)
    @available(iOS 26.0, *)
    private var glass: Glass {
        var glass = Glass.regular
        if let tint {
            glass = glass.tint(tint)
        }
        return glass.interactive(interactive)
    }
    #endif

    private var legacyBackground: some View {
        ZStack {
            if reduceTransparency {
                shape.fill(colorScheme == .dark ? Color(white: 0.14) : Color(white: 0.97))
            } else {
                shape.fill(.ultraThinMaterial)
            }
            if let tint {
                shape.fill(tint.opacity(reduceTransparency ? 1 : 0.7))
            }
            shape.stroke(
                Color.white.opacity(colorScheme == .dark ? 0.10 : 0.30),
                lineWidth: 0.75
            )
        }
    }
}

extension View {

    func glassSurface(
        _ shape: some Shape,
        tint: Color? = nil,
        interactive: Bool = false
    ) -> some View {
        modifier(GlassSurface(shape: shape, tint: tint, interactive: interactive))
    }

    @ViewBuilder
    func glassID(_ id: some Hashable & Sendable, in namespace: Namespace.ID) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.glassEffectID(id, in: namespace)
        } else {
            self
        }
        #else
        self
        #endif
    }

    @ViewBuilder
    func glassButtonStyle() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glass)
        } else {
            self.buttonStyle(.bordered)
        }
        #else
        self.buttonStyle(.bordered)
        #endif
    }
}

struct PrimaryActionButtonStyle: ButtonStyle {
    var tint: Color

    func makeBody(configuration: Configuration) -> some View {
        Surface(configuration: configuration, tint: tint)
    }

    private struct Surface: View {
        let configuration: ButtonStyleConfiguration
        let tint: Color

        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            configuration.label
                .font(.headline)
                .foregroundStyle(.white.opacity(isEnabled ? 1 : 0.6))
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(
                    tint.opacity(isEnabled ? 1 : 0.45),
                    in: .rect(cornerRadius: 16, style: .continuous)
                )
                .shadow(color: tint.opacity(isEnabled ? 0.35 : 0), radius: 10, y: 4)
                .scaleEffect(configuration.isPressed ? 0.975 : 1)
                .animation(.spring(response: 0.3, dampingFraction: 0.7), value: configuration.isPressed)
        }
    }
}

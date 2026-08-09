import SwiftUI

@main
struct InstaDownloadApp: App {
    @State private var settings = AppSettings.shared

    var body: some Scene {
        WindowGroup {
            DownloaderView()
                .environment(settings)
                .preferredColorScheme(settings.theme.colorScheme)
                .tint(Brand.pink)
        }
    }
}

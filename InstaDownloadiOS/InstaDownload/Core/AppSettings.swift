import Foundation
import Observation

enum SaveDestination: String, CaseIterable, Identifiable {
    case photos, files

    var id: String { rawValue }

    var label: String {
        switch self {
        case .photos: "Photo library"
        case .files: "Custom folder"
        }
    }

    var detail: String {
        switch self {
        case .photos: "Reels and photos are added to Recents."
        case .files: "Saved as files into a folder you pick."
        }
    }

    var symbolName: String {
        switch self {
        case .photos: "photo.on.rectangle.angled"
        case .files: "folder"
        }
    }
}

@Observable
final class AppSettings {
    static let shared = AppSettings()

    var theme: AppTheme {
        didSet { defaults.set(theme.rawValue, forKey: Keys.theme) }
    }

    var hapticsEnabled: Bool {
        didSet { defaults.set(hapticsEnabled, forKey: Keys.haptics) }
    }

    var destination: SaveDestination {
        didSet { defaults.set(destination.rawValue, forKey: Keys.destination) }
    }

    private(set) var folderName: String?
    private(set) var folderBookmark: Data?

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = AppTheme(rawValue: defaults.string(forKey: Keys.theme) ?? "") ?? .system
        hapticsEnabled = defaults.object(forKey: Keys.haptics) as? Bool ?? true
        destination = SaveDestination(rawValue: defaults.string(forKey: Keys.destination) ?? "") ?? .photos
        folderName = defaults.string(forKey: Keys.folderName)
        folderBookmark = defaults.data(forKey: Keys.folderBookmark)
    }

    func setFolder(_ url: URL) throws {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }

        let bookmark = try url.bookmarkData()
        folderBookmark = bookmark
        folderName = url.lastPathComponent
        defaults.set(bookmark, forKey: Keys.folderBookmark)
        defaults.set(folderName, forKey: Keys.folderName)
        destination = .files
    }

    func clearFolder() {
        folderBookmark = nil
        folderName = nil
        defaults.removeObject(forKey: Keys.folderBookmark)
        defaults.removeObject(forKey: Keys.folderName)
        destination = .photos
    }

    var folderDisplayName: String { folderName ?? "No folder chosen" }

    private enum Keys {
        static let theme = "theme"
        static let haptics = "haptics"
        static let destination = "save_destination"
        static let folderName = "download_folder_name"
        static let folderBookmark = "download_folder_bookmark"
    }
}

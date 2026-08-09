import Foundation
import Photos

enum MediaSaver {

    static func save(
        _ items: [MediaItem],
        to destination: SaveDestination,
        folderBookmark: Data?
    ) async throws {

        let folder: URL? = destination == .files
            ? try resolveFolder(from: folderBookmark)
            : nil

        for (index, item) in items.enumerated() {
            let data = try await InstagramDownloader.data(for: item.url)
            let name = fileName(for: item, index: index)

            switch destination {
            case .photos:
                try await addToPhotoLibrary(data: data, isVideo: item.isVideo, fileName: name)
            case .files:
                guard let folder else {
                    throw DownloadError.unsupported("Choose a folder in Settings before saving to Files.")
                }
                try write(data, named: name, into: folder)
            }
        }
    }

    private static func addToPhotoLibrary(data: Data, isVideo: Bool, fileName: String) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            throw DownloadError.unsupported(
                "InstaDownload needs permission to add to your photo library. "
                + "Turn it on in Settings › Privacy & Security › Photos, "
                + "or switch to a custom folder in the app's settings."
            )
        }

        let staged = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
        try data.write(to: staged, options: .atomic)
        defer { try? FileManager.default.removeItem(at: staged) }

        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: isVideo ? .video : .photo, fileURL: staged, options: nil)
        }
    }

    private static func resolveFolder(from bookmark: Data?) throws -> URL {
        guard let bookmark else {
            throw DownloadError.unsupported("Choose a folder in Settings before saving to Files.")
        }
        var isStale = false
        do {
            return try URL(
                resolvingBookmarkData: bookmark,
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            )
        } catch {
            throw DownloadError.unsupported(
                "Lost access to the saved folder. Pick it again in Settings."
            )
        }
    }

    private static func write(_ data: Data, named name: String, into folder: URL) throws {
        guard folder.startAccessingSecurityScopedResource() else {
            throw DownloadError.unsupported(
                "Lost access to the saved folder. Pick it again in Settings."
            )
        }
        defer { folder.stopAccessingSecurityScopedResource() }
        try data.write(to: folder.appendingPathComponent(name), options: .atomic)
    }

    private static func fileName(for item: MediaItem, index: Int) -> String {
        let stamp = Int(Date().timeIntervalSince1970 * 1000) + index
        return item.isVideo ? "instagram_video_\(stamp).mp4" : "instagram_image_\(stamp).jpg"
    }
}

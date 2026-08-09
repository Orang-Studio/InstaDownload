import Foundation

struct MediaItem: Identifiable, Hashable, Sendable {
    let url: URL
    let isVideo: Bool
    let thumbnailURL: URL?

    var id: String { url.absoluteString }

    var previewURL: URL? { thumbnailURL ?? (isVideo ? nil : url) }
}

enum DownloadError: LocalizedError {

    case invalidURL(String)

    case unsupported(String)

    case failed(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL(let url): "Invalid Instagram URL: \(url)"
        case .unsupported(let message): message
        case .failed(let message): message
        }
    }
}

private struct RE {
    private let regex: NSRegularExpression

    init(_ pattern: String, options: NSRegularExpression.Options = []) {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else {
            preconditionFailure("Invalid regular expression literal: \(pattern)")
        }
        self.regex = regex
    }

    func firstGroup(_ index: Int = 1, in text: String) -> String? {
        let text = text as NSString
        let range = NSRange(location: 0, length: text.length)
        guard let match = regex.firstMatch(in: text as String, range: range),
              index < match.numberOfRanges
        else { return nil }
        let group = match.range(at: index)
        return group.location == NSNotFound ? nil : text.substring(with: group)
    }

    func allGroups(_ index: Int = 1, in text: String) -> [String] {
        let text = text as NSString
        let range = NSRange(location: 0, length: text.length)
        return regex.matches(in: text as String, range: range).compactMap { match in
            guard index < match.numberOfRanges else { return nil }
            let group = match.range(at: index)
            return group.location == NSNotFound ? nil : text.substring(with: group)
        }
    }

    func matches(_ text: String) -> Bool {
        let text = text as NSString
        return regex.firstMatch(in: text as String, range: NSRange(location: 0, length: text.length)) != nil
    }
}

enum InstagramDownloader {

    private struct StoryRequest {
        let username: String
        let mediaID: String
    }

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60
        configuration.httpShouldSetCookies = true
        configuration.httpCookieAcceptPolicy = .always
        configuration.httpCookieStorage = HTTPCookieStorage.shared
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration)
    }()

    private static let desktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
    private static let mobileUA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
        + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    private static let shortcodeRE = RE(#"(?:instagram\.com|instagr\.am)/(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)"#)
    private static let storyRE = RE(#"(?:instagram\.com|instagr\.am)/stories/([A-Za-z0-9._]+)/([0-9]+)"#)
    private static let validPostRE = RE(#"^https?://(www\.)?(instagram\.com|instagr\.am)/(p|reel|tv)/[A-Za-z0-9_-]+"#)
    private static let storyURLRE = RE(#"^https?://(www\.)?instagram\.com/stories/[A-Za-z0-9._]+/?.*$"#)

    private static let videoURLRE = RE(#"\\"video_url\\":\\"(https:(?:(?!\\").)*)"#)
    private static let displayURLRE = RE(#"\\"display_url\\":\\"(https:(?:(?!\\").)*)"#)
    private static let isVideoRE = RE(#"\\"is_video\\":true"#)
    private static let embeddedImageRE = RE(#"<img[^>]*class="EmbeddedMediaImage"[^>]*src="(https:[^"]*)""#)
    private static let ogImageRE = RE(#"<meta property="og:image" content="([^"]+)""#)
    private static let sjsScriptRE = RE(
        #"<script\b[^>]*\bdata-sjs[^>]*>(\{.+?\})</script>"#,
        options: [.dotMatchesLineSeparators]
    )
    private static let percentEscapeRE = RE(#"%[0-9A-Fa-f]{2}"#)

    static func isValidPostURL(_ url: String) -> Bool { validPostRE.matches(url) }

    static func isStoryURL(_ url: String) -> Bool { storyURLRE.matches(url) }

    static func mediaItems(for postURL: String) async throws -> [MediaItem] {
        if let story = extractStory(from: postURL) {
            return try await publicStory(story)
        }

        guard let shortcode = extractShortcode(from: postURL) else {
            throw DownloadError.invalidURL(postURL)
        }

        let embedFailure: String
        do {
            return try await tryEmbedPage(shortcode: shortcode)
        } catch let error as DownloadError {
            embedFailure = error.errorDescription ?? "no media found"
        } catch {
            embedFailure = error.localizedDescription
        }

        do {
            return try await tryPostPage(shortcode: shortcode)
        } catch {
            let postFailure = (error as? DownloadError)?.errorDescription ?? error.localizedDescription
            throw DownloadError.failed("""
                Could not fetch this post. It may be private, age-restricted, or deleted. \
                This build only downloads public content. Use the login build for private posts and stories.

                Embed: \(embedFailure)
                Post page: \(postFailure)
                """)
        }
    }

    private static func publicStory(_ story: StoryRequest) async throws -> [MediaItem] {
        let userID = try await fetchPublicUserID(username: story.username)
        let reels = try await fetchPublicReelsMedia(
            userID: userID,
            mediaID: story.mediaID,
            username: story.username
        )
        let items = extractStoryMedia(reels, userID: userID, mediaID: story.mediaID)
        if !items.isEmpty { return items }

        let reelCount = (reels["reels"] as? [String: Any])?.count ?? 0
        throw DownloadError.unsupported("""
            Instagram did not expose this story through public anonymous endpoints. \
            Resolved @\(story.username) to user id \(userID), but reels_media returned \
            \(reelCount) reel(s). Normal stories require a logged-in session or may have expired.
            """)
    }

    private static func fetchPublicUserID(username: String) async throws -> String {
        let encoded = username.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? username
        let url = URL(string: "https://www.instagram.com/api/v1/users/web_profile_info/?username=\(encoded)")!
        let (data, response) = try await get(url, headers: [
            "User-Agent": desktopUA,
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
            "Referer": "https://www.instagram.com/\(username)/",
            "X-IG-App-ID": "936619743392459",
            "X-ASBD-ID": "129477",
            "X-Requested-With": "XMLHttpRequest",
        ])

        guard (200..<300).contains(response.statusCode) else {
            let body = decodeText(data) ?? ""
            throw DownloadError.failed("Profile HTTP \(response.statusCode): \(body.prefix(200))")
        }

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let user = (json["data"] as? [String: Any])?["user"] as? [String: Any]
        else {
            throw DownloadError.failed("Profile response did not include user data")
        }

        if user["is_private"] as? Bool == true {
            throw DownloadError.unsupported("@\(username) is private")
        }

        guard let id = string(user["id"]) else {
            throw DownloadError.failed("Profile response did not include user id")
        }
        return id
    }

    private static func fetchPublicReelsMedia(
        userID: String,
        mediaID: String,
        username: String
    ) async throws -> [String: Any] {
        let url = URL(
            string: "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=\(userID)&media_id=\(mediaID)"
        )!
        let (data, response) = try await get(url, headers: [
            "User-Agent": desktopUA,
            "Accept": "*/*",
            "Accept-Language": "en-US,en;q=0.9",
            "Referer": "https://www.instagram.com/stories/\(username)/\(mediaID)/",
            "X-IG-App-ID": "936619743392459",
            "X-ASBD-ID": "129477",
            "X-Requested-With": "XMLHttpRequest",
        ])

        let body = decodeText(data) ?? ""
        if body.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("<") {
            throw DownloadError.failed("Story HTTP \(response.statusCode): got HTML instead of JSON")
        }
        guard (200..<300).contains(response.statusCode) else {
            throw DownloadError.failed("Story HTTP \(response.statusCode): \(body.prefix(200))")
        }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw DownloadError.failed("Story HTTP \(response.statusCode): response was not a JSON object")
        }
        return json
    }

    private static func extractStoryMedia(
        _ json: [String: Any],
        userID: String,
        mediaID: String
    ) -> [MediaItem] {
        guard let reels = json["reels"] as? [String: Any] else { return [] }

        guard let reel = (reels[userID] as? [String: Any])
            ?? reels.values.compactMap({ $0 as? [String: Any] }).first
        else { return [] }

        guard let items = reel["items"] as? [[String: Any]] else { return [] }
        for item in items {
            let id = string(item["id"]) ?? ""
            let pk = string(item["pk"]) ?? ""
            if id == mediaID || id.hasPrefix("\(mediaID)_") || pk == mediaID {
                return [mediaItem(from: item)].compactMap { $0 }
            }
        }
        return []
    }

    private static func mediaItem(from item: [String: Any]) -> MediaItem? {
        let poster = ((item["image_versions2"] as? [String: Any])?["candidates"] as? [[String: Any]])?
            .first.flatMap { string($0["url"]) }
            ?? string(item["display_url"])

        if let videoURL = (item["video_versions"] as? [[String: Any]])?
            .first.flatMap({ string($0["url"]) }),
           let url = URL(string: videoURL) {
            return MediaItem(
                url: url,
                isVideo: true,
                thumbnailURL: poster.flatMap { URL(string: $0) }
            )
        }

        if let poster, let url = URL(string: poster) {
            return MediaItem(url: url, isVideo: false, thumbnailURL: url)
        }

        return nil
    }

    private static func embedRefusal(in html: String) -> String? {
        if html.contains("Please wait a few minutes before you try again") {
            return "Instagram is rate-limiting this device. Wait a few minutes and try again."
        }

        if html.contains(#""contextJSON":null"#) {
            return "Instagram would not serve this post to a logged-out client. "
                + "That usually means it is age-restricted or login-gated, but it can also be "
                + "private, deleted, or region-blocked; the embed page doesn't say which. "
                + "This build is public-only; the login build can fetch it."
        }

        if !html.contains(#""contextJSON":""#) && html.contains("/accounts/login/") {
            return "Instagram served a login wall. This build only downloads public content."
        }

        return nil
    }

    private static func tryEmbedPage(shortcode: String) async throws -> [MediaItem] {
        let url = URL(string: "https://www.instagram.com/p/\(shortcode)/embed/captioned/")!
        let (data, response) = try await get(url, headers: [
            "User-Agent": mobileUA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.5",
            "Referer": "https://www.instagram.com/",
        ])

        guard let html = decodeText(data) else {
            throw DownloadError.failed("Embed HTTP \(response.statusCode): empty body")
        }
        guard (200..<300).contains(response.statusCode) else {
            throw DownloadError.failed("Embed HTTP \(response.statusCode): \(html.prefix(120))")
        }

        if let refusal = embedRefusal(in: html) {
            throw DownloadError.unsupported(refusal)
        }

        if let video = videoURLRE.firstGroup(in: html) {
            let poster = displayURLRE.firstGroup(in: html).map(unescape)
            guard let url = URL(string: unescape(video)) else {
                throw DownloadError.failed("Embed HTTP \(response.statusCode): malformed video URL")
            }
            return [MediaItem(url: url, isVideo: true, thumbnailURL: poster.flatMap { URL(string: $0) })]
        }

        if isVideoRE.matches(html) {
            throw DownloadError.failed(
                "Embed HTTP \(response.statusCode): is_video=true but video_url missing from embed response"
            )
        }

        let displayImages = uniqueImages(from: displayURLRE.allGroups(in: html))
        if !displayImages.isEmpty { return displayImages }

        let embeddedImages = uniqueImages(from: embeddedImageRE.allGroups(in: html))
        if !embeddedImages.isEmpty { return embeddedImages }

        let ogImages = uniqueImages(from: ogImageRE.allGroups(in: html))
        if !ogImages.isEmpty { return ogImages }

        throw DownloadError.failed(
            "Embed HTTP \(response.statusCode): no media URL found (\(html.count) chars)"
        )
    }

    private static func uniqueImages(from rawURLs: [String]) -> [MediaItem] {
        var seen = Set<String>()
        return rawURLs.compactMap { raw in
            let unescaped = unescape(raw)
            guard seen.insert(unescaped).inserted, let url = URL(string: unescaped) else { return nil }
            return MediaItem(url: url, isVideo: false, thumbnailURL: nil)
        }
    }

    private static func tryPostPage(shortcode: String) async throws -> [MediaItem] {
        let url = URL(string: "https://www.instagram.com/p/\(shortcode)/")!
        let (data, response) = try await get(url, headers: [
            "User-Agent": "Googlebot/2.1 (+http://www.google.com/bot.html)",
        ])

        guard let html = decodeText(data) else {
            throw DownloadError.failed("Post HTTP \(response.statusCode): empty body")
        }
        guard (200..<300).contains(response.statusCode) else {
            throw DownloadError.failed("Post HTTP \(response.statusCode)")
        }

        let expectedMediaID = try mediaID(fromShortcode: shortcode)
        for payload in sjsScriptRE.allGroups(in: html) {
            guard let json = try? JSONSerialization.jsonObject(with: Data(payload.utf8)),
                  let product = findPublicProduct(json, expectedMediaID: expectedMediaID)
            else { continue }
            let items = extractProductMedia(product)
            if !items.isEmpty { return items }
        }

        throw DownloadError.failed("Post HTTP \(response.statusCode): no public media found")
    }

    private static func findPublicProduct(_ value: Any, expectedMediaID: String) -> [String: Any]? {
        if let object = value as? [String: Any] {
            if let ungated = object["if_not_gated_logged_out"] as? [String: Any],
               string(ungated["pk"]) == expectedMediaID || string(ungated["id"]) == expectedMediaID {
                return ungated
            }

            let matchesID = string(object["pk"]) == expectedMediaID || string(object["id"]) == expectedMediaID
            let hasMedia = object["video_versions"] != nil
                || object["carousel_media"] != nil
                || object["image_versions2"] != nil
            if matchesID && hasMedia { return object }

            for nested in object.values {
                if let found = findPublicProduct(nested, expectedMediaID: expectedMediaID) { return found }
            }
        } else if let array = value as? [Any] {
            for nested in array {
                if let found = findPublicProduct(nested, expectedMediaID: expectedMediaID) { return found }
            }
        }
        return nil
    }

    private static func extractProductMedia(_ product: [String: Any]) -> [MediaItem] {
        if let carousel = product["carousel_media"] as? [[String: Any]] {
            return carousel.compactMap { mediaItem(from: $0) }
        }
        return [mediaItem(from: product)].compactMap { $0 }
    }

    private static func mediaID(fromShortcode shortcode: String) throws -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        var id: Int64 = 0
        for character in shortcode {
            guard let digit = alphabet.firstIndex(of: character) else {
                throw DownloadError.invalidURL("Invalid Instagram shortcode")
            }
            let (scaled, scaleOverflow) = id.multipliedReportingOverflow(by: 64)
            let (next, addOverflow) = scaled.addingReportingOverflow(Int64(digit))
            guard !scaleOverflow, !addOverflow else {
                throw DownloadError.invalidURL("Invalid Instagram shortcode")
            }
            id = next
        }
        return String(id)
    }

    private static func mediaRequest(_ url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue(mobileUA, forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.instagram.com/", forHTTPHeaderField: "Referer")
        return request
    }

    static func data(for url: URL) async throws -> Data {
        let (data, response) = try await session.data(for: mediaRequest(url))
        guard let http = response as? HTTPURLResponse else {
            throw DownloadError.failed("Download failed: no HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw DownloadError.failed("Download HTTP \(http.statusCode)")
        }
        return data
    }

    private static func get(_ url: URL, headers: [String: String]) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url)
        for (field, value) in headers {
            request.setValue(value, forHTTPHeaderField: field)
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw DownloadError.failed("No HTTP response from \(url.host() ?? url.absoluteString)")
        }
        return (data, http)
    }

    private static func decodeText(_ data: Data) -> String? {
        String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1)
    }

    private static func string(_ value: Any?) -> String? {
        if let string = value as? String { return string.isEmpty ? nil : string }
        if let number = value as? NSNumber { return number.stringValue }
        return nil
    }

    private static let escapedAmpersand = "\\" + "u0026"

    private static func unescape(_ raw: String) -> String {
        var value = raw
            .replacingOccurrences(of: #"\\\/"#, with: "/")
            .replacingOccurrences(of: escapedAmpersand, with: "&")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#039;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")

        if percentEscapeRE.matches(value), let decoded = value.removingPercentEncoding {
            value = decoded
        }
        return value
    }

    private static func extractShortcode(from url: String) -> String? {
        shortcodeRE.firstGroup(in: url).map { String($0.prefix(11)) }
    }

    private static func extractStory(from url: String) -> StoryRequest? {
        guard let username = storyRE.firstGroup(1, in: url),
              let mediaID = storyRE.firstGroup(2, in: url)
        else { return nil }
        return StoryRequest(username: username, mediaID: mediaID)
    }
}

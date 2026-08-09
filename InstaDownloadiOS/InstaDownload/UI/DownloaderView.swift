import SwiftUI
import UIKit

struct DownloaderView: View {
    @Environment(AppSettings.self) private var settings
    @Environment(\.openURL) private var openURL

    @State private var url = ""
    @State private var media: [MediaItem]?
    @State private var isLoading = false
    @State private var isSaving = false
    @State private var didComplete = false
    @State private var urlError: String?
    @State private var fullError: String?
    @State private var showSettings = false

    @FocusState private var urlFieldFocused: Bool
    @Namespace private var glass

    private var trimmedURL: String {
        url.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isStory: Bool { InstagramDownloader.isStoryURL(trimmedURL) }

    var body: some View {
        NavigationStack {
            ScrollView {

                GlassGroup(spacing: 12) {
                    VStack(spacing: 16) {
                        hero
                            .padding(.top, 24)
                            .padding(.bottom, 24)

                        inputCard

                        if let media, !media.isEmpty {
                            previewCard(media)
                        }

                        if let fullError {
                            errorCard(fullError)
                        }

                        gitHubCredit
                            .padding(.top, 8)
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 32)
                }
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .background { BrandBackground() }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {

                    Button {
                        showSettings = true
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                            .labelStyle(.iconOnly)
                    }
                    .tint(.white)
                }
            }
            .overlay(alignment: .top) {
                if isLoading {
                    ProgressView()
                        .progressViewStyle(.linear)
                        .tint(.white)
                        .transition(.opacity)
                }
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
        .animation(.smooth(duration: 0.25), value: isLoading)
        .animation(.smooth(duration: 0.3), value: media)
        .animation(.smooth(duration: 0.3), value: fullError)
        .animation(.smooth(duration: 0.3), value: isStory)
        .task(id: url) { await urlDidChange() }
    }

    private var hero: some View {
        VStack(spacing: 0) {
            Image(systemName: "arrow.down.to.line")
                .font(.system(size: 40, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 88, height: 88)
                .glassSurface(.rect(cornerRadius: 24, style: .continuous))
                .glassID("hero", in: glass)

            Text("InstaDownload")
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(.white)
                .padding(.top, 20)

            Text("Save reels & posts to your device")
                .font(.body)
                .foregroundStyle(.white.opacity(0.8))
                .padding(.top, 4)
        }
        .multilineTextAlignment(.center)
    }

    private var inputCard: some View {
        VStack(spacing: 20) {
            urlField

            if isStory {
                storyNotice
                    .transition(.opacity.combined(with: .scale(scale: 0.97, anchor: .top)))
            }

            Button {
                Task { await download() }
            } label: {
                HStack(spacing: 8) {
                    if isSaving {
                        ProgressView()
                            .tint(.white)
                            .controlSize(.small)
                    } else {
                        Image(systemName: didComplete ? "checkmark" : "arrow.down.to.line")
                            .font(.system(size: 17, weight: .semibold))
                    }
                    Text(actionTitle)
                }
                .contentTransition(.opacity)
            }
            .buttonStyle(PrimaryActionButtonStyle(tint: Brand.pink))
            .disabled(isSaving || isStory)
        }
        .padding(24)

        .glassSurface(.rect(cornerRadius: 28, style: .continuous))
        .glassID("input", in: glass)
    }

    private var actionTitle: String {
        if isSaving { return "Saving…" }
        if didComplete { return "Saved!" }
        return "Download"
    }

    private var urlField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Instagram URL")
                .font(.footnote.weight(.medium))
                .foregroundStyle(urlError == nil ? Color.secondary : Color.red)

            HStack(spacing: 8) {
                TextField("https://www.instagram.com/reel/…", text: $url, axis: .vertical)
                    .lineLimit(1...3)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .submitLabel(.go)
                    .focused($urlFieldFocused)
                    .disabled(isSaving)
                    .onSubmit { Task { await download() } }

                PasteButton(payloadType: String.self) { strings in
                    guard let pasted = strings.first else { return }
                    Task { @MainActor in apply(pasted: pasted) }
                }
                .labelStyle(.iconOnly)
                .buttonBorderShape(.circle)
                .tint(Brand.pink)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)

            .background(fieldFill, in: .rect(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(fieldStroke, lineWidth: urlFieldFocused || urlError != nil ? 2 : 1)
            }
            .animation(.smooth(duration: 0.2), value: urlFieldFocused)

            if let urlError {
                Text(urlError)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .transition(.opacity)
            }
        }
        .animation(.smooth(duration: 0.2), value: urlError)
    }

    private var fieldFill: Color {
        Color.primary.opacity(0.06)
    }

    private var fieldStroke: Color {
        if urlError != nil { return .red }
        return urlFieldFocused ? Brand.pink : Color.primary.opacity(0.15)
    }

    private var storyNotice: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Stories aren't supported")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(Brand.orange)
            Text(
                "Instagram only serves Stories to logged-in accounts, so they can't be "
                + "downloaded here. Reels and posts work as usual."
            )
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Brand.orange.opacity(0.15), in: .rect(cornerRadius: 16, style: .continuous))
    }

    private func previewCard(_ items: [MediaItem]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(items.count > 1 ? "\(items.count) items" : "Preview")
                .font(.subheadline.weight(.bold))

            ScrollView(.horizontal) {
                HStack(spacing: 12) {
                    ForEach(items) { MediaThumbnail(item: $0) }
                }
            }
            .scrollIndicators(.hidden)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .glassSurface(.rect(cornerRadius: 28, style: .continuous))

        .glassID("preview", in: glass)
    }

    private func errorCard(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Error", systemImage: "exclamationmark.triangle.fill")
                    .font(.subheadline.weight(.bold))
                Spacer()
                Button {
                    UIPasteboard.general.string = message
                } label: {
                    Label("Copy error", systemImage: "doc.on.doc")
                        .labelStyle(.iconOnly)
                }
                Button("Dismiss") { fullError = nil }
                    .font(.subheadline)
            }

            Text(message)
                .font(.footnote)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text("Instagram may have changed. Updating to the latest version usually fixes this.")
                .font(.subheadline.weight(.medium))

            Button {
                openURL(URL(string: "https://github.com/Orang-Studio/InstaDownload/releases/latest")!)
            } label: {
                Label("Update to latest release", systemImage: "arrow.down.to.line")
                    .font(.subheadline.weight(.bold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }

            .buttonStyle(.bordered)
        }
        .padding(16)
        .glassSurface(.rect(cornerRadius: 24, style: .continuous), tint: .red.opacity(0.25))
        .glassID("error", in: glass)
        .tint(.white)
        .foregroundStyle(.white)
    }

    private var gitHubCredit: some View {
        VStack(spacing: 6) {
            if let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String {
                Text("v\(version)")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.6))
            }

            Button {
                openURL(URL(string: "https://github.com/Orang-Studio/InstaDownload")!)
            } label: {
                HStack(spacing: 8) {
                    Image("GitHubMark")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 16, height: 16)
                    Text("Made by Vakarux")
                        .font(.subheadline)
                }
                .foregroundStyle(.white.opacity(0.9))
            }
            .glassButtonStyle()
            .tint(.white)
        }
    }

    private func urlDidChange() async {
        let trimmed = trimmedURL
        guard !trimmed.isEmpty, InstagramDownloader.isValidPostURL(trimmed) else {
            media = nil
            isLoading = false
            return
        }

        do {
            try await Task.sleep(for: .milliseconds(350))
        } catch {
            return
        }

        urlError = nil
        fullError = nil
        media = nil
        didComplete = false
        isLoading = true

        do {
            let items = try await InstagramDownloader.mediaItems(for: trimmed)
            guard !Task.isCancelled else { return }
            isLoading = false
            Haptics.start(enabled: settings.hapticsEnabled)
            media = items
        } catch {
            guard !Task.isCancelled else { return }
            isLoading = false
            fullError = describe(error)
        }
    }

    private func download() async {
        let trimmed = trimmedURL

        guard !trimmed.isEmpty else {
            urlError = "Please enter a URL"
            return
        }
        guard InstagramDownloader.isValidPostURL(trimmed) else {
            urlError = "Not a valid Instagram post or reel URL"
            return
        }

        urlFieldFocused = false
        urlError = nil
        fullError = nil

        let items: [MediaItem]
        if let media, !media.isEmpty {
            items = media
        } else {
            isLoading = true
            do {
                items = try await InstagramDownloader.mediaItems(for: trimmed)
            } catch {
                isLoading = false
                fullError = describe(error)
                return
            }
            isLoading = false
            media = items
        }

        Haptics.start(enabled: settings.hapticsEnabled)
        isSaving = true
        do {
            try await MediaSaver.save(
                items,
                to: settings.destination,
                folderBookmark: settings.folderBookmark
            )
            isSaving = false
            Haptics.complete(enabled: settings.hapticsEnabled)
            didComplete = true
            try? await Task.sleep(for: .seconds(2.5))
            didComplete = false
            media = nil
        } catch {
            isSaving = false
            Haptics.failure(enabled: settings.hapticsEnabled)
            fullError = describe(error)
        }
    }

    private func apply(pasted text: String) {
        url = text.trimmingCharacters(in: .whitespacesAndNewlines)
        urlError = nil
        fullError = nil
        media = nil
    }

    private func describe(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }
}

private struct MediaThumbnail: View {
    let item: MediaItem

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            Color.black.opacity(0.15)

            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else if failed || item.previewURL == nil {
                Image(systemName: item.isVideo ? "film" : "photo")
                    .font(.system(size: 32))
                    .foregroundStyle(.white.opacity(0.7))
            } else {
                ProgressView().tint(.white)
            }

            if item.isVideo, image != nil {
                Image(systemName: "play.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(.black.opacity(0.45), in: .circle)
            }
        }
        .frame(width: 120, height: 150)
        .clipShape(.rect(cornerRadius: 16, style: .continuous))
        .accessibilityLabel(item.isVideo ? "Video preview" : "Image preview")
        .task(id: item.id) { await load() }
    }

    private func load() async {
        guard let previewURL = item.previewURL else { return }
        do {
            let data = try await InstagramDownloader.data(for: previewURL)
            guard let decoded = UIImage(data: data) else {
                failed = true
                return
            }
            image = decoded
        } catch {
            failed = true
        }
    }
}

#Preview {
    DownloaderView()
        .environment(AppSettings(defaults: UserDefaults(suiteName: "preview") ?? .standard))
}

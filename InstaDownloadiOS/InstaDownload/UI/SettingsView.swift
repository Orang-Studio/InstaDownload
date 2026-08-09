import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppSettings.self) private var settings
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var showFolderPicker = false
    @State private var folderError: String?

    var body: some View {

        @Bindable var settings = settings

        NavigationStack {
            Form {
                Section {
                    Picker("Save to", selection: $settings.destination) {
                        ForEach(SaveDestination.allCases) { destination in
                            Label(destination.label, systemImage: destination.symbolName)
                                .tag(destination)
                        }
                    }

                    if settings.destination == .files {
                        LabeledContent("Folder") {
                            Text(settings.folderDisplayName)
                                .foregroundStyle(settings.folderName == nil ? .secondary : .primary)
                        }

                        Button("Choose folder…") { showFolderPicker = true }

                        if settings.folderName != nil {
                            Button("Use photo library instead", role: .destructive) {
                                settings.clearFolder()
                            }
                        }
                    }
                } header: {
                    Text("Download location")
                } footer: {
                    Text(settings.destination.detail)
                }

                Section("Feedback") {
                    Toggle(isOn: $settings.hapticsEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Haptic feedback")
                            Text("Vibrate for download events")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section("Appearance") {
                    Picker("Theme", selection: $settings.theme) {
                        ForEach(AppTheme.allCases) { theme in
                            Label(theme.label, systemImage: theme.symbolName)
                                .tag(theme)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }

                Section("About") {
                    LabeledContent("Version", value: versionString)
                    Button {
                        openURL(URL(string: "https://github.com/Orang-Studio/InstaDownload")!)
                    } label: {
                        Label("Source on GitHub", systemImage: "arrow.up.right.square")
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder]
            ) { result in
                switch result {
                case .success(let folder):
                    do {
                        try settings.setFolder(folder)
                        folderError = nil
                    } catch {
                        folderError = error.localizedDescription
                    }
                case .failure(let error):
                    folderError = error.localizedDescription
                }
            }
            .alert(
                "Couldn't use that folder",
                isPresented: Binding(
                    get: { folderError != nil },
                    set: { if !$0 { folderError = nil } }
                )
            ) {
                Button("OK", role: .cancel) { folderError = nil }
            } message: {
                Text(folderError ?? "")
            }
        }
    }

    private var versionString: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "n/a"
        let build = info?["CFBundleVersion"] as? String ?? "n/a"
        return "\(short) (\(build))"
    }
}

#Preview {
    SettingsView()
        .environment(AppSettings(defaults: UserDefaults(suiteName: "preview") ?? .standard))
}

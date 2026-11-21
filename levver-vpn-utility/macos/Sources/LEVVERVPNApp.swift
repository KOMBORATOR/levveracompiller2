import SwiftUI
import AppKit

@main
struct LEVVERVPNApp: App {
    @StateObject private var model = VPNModel()

    var body: some Scene {
        WindowGroup {
            MainView()
                .environmentObject(model)
                .frame(minWidth: 760, minHeight: 560)
                .preferredColorScheme(.dark)
        }
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .newItem) { }
        }
    }
}

@MainActor
final class VPNModel: ObservableObject {
    @Published var profileName = "Новый профиль"
    @Published var configText = ""
    @Published var status = "Не подключено"
    @Published var detail = "Импортируйте sing-box JSON-конфигурацию"
    @Published var isRunning = false
    @Published var profileURL: URL?

    private var process: Process?

    private var supportDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("LEVVER VPN UTILITY", isDirectory: true)
    }

    func importConfig(from url: URL) {
        do {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  object["outbounds"] != nil else {
                throw NSError(domain: "LEVVER", code: 1, userInfo: [NSLocalizedDescriptionKey: "Ожидается полный sing-box JSON с массивом outbounds."])
            }
            configText = String(data: try JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]), encoding: .utf8) ?? ""
            profileName = url.deletingPathExtension().lastPathComponent
            detail = "Конфигурация загружена. Нажмите «Проверить» перед подключением."
            status = "Профиль импортирован"
        } catch {
            detail = error.localizedDescription
            status = "Ошибка импорта"
        }
    }

    func checkConfig() {
        do {
            let url = try writeConfig()
            let core = try coreURL()
            let check = Process()
            check.executableURL = core
            check.arguments = ["check", "-c", url.path]
            let output = Pipe()
            check.standardOutput = output
            check.standardError = output
            try check.run()
            check.waitUntilExit()
            let message = String(data: output.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
            if check.terminationStatus == 0 {
                status = "Конфигурация проверена"
                detail = "sing-box не обнаружил ошибок."
            } else {
                status = "Ошибка конфигурации"
                detail = message.isEmpty ? "sing-box завершился с кодом \(check.terminationStatus)." : message
            }
        } catch {
            status = "Проверка недоступна"
            detail = error.localizedDescription
        }
    }

    func connect() {
        guard !isRunning else { return }
        do {
            let url = try writeConfig()
            let core = try coreURL()
            let task = Process()
            task.executableURL = core
            task.arguments = ["run", "-c", url.path]
            let output = Pipe()
            task.standardOutput = output
            task.standardError = output
            task.terminationHandler = { [weak self] task in
                Task { @MainActor in
                    self?.isRunning = false
                    self?.process = nil
                    self?.status = task.terminationStatus == 0 ? "Отключено" : "Ядро остановлено"
                    self?.detail = "sing-box завершил работу (код \(task.terminationStatus))."
                }
            }
            try task.run()
            process = task
            isRunning = true
            status = "Ядро запущено"
            detail = "Проверяйте доступность VPN и системный маршрут. Для TUN могут потребоваться разрешения macOS."
        } catch {
            status = "Не удалось подключиться"
            detail = error.localizedDescription
        }
    }

    func disconnect() {
        process?.terminate()
        status = "Отключение…"
        detail = "Останавливаем sing-box."
    }

    private func writeConfig() throws -> URL {
        guard let data = configText.data(using: .utf8),
              var object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let outbounds = object["outbounds"] as? [[String: Any]],
              !outbounds.isEmpty else {
            throw NSError(domain: "LEVVER", code: 2, userInfo: [NSLocalizedDescriptionKey: "Сначала импортируйте корректный sing-box JSON."])
        }
        if object["inbounds"] == nil {
            object["inbounds"] = [[
                "type": "tun",
                "tag": "tun-in",
                "interface_name": "utun",
                "address": ["172.19.0.1/30"],
                "auto_route": true,
                "strict_route": true
            ]]
        }
        let dataOut = try JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys])
        try FileManager.default.createDirectory(at: supportDirectory, withIntermediateDirectories: true)
        let target = supportDirectory.appendingPathComponent("active-config.json")
        try dataOut.write(to: target, options: .atomic)
        profileURL = target
        return target
    }

    private func coreURL() throws -> URL {
        guard let url = Bundle.main.resourceURL?.appendingPathComponent("sing-box"),
              FileManager.default.isExecutableFile(atPath: url.path) else {
            throw NSError(domain: "LEVVER", code: 3, userInfo: [NSLocalizedDescriptionKey: "Не найден sing-box. Переустановите приложение из официальной сборки LEVVER."])
        }
        return url
    }
}

struct MainView: View {
    @EnvironmentObject private var model: VPNModel
    @State private var showImporter = false

    var body: some View {
        ZStack {
            Color(red: 0.035, green: 0.043, blue: 0.06).ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12).fill(Color(red: 0.38, green: 0.12, blue: 0.17))
                        Image(systemName: "shield.lefthalf.filled").font(.system(size: 22, weight: .bold)).foregroundStyle(.white)
                    }.frame(width: 44, height: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("LEVVER").font(.system(size: 19, weight: .black, design: .rounded)).tracking(2)
                        Text("VPN UTILITY  •  macOS").font(.system(size: 10, weight: .semibold)).tracking(1.8).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text("1.0.0 • PREVIEW").font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(Color(red: 0.82, green: 0.48, blue: 0.5))
                        .padding(.horizontal, 10).padding(.vertical, 6).background(.white.opacity(0.06), in: Capsule())
                }
                .padding(24)

                HStack(alignment: .top, spacing: 20) {
                    VStack(spacing: 16) {
                        ZStack {
                            Circle().stroke(.white.opacity(0.08), lineWidth: 1).frame(width: 190, height: 190)
                            Circle().stroke(Color(red: 0.45, green: 0.16, blue: 0.2).opacity(model.isRunning ? 0.9 : 0.35), lineWidth: 8).frame(width: 166, height: 166)
                            Circle().fill(Color(red: 0.14, green: 0.07, blue: 0.09)).frame(width: 145, height: 145)
                            VStack(spacing: 8) {
                                Image(systemName: model.isRunning ? "shield.lefthalf.filled" : "shield").font(.system(size: 42, weight: .light))
                                Text(model.isRunning ? "ACTIVE" : "READY").font(.system(size: 11, weight: .bold, design: .monospaced)).tracking(2)
                            }.foregroundStyle(model.isRunning ? Color.green : Color.white.opacity(0.8))
                        }
                        Text(model.status).font(.system(size: 18, weight: .semibold))
                        Text(model.detail).font(.system(size: 12)).foregroundStyle(.secondary).multilineTextAlignment(.center).lineLimit(4).frame(maxWidth: 250, minHeight: 48)
                        Button {
                            model.isRunning ? model.disconnect() : model.connect()
                        } label: {
                            HStack {
                                Image(systemName: model.isRunning ? "power" : "bolt.fill")
                                Text(model.isRunning ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬ")
                            }.font(.system(size: 12, weight: .bold)).tracking(1)
                                .frame(width: 220, height: 46)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color(red: 0.48, green: 0.13, blue: 0.19))
                        .disabled(model.configText.isEmpty)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(22)
                    .background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 20))

                    VStack(alignment: .leading, spacing: 14) {
                        Text("ПРОФИЛЬ").font(.system(size: 11, weight: .bold)).tracking(1.6).foregroundStyle(.secondary)
                        Text(model.profileName).font(.system(size: 17, weight: .semibold))
                        Text("Импортируйте полный sing-box JSON-файл. Конфигурация хранится локально на этом Mac.")
                            .font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        Button { showImporter = true } label: {
                            Label("Импортировать JSON", systemImage: "square.and.arrow.down")
                                .frame(maxWidth: .infinity, minHeight: 38)
                        }.buttonStyle(.bordered)
                        Button { model.checkConfig() } label: {
                            Label("Проверить конфигурацию", systemImage: "checkmark.seal")
                                .frame(maxWidth: .infinity, minHeight: 38)
                        }.buttonStyle(.bordered).disabled(model.configText.isEmpty || model.isRunning)
                        Divider().overlay(.white.opacity(0.08))
                        Label("Ядро sing-box 1.14.4", systemImage: "cpu").font(.system(size: 12, weight: .medium))
                        Label("TUN / utun", systemImage: "network").font(.system(size: 12, weight: .medium))
                        Label("Apple Silicon preview", systemImage: "apple.logo").font(.system(size: 12, weight: .medium))
                        Spacer(minLength: 0)
                    }
                    .padding(22)
                    .frame(width: 270, alignment: .leading)
                    .background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 20))
                }
                .padding(.horizontal, 24)

                Spacer()
                HStack {
                    Text("LEVVER VPN UTILITY").tracking(1)
                    Spacer()
                    Text("Нативный клиент macOS • локальные профили")
                }.font(.system(size: 10, weight: .medium)).foregroundStyle(.tertiary).padding(24)
            }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.json], allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first { model.importConfig(from: url) }
        }
    }
}

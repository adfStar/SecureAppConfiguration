import Foundation
import PklSwift
import PklVaultSwift

public enum PickelConfigRendererError: LocalizedError {
    case missingDescriptors
    case invalidIdentifier(String)
    case unsupportedConfigPath(String)
    case unableToReadVersionFile(URL)
    case missingVersion(URL)
    case pklEvaluationFailed(String)

    public var errorDescription: String? {
        switch self {
        case .missingDescriptors:
            return "At least one configuration descriptor is required."
        case let .invalidIdentifier(identifier):
            return "Invalid Pkl member identifier '\(identifier)'."
        case let .unsupportedConfigPath(configPath):
            return "Unsupported config path '\(configPath)'."
        case let .unableToReadVersionFile(url):
            return "Unable to read version file at \(url.path)."
        case let .missingVersion(url):
            return "No version heading found in \(url.path). Expected format: ## [123]"
        case let .pklEvaluationFailed(message):
            return message
        }
    }
}

struct ConfigurationDescriptor: Decodable {
    let name: String
    let vaultMount: String
    let configurations: [ConfigDescriptor]

    func configuration(for configPath: String) -> ConfigDescriptor? {
        configurations.first { $0.configPath == configPath }
    }
}

struct ConfigDescriptor: Decodable {
    let configPath: String
    let valuesFile: String
    let valuesKey: String
    let secureValuesFile: String
    let secureValuesKey: String
    let vaultPath: String
    let outputFile: String?
    let secureOutputFile: String?
    let versionFile: String?
}

public struct PickelConfigRenderer {
    private let sourceRoot: URL
    private let descriptorPaths: [String]

    public init(sourceRoot: URL, descriptorPaths: [String]) {
        self.sourceRoot = sourceRoot.standardizedFileURL
        self.descriptorPaths = descriptorPaths
    }

    public func render(configPath: String) async throws {
        guard !descriptorPaths.isEmpty else {
            throw PickelConfigRendererError.missingDescriptors
        }

        let (descriptor, configuration) = try await resolveDescriptor(configPath: configPath)

        try await renderNonSecretConfig(configuration)

        let versionURL = configuration.versionFile.map(resolvePath)
            ?? resolvePath(configuration.configPath)
                .deletingLastPathComponent()
                .appending(path: "CHANGELOG.md")
        let version = try Self.readVersion(from: versionURL)

        try await renderSecureConfig(
            descriptor: descriptor,
            configuration: configuration,
            version: version
        )
    }

    private func resolveDescriptor(configPath: String) async throws -> (ConfigurationDescriptor, ConfigDescriptor) {
        for descriptorPath in descriptorPaths {
            let descriptorURL = resolvePath(descriptorPath)
            let descriptor: ConfigurationDescriptor
            do {
                let jsonText = try await evaluateOutputText(
                    source: Self.descriptorRenderSource(descriptorURL)
                )
                descriptor = try JSONDecoder().decode(
                    ConfigurationDescriptor.self,
                    from: Data(jsonText.utf8)
                )
            } catch {
                throw PickelConfigRendererError.pklEvaluationFailed(Self.errorMessage(error))
            }

            if let configuration = descriptor.configuration(for: configPath) {
                return (descriptor, configuration)
            }
        }

        throw PickelConfigRendererError.unsupportedConfigPath(configPath)
    }

    private func renderNonSecretConfig(_ configuration: ConfigDescriptor) async throws {
        try Self.validateIdentifier(configuration.valuesKey)

        let valuesModuleURL = resolvePath(configuration.valuesFile)
        let outputURL = resolvePath(configuration.outputFile ?? "\(configuration.configPath).json")
        let renderSource = Self.renderSource(
            valuesModuleURL: valuesModuleURL,
            valuesKey: configuration.valuesKey
        )

        let jsonText = try await evaluateOutputText(source: renderSource)
        try Self.writeIfChanged(
            data: Data(jsonText.utf8),
            to: outputURL,
            unchangedMessage: "Config \(outputURL.path) is already up to date, nothing to do.",
            changedMessage: "Rendered config \(outputURL.path) from Pkl."
        )
    }

    private func renderSecureConfig(
        descriptor: ConfigurationDescriptor,
        configuration: ConfigDescriptor,
        version: Int
    ) async throws {
        try Self.validateIdentifier(configuration.secureValuesKey)

        let valuesModuleURL = resolvePath(configuration.secureValuesFile)
        let outputURL = resolvePath(
            configuration.secureOutputFile ?? "\(configuration.configPath)Secure.json"
        )
        let renderSource = Self.renderSource(
            valuesModuleURL: valuesModuleURL,
            valuesKey: configuration.secureValuesKey
        )

        let reader = try VaultResourceReader()
        defer { reader.close() }

        var options = EvaluatorOptions.preconfigured.withResourceReader(reader)
        var properties = options.properties ?? [:]
        properties["vaultMount"] = descriptor.vaultMount
        properties["vaultPath"] = configuration.vaultPath
        properties["vaultVersion"] = String(version)
        options.properties = properties

        let jsonText = try await evaluateOutputText(source: renderSource, options: options)
        try Self.writeIfChanged(
            data: Data(jsonText.utf8),
            to: outputURL,
            unchangedMessage: "Secret \(outputURL.path) is already up to date, nothing to do.",
            changedMessage: "Rendered secret \(outputURL.path) from Pkl and Vault."
        )
    }

    private func evaluateOutputText(
        source: String,
        options: EvaluatorOptions = .preconfigured
    ) async throws -> String {
        do {
            return try await withEvaluator(options: options) { evaluator in
                try await evaluator.evaluateOutputText(source: .text(source))
            }
        } catch let pklError as PklError {
            throw PickelConfigRendererError.pklEvaluationFailed(Self.concisePklMessage(pklError.message))
        } catch {
            throw PickelConfigRendererError.pklEvaluationFailed(Self.errorMessage(error))
        }
    }

    private func resolvePath(_ path: String) -> URL {
        if path.hasPrefix("/") {
            return URL(fileURLWithPath: path).standardizedFileURL
        }
        return sourceRoot.appending(path: path).standardizedFileURL
    }

    static func readVersion(from url: URL) throws -> Int {
        guard let content = try? String(contentsOf: url, encoding: .utf8) else {
            throw PickelConfigRendererError.unableToReadVersionFile(url)
        }

        let pattern = #"^## \[(\d+)\]"#
        let regex = try NSRegularExpression(pattern: pattern, options: [.anchorsMatchLines])
        let range = NSRange(content.startIndex..<content.endIndex, in: content)

        guard
            let match = regex.firstMatch(in: content, range: range),
            match.numberOfRanges == 2,
            let versionRange = Range(match.range(at: 1), in: content),
            let version = Int(content[versionRange])
        else {
            throw PickelConfigRendererError.missingVersion(url)
        }

        return version
    }

    static func validateIdentifier(_ identifier: String) throws {
        guard identifier.range(
            of: #"^[A-Za-z_][A-Za-z0-9_]*$"#,
            options: .regularExpression
        ) != nil else {
            throw PickelConfigRendererError.invalidIdentifier(identifier)
        }
    }

    private static func renderSource(valuesModuleURL: URL, valuesKey: String) -> String {
        #"""
        import "\#(valuesModuleURL.absoluteString)" as values

        output {
          renderer = new JsonRenderer {}
          value = values.\#(valuesKey)
        }
        """#
    }

    private static func descriptorRenderSource(_ descriptorURL: URL) -> String {
        #"""
        import "\#(descriptorURL.absoluteString)" as descriptor

        output {
          renderer = new JsonRenderer {}
          value = descriptor
        }
        """#
    }

    private static func writeIfChanged(
        data: Data,
        to outputURL: URL,
        unchangedMessage: String,
        changedMessage: String
    ) throws {
        try FileManager.default.createDirectory(
            at: outputURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        if let existing = try? Data(contentsOf: outputURL), existing == data {
            print(unchangedMessage)
            return
        }

        try data.write(to: outputURL, options: .atomic)
        print(changedMessage)
    }

    private static func concisePklMessage(_ message: String) -> String {
        let summary = message
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { line in
                !line.isEmpty
                    && !line.hasPrefix("–– Pkl Error ––")
                    && !line.hasPrefix("at ")
            }
        return summary ?? message.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func errorMessage(_ error: Error) -> String {
        if error is DecodingError {
            return String(describing: error)
        }
        return error.localizedDescription
    }
}

import Foundation
import PickelConfigRenderer

private enum CLIError: LocalizedError {
    case missingConfigPath
    case missingArgumentValue(String)
    case unknownArgument(String)

    var errorDescription: String? {
        switch self {
        case .missingConfigPath:
            return "Missing required --config-path argument."
        case let .missingArgumentValue(argument):
            return "Missing value for \(argument)."
        case let .unknownArgument(argument):
            return "Unknown argument: \(argument)"
        }
    }
}

private struct Arguments {
    let sourceRoot: URL
    let configPath: String
    let descriptorPaths: [String]

    static func parse(_ raw: [String]) throws -> Arguments {
        var sourceRoot = URL(
            fileURLWithPath: FileManager.default.currentDirectoryPath,
            isDirectory: true
        )
        var configPath: String?
        var descriptorPaths = [String]()
        var index = 0

        while index < raw.count {
            let argument = raw[index]
            switch argument {
            case "--help", "-h":
                printUsage()
                exit(0)
            case "--source-root", "-s":
                index += 1
                guard index < raw.count else {
                    throw CLIError.missingArgumentValue(argument)
                }
                sourceRoot = URL(fileURLWithPath: raw[index], isDirectory: true)
            case "--config-path", "-c":
                index += 1
                guard index < raw.count else {
                    throw CLIError.missingArgumentValue(argument)
                }
                configPath = raw[index]
            case "--descriptor", "-d":
                index += 1
                guard index < raw.count else {
                    throw CLIError.missingArgumentValue(argument)
                }
                descriptorPaths.append(raw[index])
            default:
                if argument.hasPrefix("-") {
                    throw CLIError.unknownArgument(argument)
                }
                configPath = argument
            }
            index += 1
        }

        guard let configPath, !configPath.isEmpty else {
            throw CLIError.missingConfigPath
        }

        return Arguments(
            sourceRoot: sourceRoot,
            configPath: configPath,
            descriptorPaths: descriptorPaths
        )
    }

    static func printUsage() {
        print(
            """
            Usage: pickel-config-renderer [options] [configPath]

              -s, --source-root <path>  Project root used for relative paths.
              -c, --config-path <path>  Configuration path to render.
              -d, --descriptor <path>   Pkl descriptor; repeat for multiple descriptors.
              -h, --help                Show this help.
            """
        )
    }
}

@main
private struct PickelConfigRendererMain {
    static func main() async {
        do {
            let arguments = try Arguments.parse(Array(CommandLine.arguments.dropFirst()))
            let renderer = PickelConfigRenderer(
                sourceRoot: arguments.sourceRoot,
                descriptorPaths: arguments.descriptorPaths
            )
            try await renderer.render(configPath: arguments.configPath)
        } catch let error as CLIError {
            fputs("error: \(error.localizedDescription)\n", stderr)
            Arguments.printUsage()
            exit(1)
        } catch {
            fputs("error: \(error.localizedDescription)\n", stderr)
            exit(1)
        }
    }
}

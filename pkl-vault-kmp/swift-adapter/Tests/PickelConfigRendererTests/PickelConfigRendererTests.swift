import Foundation
import XCTest
@testable import PickelConfigRenderer

final class PickelConfigRendererTests: XCTestCase {
    func testConfigurationDescriptorFindsMatchingConfiguration() {
        let expected = ConfigDescriptor(
            configPath: "App/Resources/Development/configDev",
            valuesFile: "Configuration/App.pkl",
            valuesKey: "development",
            secureValuesFile: "Configuration/App.pkl",
            secureValuesKey: "developmentSecure",
            vaultPath: "App/Resources/Development/configDev",
            outputFile: nil,
            secureOutputFile: nil,
            versionFile: nil
        )
        let descriptor = ConfigurationDescriptor(
            name: "App",
            vaultMount: "secret",
            configurations: [expected]
        )

        let result = descriptor.configuration(for: expected.configPath)

        XCTAssertEqual(result?.configPath, expected.configPath)
    }

    func testReadVersionUsesFirstVersionHeading() throws {
        let fileURL = FileManager.default.temporaryDirectory
            .appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: fileURL) }
        try """
        # Changelog

        ## [42]
        - Current

        ## [41]
        - Previous
        """.write(to: fileURL, atomically: true, encoding: .utf8)

        let version = try PickelConfigRenderer.readVersion(from: fileURL)

        XCTAssertEqual(version, 42)
    }

    func testDescriptorDecodesConfigurationList() throws {
        let data = Data(
            """
            {
              "name": "App",
              "vaultMount": "secret",
              "configurations": [
                {
                  "configPath": "App/Resources/Development/configDev",
                  "valuesFile": "Configuration/App.pkl",
                  "valuesKey": "development",
                  "secureValuesFile": "Configuration/App.pkl",
                  "secureValuesKey": "developmentSecure",
                  "vaultPath": "App/Resources/Development/configDev"
                }
              ]
            }
            """.utf8
        )

        let descriptor = try JSONDecoder().decode(ConfigurationDescriptor.self, from: data)

        XCTAssertEqual(descriptor.configurations.count, 1)
        XCTAssertNil(descriptor.configurations[0].outputFile)
    }

    func testValidateIdentifierRejectsMemberPaths() {
        XCTAssertThrowsError(
            try PickelConfigRenderer.validateIdentifier("development.secure")
        )
    }
}

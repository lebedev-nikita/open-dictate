import Foundation
import XCTest
@testable import OpenDictateCore

final class CoreResourcesTests: XCTestCase {
    func testInstalledApplicationLoadsResourcesWithoutTheBuildDirectory() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let app = root.appendingPathComponent("OpenDictate.app")
        let contents = app.appendingPathComponent("Contents")
        let resources = contents.appendingPathComponent("Resources").appendingPathComponent(CoreResources.bundleName)
        try FileManager.default.createDirectory(at: resources, withIntermediateDirectories: true)
        let info = ["CFBundleIdentifier": "com.opendictate.resource-fixture", "CFBundlePackageType": "APPL"]
        try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0)
            .write(to: contents.appendingPathComponent("Info.plist"))
        for name in CoreResources.requiredJSON {
            let source = try XCTUnwrap(CoreResources.url(forResource: name, withExtension: "json"))
            try FileManager.default.copyItem(at: source, to: resources.appendingPathComponent(name + ".json"))
        }
        let bundle = try XCTUnwrap(Bundle(url: app))
        for name in CoreResources.requiredJSON {
            let actual = try XCTUnwrap(CoreResources.url(forResource: name, withExtension: "json", in: bundle))
            XCTAssertTrue(actual.path.hasPrefix(resources.path))
            _ = try JSONSerialization.jsonObject(with: Data(contentsOf: actual))
        }
        XCTAssertNil(CoreResources.url(forResource: "missing-resource", withExtension: "json", in: bundle),
                     "Installed apps must never fall back to a developer's build directory")
    }
}

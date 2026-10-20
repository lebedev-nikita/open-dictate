import Foundation

public enum CoreResources {
    static let bundleName = "OpenDictate_OpenDictateCore.bundle"
    static let requiredJSON = ["postprocessing-rules", "postprocessing-languages", "scaled-integers"]

    static func packagedBundle(in application: Bundle) -> Bundle? {
        guard let resources = application.resourceURL else { return nil }
        return Bundle(url: resources.appendingPathComponent(bundleName))
    }

    static func url(forResource name: String, withExtension ext: String, in application: Bundle = .main) -> URL? {
        if application.bundleURL.pathExtension == "app" {
            return packagedBundle(in: application)?.url(forResource: name, withExtension: ext)
        }
        return Bundle.module.url(forResource: name, withExtension: ext)
    }

    /// Verifies the shipped app without preferences, credentials, recording or network access.
    public static func verify() throws {
        for name in requiredJSON {
            guard let url = url(forResource: name, withExtension: "json") else {
                throw CocoaError(.fileNoSuchFile)
            }
            _ = try JSONSerialization.jsonObject(with: Data(contentsOf: url))
        }
    }
}

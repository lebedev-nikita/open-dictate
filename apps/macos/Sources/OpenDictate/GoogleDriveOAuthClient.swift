import Foundation

/// Desktop client configuration belongs to the build, independently of a user's Google session.
struct GoogleDriveOAuthClient: Equatable {
    let clientID: String
    let clientSecret: String

    init?(clientID: String, clientSecret: String) {
        let clientID = clientID.trimmingCharacters(in: .whitespacesAndNewlines)
        let clientSecret = clientSecret.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clientID.hasSuffix(".apps.googleusercontent.com"), !clientID.contains(where: \.isWhitespace),
              !clientSecret.isEmpty else { return nil }
        self.clientID = clientID; self.clientSecret = clientSecret
    }

    init?(infoDictionary: [String: Any]) {
        guard let client = infoDictionary["OpenDictateGoogleDriveClient"] as? [String: String],
              let clientID = client["client_id"], let clientSecret = client["client_secret"] else { return nil }
        self.init(clientID: clientID, clientSecret: clientSecret)
    }

    static var bundled: GoogleDriveOAuthClient? {
        Bundle.main.infoDictionary.flatMap(GoogleDriveOAuthClient.init(infoDictionary:))
    }
}

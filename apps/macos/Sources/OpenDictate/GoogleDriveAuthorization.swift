import AppKit
import CryptoKit
import Network
import Security

struct DriveCredentials: Codable {
    let clientID: String
    let clientSecret: String
    let refreshToken: String
}

enum DriveCredentialStore {
    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.opendictate.mac",
         kSecAttrAccount as String: "google-drive-oauth", kSecAttrSynchronizable as String: false]
    }
    static func load() throws -> DriveCredentials? {
        var item = query; item[kSecReturnData as String] = true; item[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(item as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw DriveError.authorization }
        return try JSONDecoder().decode(DriveCredentials.self, from: data)
    }
    static func save(_ credentials: DriveCredentials) throws {
        let attributes: [String: Any] = [kSecValueData as String: try JSONEncoder().encode(credentials),
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = query; item.merge(attributes) { _, new in new }; status = SecItemAdd(item as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw DriveError.authorization }
    }
    static func delete() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw DriveError.authorization }
    }

    static func signOut(loadCredentials: () throws -> DriveCredentials? = load,
                        saveCredentials: (DriveCredentials) throws -> Void = save,
                        deleteCredentials: () throws -> Void = delete) throws {
        let credentials: DriveCredentials?
        do { credentials = try loadCredentials() }
        catch { try deleteCredentials(); return }
        guard let credentials,
              GoogleDriveOAuthClient(clientID: credentials.clientID, clientSecret: credentials.clientSecret) != nil else {
            try deleteCredentials(); return
        }
        // The client identifies the application; only the refresh token authorizes a user.
        do {
            try saveCredentials(DriveCredentials(clientID: credentials.clientID, clientSecret: credentials.clientSecret,
                                                 refreshToken: ""))
        } catch {
            // If preserving configuration fails, removing account access still takes priority.
            try deleteCredentials()
        }
    }
}

/// Desktop OAuth with PKCE, state validation and a temporary IPv4 loopback listener.
@MainActor
final class GoogleDriveAuthorization {
    static let scope = "https://www.googleapis.com/auth/drive.appdata"
    private var listener: NWListener?
    private var continuation: CheckedContinuation<(String, String), Error>?
    private var timeout: Task<Void, Never>?
    private var connections = [NWConnection]()
    private let queue = DispatchQueue(label: "com.opendictate.drive.oauth")
    private let openBrowser: (URL) -> Bool
    private let loadCredentials: () throws -> DriveCredentials?
    private let saveCredentials: (DriveCredentials) throws -> Void
    private let signInTimeoutNanoseconds: UInt64
    private let configuredClient: GoogleDriveOAuthClient?

    init(openBrowser: @escaping (URL) -> Bool = { NSWorkspace.shared.open($0) },
         loadCredentials: @escaping () throws -> DriveCredentials? = DriveCredentialStore.load,
         saveCredentials: @escaping (DriveCredentials) throws -> Void = DriveCredentialStore.save,
         signInTimeoutNanoseconds: UInt64 = 600_000_000_000,
         configuredClient: GoogleDriveOAuthClient? = .bundled) {
        self.openBrowser = openBrowser; self.loadCredentials = loadCredentials; self.saveCredentials = saveCredentials
        self.signInTimeoutNanoseconds = signInTimeoutNanoseconds
        self.configuredClient = configuredClient
    }

    func connect(client: GoogleDriveClient) async throws -> String {
        try Task.checkCancellation()
        let configuration: GoogleDriveOAuthClient
        if let configuredClient { configuration = configuredClient }
        else if let saved = try loadCredentials(),
                let savedClient = GoogleDriveOAuthClient(clientID: saved.clientID, clientSecret: saved.clientSecret) {
            configuration = savedClient
        } else { throw DriveError.clientConfiguration }
        return try await connect(clientID: configuration.clientID, clientSecret: configuration.clientSecret, client: client)
    }

    func connect(clientID: String, clientSecret: String, client: GoogleDriveClient) async throws -> String {
        guard clientID.hasSuffix(".apps.googleusercontent.com"), !clientID.contains(where: \.isWhitespace) else { throw DriveError.configuration }
        var clientSecret = clientSecret.trimmingCharacters(in: .whitespacesAndNewlines)
        if clientSecret.isEmpty, let saved = try loadCredentials(), saved.clientID == clientID {
            clientSecret = saved.clientSecret
        }
        guard !clientSecret.isEmpty else { throw DriveError.clientConfiguration }
        let verifier = try randomString(), state = try randomString()
        let challenge = base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        let (code, redirect) = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                listener.stateUpdateHandler = { [weak self] status in
                    Task { @MainActor in
                        guard let self, self.continuation != nil else { return }
                        switch status {
                        case .ready:
                            guard let port = listener.port else { self.finish(.failure(DriveError.network)); return }
                            let redirect = "http://127.0.0.1:\(port.rawValue)/"
                            var url = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
                            url.queryItems = ["client_id": clientID, "redirect_uri": redirect, "response_type": "code",
                                "scope": Self.scope, "state": state, "code_challenge": challenge, "code_challenge_method": "S256",
                                "access_type": "offline", "prompt": "consent select_account"].map { URLQueryItem(name: $0.key, value: $0.value) }
                            if !self.openBrowser(url.url!) { self.finish(.failure(DriveError.network)) }
                        case .failed: self.finish(.failure(DriveError.network))
                        default: break
                        }
                    }
                }
                listener.newConnectionHandler = { [weak self] connection in
                    Task { @MainActor in self?.accept(connection, expectedState: state) }
                }
                listener.start(queue: queue)
                timeout = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: self?.signInTimeoutNanoseconds ?? 0) }
                    catch { return }
                    self?.finish(.failure(DriveError.signInTimedOut))
                }
                if Task.isCancelled { finish(.failure(CancellationError())) }
            }
        } onCancel: { Task { @MainActor [weak self] in self?.cancel() } }
        try Task.checkCancellation()
        let result = try await tokenRequest(["client_id": clientID, "client_secret": clientSecret, "code": code,
            "code_verifier": verifier, "redirect_uri": redirect, "grant_type": "authorization_code"], client: client)
        guard let refresh = result["refresh_token"] as? String, let token = result["access_token"] as? String,
              !refresh.isEmpty, !token.isEmpty,
              (result["scope"] as? String)?.split(separator: " ").contains(Substring(Self.scope)) == true else { throw DriveError.authorization }
        try Task.checkCancellation()
        try saveCredentials(DriveCredentials(clientID: clientID, clientSecret: clientSecret, refreshToken: refresh))
        return token
    }

    func refresh(client: GoogleDriveClient) async throws -> String {
        guard let credentials = try loadCredentials(), !credentials.refreshToken.isEmpty else { throw DriveError.authorization }
        let result = try await tokenRequest(["client_id": credentials.clientID, "client_secret": credentials.clientSecret,
            "refresh_token": credentials.refreshToken, "grant_type": "refresh_token"], client: client)
        guard let token = result["access_token"] as? String, !token.isEmpty else { throw DriveError.authorization }
        return token
    }

    private func tokenRequest(_ fields: [String: String], client: GoogleDriveClient) async throws -> [String: Any] {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var body = URLComponents(); body.queryItems = fields.map { URLQueryItem(name: $0.key, value: $0.value) }
        request.httpBody = body.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B").data(using: .utf8)
        let data = try await client.send(request)
        guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw DriveError.authorization }
        return result
    }

    private func accept(_ connection: NWConnection, expectedState: String) {
        guard continuation != nil, connections.count < 10 else { connection.cancel(); return }
        connections.append(connection); connection.start(queue: queue)
        receive(connection, data: Data(), expectedState: expectedState)
    }
    private func receive(_ connection: NWConnection, data: Data, expectedState: String) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] chunk, _, complete, error in
            Task { @MainActor in
                guard let self, self.continuation != nil else { connection.cancel(); return }
                var combined = data; if let chunk { combined.append(chunk) }
                guard combined.count <= 8192, error == nil else { connection.cancel(); return }
                let text = String(decoding: combined, as: UTF8.self)
                if !text.contains("\r\n\r\n") {
                    if !complete { self.receive(connection, data: combined, expectedState: expectedState) }
                    else { connection.cancel() }
                    return
                }
                let first = text.components(separatedBy: "\r\n")[0].split(separator: " ")
                guard first.count == 3, first[0] == "GET", first[1].hasPrefix("/?"),
                      let url = URLComponents(string: "http://127.0.0.1\(first[1])"),
                      url.queryItems?.filter({ $0.name == "state" }).count == 1,
                      url.queryItems?.first(where: { $0.name == "state" })?.value == expectedState else {
                    connection.cancel(); return
                }
                let code = url.queryItems?.first(where: { $0.name == "code" })?.value
                let html = "<html><head><title>OpenDictate — Google Drive</title></head><body>You can close this window and return to OpenDictate.</body></html>"
                let response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: \(html.utf8.count)\r\n\r\n\(html)"
                let redirect = "http://127.0.0.1:\(self.listener?.port?.rawValue ?? 0)/"
                connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in
                    Task { @MainActor in
                        connection.cancel()
                        if let code, !code.isEmpty { self.finish(.success((code, redirect))) }
                        else { self.finish(.failure(DriveError.authorization)) }
                    }
                })
            }
        }
    }
    func cancel() { finish(.failure(CancellationError())) }
    private func finish(_ result: Result<(String, String), Error>) {
        let pending = continuation; continuation = nil
        listener?.cancel(); listener = nil; timeout?.cancel(); timeout = nil
        connections.forEach { $0.cancel() }; connections.removeAll()
        pending?.resume(with: result)
    }
    private func randomString() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw DriveError.authorization }
        return base64URL(Data(bytes))
    }
    private func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

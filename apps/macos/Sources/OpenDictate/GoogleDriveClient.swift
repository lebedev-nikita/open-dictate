import Foundation
import OpenDictateCore

enum DriveError: Error { case authorization, signInTimedOut, network, configuration, clientConfiguration, invalidData }

actor GoogleDriveClient {
    struct Remote {
        let document: SettingsSyncDocument
        let ownFileID: String?
        func needsUpload(_ merged: SettingsSyncDocument) -> Bool { ownFileID == nil || merged != document }
    }
    private struct CachedReplica { let version: String; let document: SettingsSyncDocument }
    private var replicas: [String: CachedReplica] = [:]
    private let session: URLSession
    init(session: URLSession? = nil) {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil; config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 90
        self.session = session ?? URLSession(configuration: config, delegate: NoDriveRedirects(), delegateQueue: nil)
    }

    func download(token: String, deviceID: String, forceRefresh: Bool = false) async throws -> Remote {
        if forceRefresh { replicas.removeAll() }
        var nextReplicas: [String: CachedReplica] = [:]
        var document = SettingsSyncDocument(), ownID: String?, page: String?
        var count = 0
        repeat {
            var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
            components.queryItems = [URLQueryItem(name: "spaces", value: "appDataFolder"),
                URLQueryItem(name: "q", value: "trashed = false and name contains 'opendictate-settings-v1-'"),
                URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,size,version)"), URLQueryItem(name: "pageSize", value: "100")]
            if let page { components.queryItems?.append(URLQueryItem(name: "pageToken", value: page)) }
            let data = try await send(URLRequest(url: components.url!), token: token)
            guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let files = root["files"] as? [[String: Any]] else { throw DriveError.invalidData }
            for file in files {
                guard let name = file["name"] as? String,
                      name.range(of: "^opendictate-settings-v1-[A-Za-z0-9-]+\\.json$", options: .regularExpression) != nil,
                      let id = file["id"] as? String else { continue }
                count += 1
                guard count <= 100, (Int(file["size"] as? String ?? "0") ?? Int.max) <= SettingsSyncDocument.maximumBytes else { throw DriveError.invalidData }
                var media = URLComponents(url: URL(string: "https://www.googleapis.com/drive/v3/files")!.appendingPathComponent(id), resolvingAgainstBaseURL: false)!
                media.queryItems = [URLQueryItem(name: "alt", value: "media")]
                let version = (file["version"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                let replica: SettingsSyncDocument
                if let version, let cached = replicas[id], cached.version == version {
                    replica = cached.document
                } else {
                    replica = try SettingsSyncDocument.decode(await send(URLRequest(url: media.url!), token: token))
                }
                document.merge(replica)
                if let version { nextReplicas[id] = CachedReplica(version: version, document: replica) }
                if name == fileName(deviceID), ownID == nil || id < ownID! { ownID = id }
            }
            page = (root["nextPageToken"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        } while page != nil
        replicas = nextReplicas
        return Remote(document: document, ownFileID: ownID)
    }

    func upload(token: String, deviceID: String, fileID: String?, document: SettingsSyncDocument) async throws {
        if let fileID { replicas.removeValue(forKey: fileID) }
        let data = try JSONEncoder().encode(document)
        guard data.count <= SettingsSyncDocument.maximumBytes else { throw DriveError.invalidData }
        var request: URLRequest
        if let fileID {
            var components = URLComponents(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files")!.appendingPathComponent(fileID), resolvingAgainstBaseURL: false)!
            components.queryItems = [URLQueryItem(name: "uploadType", value: "media")]
            request = URLRequest(url: components.url!); request.httpMethod = "PATCH"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = data
        } else {
            let boundary = "OpenDictate-\(UUID().uuidString)"
            request = URLRequest(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")!)
            request.httpMethod = "POST"
            request.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            let metadata = try JSONSerialization.data(withJSONObject: ["name": fileName(deviceID), "parents": ["appDataFolder"]])
            var body = Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8)
            body.append(metadata); body.append(Data("\r\n--\(boundary)\r\nContent-Type: application/json\r\n\r\n".utf8))
            body.append(data); body.append(Data("\r\n--\(boundary)--\r\n".utf8)); request.httpBody = body
        }
        _ = try await send(request, token: token)
    }

    private func fileName(_ deviceID: String) -> String { "opendictate-settings-v1-\(deviceID).json" }

    func send(_ original: URLRequest, token: String? = nil) async throws -> Data {
        var request = original
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw DriveError.network }
        var data = Data()
        for try await byte in bytes {
            guard data.count < SettingsSyncDocument.maximumBytes else { throw DriveError.invalidData }
            data.append(byte)
        }
        if !(200..<300).contains(http.statusCode), request.url?.host == "oauth2.googleapis.com" {
            // Interpret only known OAuth error codes. Never surface the provider's payload or description.
            let error = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            if ["invalid_client", "unauthorized_client", "invalid_request"].contains(error ?? "") {
                throw DriveError.clientConfiguration
            }
            if http.statusCode == 400 { throw DriveError.authorization }
        }
        if http.statusCode == 401 { throw DriveError.authorization }
        guard (200..<300).contains(http.statusCode) else { throw DriveError.network }
        return data
    }
}

private final class NoDriveRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

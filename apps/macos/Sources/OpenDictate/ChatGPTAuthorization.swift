import AppKit
import CryptoKit
import Network
import Security
import OpenDictateCore

@MainActor final class ChatGPTAuthorization {
    struct Result { let code: String; let client: String; let redirect: String; let verifier: String; let nonce: String }
    private var listener: NWListener?
    private var continuation: CheckedContinuation<Result, Error>?
    private var timeout: Task<Void, Never>?
    private var connections = [NWConnection]()
    private let queue = DispatchQueue(label: "com.opendictate.chatgpt.oauth")
    private let openBrowser: (URL) -> Bool
    init(openBrowser: @escaping (URL) -> Bool = { NSWorkspace.shared.open($0) }) { self.openBrowser = openBrowser }
    func authorize(host: String, client: String?, idToken: String?, consent: Bool = false) async throws -> Result {
        let verifier = try random(), state = try random(), nonce = try random()
        let challenge = ChatGPTProtocol.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters); self.listener = listener
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                listener.stateUpdateHandler = { [weak self] status in
                    Task { @MainActor in
                        guard let self, self.continuation != nil else { return }
                        switch status {
                        case .ready:
                            guard let port = listener.port else { self.finish(.failure(ChatGPTError.unavailable)); return }
                            let redirect = "http://127.0.0.1:\(port.rawValue)/auth/callback"
                            let url = ChatGPTProtocol.authorization(host: host, redirect: redirect, state: state, nonce: nonce,
                                challenge: challenge, client: client, idToken: idToken, consent: consent)
                            if !self.openBrowser(url) { self.finish(.failure(ChatGPTError.unavailable)) }
                        case .failed: self.finish(.failure(ChatGPTError.unavailable))
                        default: break
                        }
                    }
                }
                listener.newConnectionHandler = { [weak self] connection in
                    Task { @MainActor in
                        guard let self, self.continuation != nil, self.connections.count < 16 else { connection.cancel(); return }
                        self.connections.append(connection); connection.start(queue: self.queue)
                        self.receive(connection, data: Data(), state: state, client: client, verifier: verifier, nonce: nonce)
                    }
                }
                listener.start(queue: queue)
                timeout = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(600)) } catch { return }
                    self?.finish(.failure(ChatGPTError.unavailable))
                }
                if Task.isCancelled { cancel() }
            }
        } onCancel: { Task { @MainActor [weak self] in self?.cancel() } }
    }
    private func receive(_ connection: NWConnection, data: Data, state: String, client: String?, verifier: String, nonce: String) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] chunk, _, complete, error in
            Task { @MainActor in
                guard let self, self.continuation != nil else { connection.cancel(); return }
                var bytes = data; if let chunk { bytes.append(chunk) }
                guard bytes.count <= 8192, error == nil else { connection.cancel(); return }
                let text = String(decoding: bytes, as: UTF8.self)
                if !text.contains("\r\n\r\n") {
                    if !complete { self.receive(connection, data: bytes, state: state, client: client, verifier: verifier, nonce: nonce) }
                    else { connection.cancel() }; return
                }
                let first = text.components(separatedBy: "\r\n")[0].split(separator: " ")
                guard first.count == 3, first[0] == "GET",
                      let url = URLComponents(string: "http://127.0.0.1" + first[1]),
                      url.path == "/auth/callback", url.queryItems?.filter({ $0.name == "state" }).count == 1,
                      url.queryItems?.first(where: { $0.name == "state" })?.value == state else { connection.cancel(); return }
                let callback = Swift.Result { try ChatGPTProtocol.callback(String(first[1]), state: state, client: client) }
                let message = "Return to OpenDictate. You may close this tab."
                let response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: \(message.utf8.count)\r\n\r\n\(message)"
                let redirect = "http://127.0.0.1:\(self.listener?.port?.rawValue ?? 0)/auth/callback"
                connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in
                    Task { @MainActor in
                        connection.cancel()
                        self.finish(callback.map { Result(code: $0.0, client: $0.1, redirect: redirect, verifier: verifier, nonce: nonce) })
                    }
                })
            }
        }
    }
    func cancel() { finish(.failure(CancellationError())) }
    private func finish(_ result: Swift.Result<Result, Error>) {
        let pending = continuation; continuation = nil
        listener?.cancel(); listener = nil; timeout?.cancel(); timeout = nil
        connections.forEach { $0.cancel() }; connections.removeAll()
        pending?.resume(with: result)
    }
    private func random() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw ChatGPTError.unavailable }
        return ChatGPTProtocol.base64URL(Data(bytes))
    }
}

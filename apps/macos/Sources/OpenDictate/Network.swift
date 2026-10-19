import Foundation
import OpenDictateCore

enum DictationError: Error, LocalizedError {
    case missingKey, microphone, accessibility, noField, secureField, excluded
    case audio, tooLong, tooShort, queueFull, connection, timeout, invalidResponse
    case api(Int), keychain, storage, shortcut

    var errorDescription: String? {
        switch self {
        case .missingKey: return "Save your OpenAI API key in Settings."
        case .microphone: return "Allow microphone access in System Settings, then try again."
        case .accessibility: return "Allow OpenDictate in System Settings → Privacy & Security → Accessibility."
        case .noField: return "Place the cursor in an editable text field, then use the shortcut."
        case .secureField: return "Dictation is disabled in password fields."
        case .excluded: return "Dictation is disabled in this app. Change the excluded apps in Settings."
        case .audio: return "Could not record audio. Check your microphone and try again."
        case .tooLong: return "The recording reached its eight-minute limit. Start a new dictation."
        case .tooShort: return "The recording was too short. Speak for a moment before stopping."
        case .queueFull: return "The connection is too slow to stream audio. Try Accurate mode."
        case .connection: return "The connection to OpenAI was interrupted. Check your internet connection."
        case .timeout: return "OpenAI did not respond in time. Try again."
        case .invalidResponse: return "OpenAI returned an incomplete response. Try again."
        case .api(let code):
            if code == 401 { return "OpenAI rejected the API key. Update it in Settings." }
            if code == 429 { return "OpenAI's rate or usage limit was reached. Check your API billing and try later." }
            return "OpenAI returned error \(code). Check model access and try again."
        case .keychain: return "Could not access Keychain. Unlock your login keychain and try again."
        case .storage: return "Could not save local history. Check free disk space and folder permissions."
        case .shortcut: return "This shortcut is already in use. Choose another combination in Settings."
        }
    }

    func message(russian: Bool) -> String {
        guard russian else { return localizedDescription }
        switch self {
        case .missingKey: return "Сохраните ключ API OpenAI в настройках."
        case .microphone: return "Разрешите микрофон в системных настройках и повторите."
        case .accessibility: return "Разрешите OpenDictate в Системных настройках → Конфиденциальность и безопасность → Универсальный доступ."
        case .noField: return "Поставьте курсор в редактируемое поле и нажмите сочетание клавиш."
        case .secureField: return "Диктовка в полях паролей отключена."
        case .excluded: return "Диктовка в этом приложении отключена. Измените исключения в настройках."
        case .audio: return "Не удалось записать звук. Проверьте микрофон и повторите."
        case .tooLong: return "Запись достигла ограничения в восемь минут. Начните новую диктовку."
        case .tooShort: return "Запись слишком короткая. Говорите немного дольше перед остановкой."
        case .queueFull: return "Соединение слишком медленное для Live. Попробуйте режим Accurate."
        case .connection: return "Соединение с OpenAI прервано. Проверьте интернет."
        case .timeout: return "OpenAI не ответил вовремя. Попробуйте ещё раз."
        case .invalidResponse: return "OpenAI вернул неполный ответ. Попробуйте ещё раз."
        case .api(let code):
            if code == 401 { return "OpenAI отклонил ключ API. Обновите его в настройках." }
            if code == 429 { return "Достигнут лимит OpenAI. Проверьте баланс API и повторите позже." }
            return "OpenAI вернул ошибку \(code). Проверьте доступ к модели и повторите."
        case .keychain: return "Не удалось открыть Keychain. Разблокируйте связку ключей и повторите."
        case .storage: return "Не удалось сохранить историю. Проверьте свободное место и права папки."
        case .shortcut: return "Сочетание клавиш занято. Выберите другое в настройках."
        }
    }
}

actor OpenAIClient {
    private let session: URLSession
    init(session: URLSession? = nil) {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 45
        config.timeoutIntervalForResource = 180
        self.session = session ?? URLSession(configuration: config)
    }

    func transcribe(pcm: Data, key: String, context: TranscriptionContext, timeout: Int) async throws -> String {
        let boundary = "OpenDictate-\(UUID().uuidString)"
        var request = authorized(OpenAIRequest.transcriptionURL, key: key, timeout: timeout)
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = OpenAIRequest.multipart(wav: WAV.encode(pcm: pcm), context: context, boundary: boundary)
        let result = try await send(request)
        guard let text = result["text"] as? String else { throw DictationError.invalidResponse }
        return text
    }

    func transform(source: String, instruction: String, key: String, model: String = TextProcessingModel.id, chatGPTAccount: String? = nil) async throws -> (String, String?) {
        let response = try await response(OpenAIRequest.transform(source: source, instruction: instruction, model: model), key: key, chatGPTAccount: chatGPTAccount)
        guard let data = OpenAIRequest.responseText(response).data(using: .utf8),
              let result = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = result["transformed_text"] as? String else { throw DictationError.invalidResponse }
        return (text, (result["message"] as? String).map { String($0.prefix(1000)) })
    }

    func postProcess(source: String, key: String, settings: PostProcessingSettings, languages: [String], model: String = TextProcessingModel.id, chatGPTAccount: String? = nil) async throws -> PostProcessingResult {
        try await PostProcessing.apply(source: source, settings: settings) {
            let response = try await self.response(OpenAIRequest.postProcessing(source: source, settings: settings, languages: languages, model: model), key: key, chatGPTAccount: chatGPTAccount)
            guard let data = OpenAIRequest.responseText(response).data(using: .utf8),
                  let result = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let text = result["transformed_text"] as? String else { throw DictationError.invalidResponse }
            return text
        }
    }

    func search(query: String, entries: [HistoryEntry], key: String) async throws -> [UUID] {
        var matches = [UUID]()
        let allowed = Set(entries.map(\.id))
        for batch in OpenAIRequest.historyBatches(entries) {
            try Task.checkCancellation()
            let result = try await response(OpenAIRequest.search(query: query, entries: batch), key: key)
            guard let data = OpenAIRequest.responseText(result).data(using: .utf8),
                  let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let ids = object["match_ids"] as? [String] else { throw DictationError.invalidResponse }
            for id in ids.compactMap(UUID.init(uuidString:)) where allowed.contains(id) && !matches.contains(id) {
                matches.append(id)
            }
        }
        return matches
    }

    private func response(_ body: [String: Any], key: String, timeout: Int = 30, chatGPTAccount: String? = nil) async throws -> [String: Any] {
        if let chatGPTAccount { return try await ChatGPTSession.shared.response(account: chatGPTAccount, body: body) }
        var request = authorized(OpenAIRequest.responsesURL, key: key, timeout: timeout)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    private func authorized(_ url: URL, key: String, timeout: Int) -> URLRequest {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: Double(timeout))
        request.httpMethod = "POST"
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("OpenDictate-macOS", forHTTPHeaderField: "User-Agent")
        return request
    }

    private func send(_ request: URLRequest) async throws -> [String: Any] {
        do {
            let (data, response) = try await session.data(for: request)
            guard let response = response as? HTTPURLResponse else { throw DictationError.invalidResponse }
            guard (200..<300).contains(response.statusCode) else { throw DictationError.api(response.statusCode) }
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw DictationError.invalidResponse
            }
            return object
        } catch let error as DictationError { throw error }
        catch is CancellationError { throw CancellationError() }
        catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            throw error.code == .timedOut ? DictationError.timeout : DictationError.connection
        } catch { throw DictationError.invalidResponse }
    }
}

protocol LiveSocket: AnyObject, Sendable {
    func resume()
    func send(_ message: URLSessionWebSocketTask.Message) async throws
    func receive() async throws -> URLSessionWebSocketTask.Message
    func close()
    var responseStatus: Int? { get }
}

private final class URLSessionLiveSocket: LiveSocket, @unchecked Sendable {
    private let session: URLSession
    private let task: URLSessionWebSocketTask
    init(request: URLRequest) {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil; config.httpCookieStorage = nil
        config.timeoutIntervalForRequest = 12
        config.timeoutIntervalForResource = 600
        session = URLSession(configuration: config)
        task = session.webSocketTask(with: request)
    }
    var responseStatus: Int? { (task.response as? HTTPURLResponse)?.statusCode }
    func resume() { task.resume() }
    func send(_ message: URLSessionWebSocketTask.Message) async throws { try await task.send(message) }
    func receive() async throws -> URLSessionWebSocketTask.Message { try await task.receive() }
    func close() { task.cancel(with: .normalClosure, reason: nil); session.invalidateAndCancel() }
}

/// Serial send pump preserves session.update → append* → commit even when send suspends.
actor LiveTranscriptionSession {
    private let socket: any LiveSocket
    private let context: TranscriptionContext
    private let partial: @Sendable (String) -> Void
    private var receiver: Task<Void, Never>?
    private var writer: Task<Void, Never>?
    private var pending = [Data]()
    private var outbox = [String]()
    private var configured = false
    private var started = false
    private var closed = false
    private var committed = false
    private var failure: DictationError?
    private var transcript = RealtimeTranscript()

    init(key: String, context: TranscriptionContext, partial: @escaping @Sendable (String) -> Void) {
        var request = URLRequest(url: OpenAIRequest.realtimeURL)
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("OpenDictate-macOS", forHTTPHeaderField: "User-Agent")
        socket = URLSessionLiveSocket(request: request)
        self.context = context; self.partial = partial
    }

    init(socket: any LiveSocket, context: TranscriptionContext = .init(), partial: @escaping @Sendable (String) -> Void) {
        self.socket = socket; self.context = context; self.partial = partial
    }

    func start() throws {
        guard !closed, !started else { return }
        started = true
        socket.resume()
        try enqueue(OpenAIRequest.sessionUpdate(context: context))
        receiver = Task { await receiveLoop() }
    }

    func append(_ data: Data) throws {
        if let failure { throw failure }
        guard !closed, !committed else { return }
        guard pending.count + outbox.count < AudioConstants.maximumQueuedChunks else {
            fail(.queueFull); throw DictationError.queueFull
        }
        if configured { try enqueueAudio(data) } else { pending.append(data) }
    }

    func finish(timeout: Int) async throws -> String {
        let readyDeadline = Date().addingTimeInterval(12)
        while !configured {
            try check(deadline: readyDeadline)
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        try check(deadline: readyDeadline)
        committed = true
        try enqueue(["type": "input_audio_buffer.commit"])
        let deadline = Date().addingTimeInterval(Double(timeout))
        while !transcript.completed {
            try check(deadline: deadline)
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        let result = transcript.text
        close()
        return result
    }

    func close() {
        guard !closed else { return }
        closed = true
        receiver?.cancel(); receiver = nil
        writer?.cancel(); writer = nil
        pending.removeAll(); outbox.removeAll()
        socket.close()
    }

    private func check(deadline: Date) throws {
        try Task.checkCancellation()
        if let failure { throw failure }
        if closed { throw CancellationError() }
        if Date() >= deadline { fail(.timeout); throw DictationError.timeout }
    }

    private func enqueueAudio(_ data: Data) throws {
        try enqueue(["type": "input_audio_buffer.append", "audio": data.base64EncodedString()])
    }

    private func enqueue(_ event: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: event)
        outbox.append(String(decoding: data, as: UTF8.self))
        if writer == nil { writer = Task { await sendLoop() } }
    }

    private func sendLoop() async {
        while !outbox.isEmpty && !closed {
            let message = outbox.removeFirst()
            do { try await socket.send(.string(message)) }
            catch { if !closed { fail(.connection) }; break }
        }
        writer = nil
    }

    private func receiveLoop() async {
        do {
            while !closed {
                let message = try await socket.receive()
                let data: Data
                switch message {
                case .string(let string): data = Data(string.utf8)
                case .data(let bytes): data = bytes
                @unknown default: continue
                }
                guard let event = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
                switch event["type"] as? String {
                case "session.updated":
                    guard !configured else { continue }
                    configured = true
                    let audio = pending; pending.removeAll()
                    for chunk in audio { try enqueueAudio(chunk) }
                case "error", "conversation.item.input_audio_transcription.failed":
                    // Raw provider errors can include request contents; never display or log them.
                    fail(.invalidResponse)
                default:
                    if let text = transcript.receive(event) { partial(text) }
                }
            }
        } catch {
            if !closed {
                let code = socket.responseStatus
                fail(code.map(DictationError.api) ?? .connection)
            }
        }
    }

    private func fail(_ error: DictationError) {
        if failure == nil { failure = error }
        close()
    }
}

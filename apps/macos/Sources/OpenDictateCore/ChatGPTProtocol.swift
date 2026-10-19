import Foundation
import Security

public enum ChatGPTError: Error { case unavailable }
public enum ChatGPTProtocol {
    public static let issuer = "https://auth.openai.com"
    public static let resource = "https://api.openai.com/v1"
    public static let tokenURL = URL(string: issuer + "/api/accounts/oauth/token")!
    public static let scopes = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    public static func form(_ values: [String: String]) -> Data {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        return Data(values.sorted { $0.key < $1.key }.map {
            $0.key.addingPercentEncoding(withAllowedCharacters: allowed)! + "=" + $0.value.addingPercentEncoding(withAllowedCharacters: allowed)!
        }.joined(separator: "&").utf8)
    }
    public static func authorization(host: String, redirect: String, state: String, nonce: String, challenge: String,
                                     client: String? = nil, idToken: String? = nil, consent: Bool = false) -> URL {
        var values = ["client_id": client ?? "dynamic_agent_client", "ext_agent_host_id": host, "redirect_uri": redirect,
                      "response_type": "code", "scope": scopes, "resource": resource, "state": state, "nonce": nonce,
                      "code_challenge_method": "S256", "code_challenge": challenge]
        if client == nil { values["agent_name_hint"] = "OpenDictate" }
        if let idToken { values["id_token_hint"] = idToken }
        if consent { values["prompt"] = "consent" }
        return URL(string: issuer + "/api/accounts/authorize?" + String(decoding: form(values), as: UTF8.self))!
    }
    public static func callback(_ target: String, state: String, client: String?) throws -> (String, String) {
        guard let url = URLComponents(string: "http://127.0.0.1" + target), url.path == "/auth/callback",
              let items = url.queryItems, Set(items.map(\.name)).count == items.count,
              items.first(where: { $0.name == "state" })?.value == state,
              !items.contains(where: { $0.name == "error" }),
              let issued = items.first(where: { $0.name == "client_id" })?.value ?? client,
              issued.hasPrefix("oaiapp_"), client == nil || client == issued,
              let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else { throw ChatGPTError.unavailable }
        return (code, issued)
    }
    public static func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    private static func decode(_ string: String) throws -> Data {
        let base = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        guard let data = Data(base64Encoded: base + String(repeating: "=", count: (4 - base.count % 4) % 4)) else { throw ChatGPTError.unavailable }
        return data
    }
    private static func object(_ data: Data) throws -> [String: Any] {
        guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ChatGPTError.unavailable }; return value
    }
    public static func identity(_ jwt: String, jwks: [String: Any], client: String, nonce: String, now: TimeInterval) throws -> [String: Any] {
        let parts = jwt.components(separatedBy: ".")
        guard parts.count == 3, jwt.utf8.count < 32768 else { throw ChatGPTError.unavailable }
        let header = try object(decode(parts[0]))
        guard header["alg"] as? String == "RS256", let kid = header["kid"] as? String,
              let keys = jwks["keys"] as? [[String: Any]],
              let jwk = keys.first(where: { $0["kid"] as? String == kid && $0["kty"] as? String == "RSA" }),
              let modulus = jwk["n"] as? String, let exponent = jwk["e"] as? String else { throw ChatGPTError.unavailable }
        func der(_ tag: UInt8, _ bytes: Data) -> Data {
            let count = bytes.count
            let length: [UInt8] = count < 128 ? [UInt8(count)] : (count < 256 ? [0x81, UInt8(count)] : [0x82, UInt8(count >> 8), UInt8(count & 255)])
            return Data([tag] + length) + bytes
        }
        func integer(_ bytes: Data) -> Data { der(2, bytes.first.map { $0 >= 128 } == true ? Data([0]) + bytes : bytes) }
        let encoded = try der(0x30, integer(decode(modulus)) + integer(decode(exponent)))
        let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA, kSecAttrKeyClass as String: kSecAttrKeyClassPublic]
        guard let key = SecKeyCreateWithData(encoded as CFData, attributes as CFDictionary, nil),
              try SecKeyVerifySignature(key, .rsaSignatureMessagePKCS1v15SHA256,
                  Data((parts[0] + "." + parts[1]).utf8) as CFData, decode(parts[2]) as CFData, nil) else { throw ChatGPTError.unavailable }
        let claims = try object(decode(parts[1]))
        let audiences = claims["aud"] as? [String]
        // A one-element audience array does not require an authorized-party claim.
        let audienceMatches = claims["aud"] as? String == client ||
            (audiences?.contains(client) == true && (audiences?.count == 1 || claims["azp"] as? String == client))
        let authorizedPartyMatches = claims["azp"] == nil || claims["azp"] as? String == client
        guard audienceMatches, authorizedPartyMatches, claims["iss"] as? String == issuer, claims["nonce"] as? String == nonce,
              let expiry = claims["exp"] as? Double, expiry > now,
              let subject = claims["sub"] as? String, !subject.isEmpty else { throw ChatGPTError.unavailable }
        return claims
    }
    public static func planRequest(_ body: [String: Any]) -> [String: Any] {
        var result = body; result["store"] = false; result["stream"] = true
        if let text = result["input"] as? String { result["input"] = [["role": "user", "content": text]] }
        return result
    }
    public static func completedEvent(_ data: String) throws -> [String: Any]? {
        if data == "[DONE]" { return nil }
        let event = try object(Data(data.utf8))
        switch event["type"] as? String {
        case "response.completed":
            guard let response = event["response"] as? [String: Any], response["status"] as? String == "completed" else { throw ChatGPTError.unavailable }
            return response
        case "response.failed", "response.incomplete", "error": throw ChatGPTError.unavailable
        default: return nil
        }
    }
}

/// Plan streams may omit the final output snapshot. Completed items remain gated by terminal success.
public struct ChatGPTResponseAccumulator {
    private var items: [Int: [String: Any]] = [:]
    private var completed: [String: Any]?
    private var failed = false
    public init() {}

    public mutating func consume(_ data: String) throws {
        do {
            guard !failed else { throw ChatGPTError.unavailable }
            if data == "[DONE]" { return }
            guard let event = try JSONSerialization.jsonObject(with: Data(data.utf8)) as? [String: Any] else { throw ChatGPTError.unavailable }
            switch event["type"] as? String {
            case "response.output_item.done":
                guard let number = event["output_index"] as? NSNumber,
                      CFGetTypeID(number) != CFBooleanGetTypeID(),
                      (0...1023).contains(number.intValue), number.doubleValue == Double(number.intValue),
                      let item = event["item"] as? [String: Any] else { throw ChatGPTError.unavailable }
                items[number.intValue] = item
            case "response.completed":
                guard var response = try ChatGPTProtocol.completedEvent(data) else { throw ChatGPTError.unavailable }
                let value = response["output"]
                guard value == nil || value is NSNull || value is [[String: Any]] else { throw ChatGPTError.unavailable }
                if (value as? [[String: Any]])?.isEmpty != false, !items.isEmpty {
                    response["output"] = items.keys.sorted().compactMap { items[$0] }
                }
                completed = response
            case "response.failed", "response.incomplete", "error": throw ChatGPTError.unavailable
            default: break
            }
        } catch {
            failed = true; completed = nil
            throw error
        }
    }

    public func result() throws -> [String: Any] {
        guard !failed, let completed else { throw ChatGPTError.unavailable }
        return completed
    }
}

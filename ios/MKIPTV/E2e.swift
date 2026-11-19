import CryptoKit
import Foundation

/// End-to-end encryption for synced secrets (playlist passwords). Same format as the Android app (E2e.kt):
/// P-256 device keys (public = base64 SubjectPublicKeyInfo DER), one 32-byte library key wrapped per device with
/// ephemeral ECDH -> HKDF-SHA256 -> AES-256-GCM, secrets sealed as base64(nonce || ciphertext || tag).
enum E2e {
    static let salt = Data("MKIPTV-keywrap-v1".utf8)
    static let info = Data("space-key".utf8)
    struct Failure: Error {}

    static func pubB64(_ k: P256.KeyAgreement.PrivateKey) -> String { k.publicKey.derRepresentation.base64EncodedString() }

    static func newSpaceKey() -> Data { SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) } }

    static func seal(_ plain: Data, _ key: Data) throws -> String {
        guard let c = try AES.GCM.seal(plain, using: SymmetricKey(data: key)).combined else { throw Failure() }
        return c.base64EncodedString()
    }

    static func open(_ box: String, _ key: Data) throws -> Data {
        guard let d = Data(base64Encoded: box) else { throw Failure() }
        return try AES.GCM.open(AES.GCM.SealedBox(combined: d), using: SymmetricKey(data: key))
    }

    private static func kek(_ priv: P256.KeyAgreement.PrivateKey, _ pubB64: String) throws -> Data {
        guard let d = Data(base64Encoded: pubB64) else { throw Failure() }
        let shared = try priv.sharedSecretFromKeyAgreement(with: P256.KeyAgreement.PublicKey(derRepresentation: d))
        return shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt, sharedInfo: info, outputByteCount: 32).withUnsafeBytes { Data($0) }
    }

    static func wrap(_ spaceKey: Data, recipientPub: String) throws -> [String: String] {
        let eph = P256.KeyAgreement.PrivateKey()
        return ["eph": pubB64(eph), "box": try seal(spaceKey, kek(eph, recipientPub))]
    }

    static func unwrap(eph: String, box: String, priv: P256.KeyAgreement.PrivateKey) throws -> Data {
        try open(box, kek(priv, eph))
    }
}

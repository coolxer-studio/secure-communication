import Foundation

actor PersistentSequenceStore {
    private let defaults: UserDefaults
    private let namespace: String

    init(
        defaults: UserDefaults = .standard,
        namespace: String = "com.coolxer.securecommunication.v2"
    ) {
        self.defaults = defaults
        self.namespace = namespace
    }

    func next(sessionID: String) throws -> UInt64 {
        let key = "\(namespace).sequence.\(sessionID)"
        let current = UInt64(defaults.object(forKey: key) as? Int64 ?? 0)
        guard current < 9_007_199_254_740_991 else {
            throw SecureError.sequenceExhausted
        }
        let next = current + 1
        defaults.set(Int64(next), forKey: key)
        // UserDefaults writes are serialized by the actor. A host with
        // multi-process access must inject a process-safe sequence authority.
        return next
    }
}

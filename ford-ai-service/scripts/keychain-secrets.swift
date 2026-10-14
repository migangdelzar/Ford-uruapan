import Foundation
import Security

func fail(_ message: String, _ status: OSStatus = errSecParam) -> Never {
    FileHandle.standardError.write(Data("Keychain operation failed: \(message) (\(status)).\n".utf8))
    exit(1)
}

let arguments = Array(CommandLine.arguments.dropFirst())
guard arguments.count >= 2 else {
    fail("usage: keychain-secrets.swift get SERVICE ACCOUNT... | store SERVICE")
}

let operation = arguments[0]
let service = arguments[1]
let input = FileHandle.standardInput.readDataToEndOfFile()

func baseQuery(account: String) -> [String: Any] {
    [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: account
    ]
}

func existingValue(account: String) -> (OSStatus, Data?) {
    var query = baseQuery(account: account)
    query[kSecReturnData as String] = true
    query[kSecMatchLimit as String] = kSecMatchLimitOne

    var result: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    return (status, result as? Data)
}

switch operation {
case "get":
    let accounts = Array(arguments.dropFirst(2))
    var values: [String: String] = [:]
    for account in accounts {
        let (status, data) = existingValue(account: account)
        if status == errSecItemNotFound {
            continue
        }
        guard status == errSecSuccess, let data,
              let value = String(data: data, encoding: .utf8) else {
            fail("read", status)
        }
        values[account] = value
    }
    let output = try JSONSerialization.data(withJSONObject: values, options: [.sortedKeys])
    FileHandle.standardOutput.write(output)

case "store":
    guard let values = try? JSONSerialization.jsonObject(with: input) as? [String: String] else {
        fail("invalid stdin JSON")
    }

    var added: [String] = []
    var alreadyCurrent: [String] = []
    var conflicts: [String] = []

    for (account, value) in values.sorted(by: { $0.key < $1.key }) {
        let data = Data(value.utf8)
        let (status, current) = existingValue(account: account)
        if status == errSecSuccess {
            if current == data {
                alreadyCurrent.append(account)
            } else {
                conflicts.append(account)
            }
            continue
        }
        guard status == errSecItemNotFound else {
            fail("check existing item", status)
        }

        var item = baseQuery(account: account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let addStatus = SecItemAdd(item as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            fail("store item", addStatus)
        }
        added.append(account)
    }

    let summary: [String: Any] = [
        "added": added.sorted(),
        "already_current": alreadyCurrent.sorted(),
        "conflicts": conflicts.sorted()
    ]
    let output = try JSONSerialization.data(withJSONObject: summary, options: [.sortedKeys])
    FileHandle.standardOutput.write(output)
    if !conflicts.isEmpty {
        exit(2)
    }

default:
    fail("unsupported operation")
}

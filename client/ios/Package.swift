// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SecureCommunication",
    platforms: [.iOS(.v13), .macOS(.v11)],
    products: [
        .library(name: "SecureCommunication", targets: ["SecureCommunication"])
    ],
    targets: [
        .target(
            name: "SecureCommunication",
            path: "Sources/SecureCommunication"
        ),
        .testTarget(
            name: "SecureCommunicationTests",
            dependencies: ["SecureCommunication"],
            path: "Tests/SecureCommunicationTests"
        )
    ]
)

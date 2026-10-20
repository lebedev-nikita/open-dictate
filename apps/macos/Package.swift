// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "OpenDictate",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "OpenDictate", targets: ["OpenDictate"])
    ],
    targets: [
        .target(name: "OpenDictateCore", resources: [.process("Resources")]),
        .executableTarget(name: "OpenDictate", dependencies: ["OpenDictateCore"],
                          linkerSettings: [.linkedFramework("Carbon")]),
        .testTarget(name: "OpenDictateCoreTests", dependencies: ["OpenDictateCore"]),
        .testTarget(name: "OpenDictateTests", dependencies: ["OpenDictate", "OpenDictateCore"])
    ]
)

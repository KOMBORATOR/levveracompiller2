// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LEVVERVPN",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "LEVVER VPN UTILITY", targets: ["LEVVERVPN"])],
    targets: [
        .executableTarget(name: "LEVVERVPN", path: "Sources")
    ]
)

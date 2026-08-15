// swift-tools-version: 5.9
// SPDX-License-Identifier: Apache-2.0
import PackageDescription

let package = Package(
    name: "QRlessPay",
    platforms: [.iOS(.v14), .macOS(.v12)],
    products: [
        .library(name: "QRlessPay", targets: ["QRlessPay"]),
    ],
    targets: [
        // No external dependencies on purpose: Ed25519 and SHA-256 come from CryptoKit,
        // and the CBOR profile this protocol uses is small enough to own outright. A bank
        // adopting the payer role should not inherit a dependency tree to do it.
        .target(name: "QRlessPay"),
        .testTarget(
            name: "QRlessPayTests",
            dependencies: ["QRlessPay"],
            resources: [.copy("vectors.json"), .copy("negative-vectors.json"), .copy("uwb-vectors.json")]
        ),
    ]
)

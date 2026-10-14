# Android Remote Control

Modern native Android application built with Kotlin and Jetpack Compose for peer-to-peer device administration, screen sharing, touch/gesture injection, audio streaming, and hardware control.

## Overview

The application enables secure pairing and remote control between two Android devices across any standard internet connection (Wi-Fi, LTE, 5G) worldwide:
- **Admin Device (Controller)**: Acts as the remote control, displaying the target device's screen, injecting touch gestures, typing via virtual keyboard, adjusting hardware volume/brightness, and streaming audio.
- **Remote Device (Target)**: Acts as the controlled client, hosting accessibility actions (Back, Home, Recents, Notifications), IME text injection, MediaProjection screen streaming, and hardware telemetry.

## Features

- **Worldwide Cloud Signaling**: Serverless WebRTC signaling using MongoDB Atlas Data API / HTTPS. No self-hosted VPS, port forwarding, or custom server configuration required.
- **Instant QR-Code Pairing**: The target device generates a high-contrast QR code on its screen. The admin device scans it via the integrated CameraX viewfinder for instant zero-touch pairing.
- **Manual PIN Fallback**: 6-digit cryptographic one-time PIN remains available alongside the QR code.
- **Live Screen Sharing**: High-performance WebRTC video streaming utilizing Android's `MediaProjection` API and `ScreenCapturerAndroid`.
- **Interactive Gestures & Remote Navigation**: Direct screen tap and swipe dispatch through Android `AccessibilityService`, plus system keys (Back, Home, Recents, Notifications).
- **Virtual Remote Keyboard**: Real-time keystroke and text commitment through a dedicated `InputMethodService` (IME).
- **Hardware Telemetry & Control**: Remote flashlight toggling, volume adjustment, display brightness control, vibration, and battery status reporting.
- **Binary PCM Audio Streaming**: Low-latency 44.1 kHz stereo audio streaming with jitter buffer management and backpressure flow control.
- **Cryptographic Security**: Ephemeral P-256 ECDH key agreement with HKDF-derived keys, short authentication string (SAS) validation, and cryptographic handshake protection.
- **Global STUN Traversal**: Automatic NAT traversal via Google Public STUN (`stun:stun.l.google.com:19302`) for direct P2P connections anywhere in the world.

## Technical Architecture

- **UI Framework**: Jetpack Compose with Material Design 3.
- **Barcode & Vision**: ZXing `QRCodeWriter` and CameraX `ImageAnalysis`.
- **Signaling**: `MongoSignalingClient` via MongoDB Atlas Data API with fallback to WebSocket.
- **Audio & Media**: Low-latency PCM streaming with JitterBuffer and Android MediaCodec.
- **Connectivity**: WebRTC peer connections with Google STUN traversal.
- **Target Platform**: Android SDK 35 (Android 15), compatible with minSdk 26 (Android 8.0+).

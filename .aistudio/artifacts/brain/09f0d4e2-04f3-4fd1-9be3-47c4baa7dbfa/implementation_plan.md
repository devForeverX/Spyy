# MongoDB Atlas Signaling & QR-Code Pairing

Migration des WebRTC-Signalisierungs- und Verbindungsaufbaus von einem lokalen/benutzerdefinierten WebSocket-Server auf einen weltweiten, serverlosen Cloud-Ansatz über die **MongoDB Atlas Data API** mit nahtloser **QR-Code-Erstverbindung** über die integrierte Kamera.

### User Review & Critical Decisions

> [!IMPORTANT]
> **Bestätigte Entscheidungen aus der Klärungsphase:**
> - **Signalisierungs-Transport:** MongoDB Atlas REST / HTTPS Data API (kein eigener Linux-VPS oder manuell gehosteter WebSocket-Server mehr nötig).
> - **QR-Code-Workflow:** Das Zielgerät (Remote) erzeugt und zeigt beim Start einen hochauflösenden QR-Code mit der Session-ID und dem kryptografischen Einmalcode an. Das Admin-Smartphone scannt den QR-Code direkt mit der Kamera (oder gibt den 6-stelligen Code manuell ein).
> - **STUN-Infrastruktur:** Standardmäßige Einbindung von Googles kostenlosem globalen STUN-Server (`stun:stun.l.google.com:19302`) für weltweites WebRTC NAT-Traversal über normale Internetverbindungen (WLAN, LTE, 5G).

---

### 1. Overview & Core Concept

- **Problem:** Die bisherige Version verlangte vom Benutzer, eine eigene `ws://`- oder `wss://`-Adresse einzugeben, was einen eigenen Server, Port-Forwarding oder lokales Netzwerk voraussetzte.
- **Lösung:** 
  1. Beide Geräte kommunizieren beim Verbindungsaufbau verschlüsselt über eine gemeinsame Session-Sammlung in **MongoDB Atlas** über standardmäßige HTTPS-Aufrufe.
  2. Das Zielgerät generiert beim Start eine Session und stellt die Zugangsdaten als **QR-Code** dar.
  3. Das Admin-Gerät scannt den QR-Code, woraufhin SDP-Offer/Answer und ICE-Kandidaten über MongoDB ausgetauscht werden.
  4. Sobald WebRTC über STUN etabliert ist, fließen alle Nutzdaten (Bildschirm, Touch-Gesten, Audio, Tastatur) direkt **P2P von Gerät zu Gerät**.

---

### 2. User Experience & Visual Design

#### Key User Flows

1. **Zielgerät (Remote-Modus):**
   - Tippt auf "Remote-Gerät (Zielgerät)".
   - Die App erstellt automatisch eine neue Sitzung in MongoDB Atlas und generiert einen scharfen, zentrierten QR-Code.
   - Unter dem QR-Code steht weiterhin der 6-stellige Zahlencode für manuelle Eingabe als Ausweichoption.
   - Statusanzeige: *"Warte auf Scan durch Fernbedienung..."*.

2. **Admin-Gerät (Controller-Modus):**
   - Tippt auf "Admin-Gerät (Fernbedienung)".
   - Wählt *"QR-Code scannen"* (öffnet den integrierten Kamera-Sucher mit Ziel-Rahmen) oder tippt den Zahlencode ein.
   - Sobald der QR-Code erkannt wird, vibriert das Smartphone kurz haptisch und startet sofort den Handshake.
   - Beide Geräte zeigen den 6-stelligen SAS-Sicherheitscode zur Bestätigung an.

3. **Aktive Sitzung:**
   - Nahtloser Übergang in die bestehende Controller-Ansicht: Live-Bildschirm, Touch/Swipe-Gesten, Navigationstasten, virtuelle Tastatur, Lautstärke-/Helligkeitsregelung und Audio-Streaming.

#### Visual Styling & Layout
- **Theme:** Dunkles, technisch-präzises Design (`DarkBackground` #0F172A, `DarkSurface` #1E293B, `EmeraldPrimary` #10B981).
- **QR-Code-Container:** Hoher Kontrast mit abgerundeten Ecken, dezenter Schatten und pulsierender Verbindungsstatusanzeige.
- **Kamera-Sucher:** Vollflächige Compose-Vorschau mit transparentem abgedunkeltem Overlay und animierter Scan-Linie.

---

### 3. Key Product Decisions & Trade-Offs

- **Entscheidung 1: Signalisierung über MongoDB Atlas Data API (HTTPS Polling / REST)**
  - *Gewählter Ansatz:* Das Remote-Gerät erstellt ein Dokument `sessions/{sessionId}`. Während der 3–5 Sekunden des WebRTC-Handshakes fragen beide Geräte die Signalisierungsdokumente in Intervallen (500 ms) ab, bis der P2P-WebRTC-Kanal aufgebaut ist.
  - *Warum:* Funktioniert weltweit hinter jeder Firewall, jedem Mobilfunknetz und jedem Router ohne offenen Port oder eigenen Server.
  - *Verworfene Alternative:* Manueller WebSocket-Server (zu kompliziert einzurichten) oder reines Bluetooth/Lokales WLAN (funktioniert nicht über das Internet).

- **Entscheidung 2: Native QR-Code-Generierung mit ZXing & CameraX**
  - *Gewählter Ansatz:* ZXing `QRCodeWriter` erzeugt eine lokale Compose-kompatible Bitmap für die Anzeige; CameraX liest das Bild mit dem integrierten ZXing-BarcodeReader aus dem Kamera-Frame.
  - *Warum:* Beide Bibliotheken sind bereits im Projekt eingebunden und benötigen keine Cloud-Dienste für die Bilderkennung.

- **Entscheidung 3: MongoDB Atlas API-Konfiguration**
  - *Gewählter Ansatz:* Konfigurierbare oder vorkonfigurierte MongoDB Atlas Endpunkte mit API-Key in der App (gesichert über `BuildConfig` / Einstellungen).

---

### 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Worldwide Internet                              │
│                                                                        │
│   ┌────────────────────┐                   ┌───────────────────────┐   │
│   │   Admin Handheld   │                   │    Target Handheld    │   │
│   │    (Controller)    │                   │   (Screen / Remote)   │   │
│   └─────────┬──────────┘                   └───────────┬───────────┘   │
│             │                                          │               │
│      1. Scan QR-Code                            Generiert QR-Code      │
│             │                                          │               │
│             │          Signalisierungs-Phase           │               │
│             ├───────────────┐          ┌───────────────┤               │
│             ▼               ▼          ▼               ▼               │
│   ┌────────────────────────────────────────────────────────────┐       │
│   │              MongoDB Atlas Cloud Data API                  │       │
│   │        - Collection: sessions                              │       │
│   │        - SDP Offer / SDP Answer / ICE Candidates           │       │
│   └────────────────────────────────────────────────────────────┘       │
│                                 │                                      │
│                     2. WebRTC STUN Traversal                           │
│             ┌───────────────────┴───────────────────┐                  │
│             ▼                                       ▼                  │
│   ┌────────────────────────────────────────────────────────────┐       │
│   │               Google Public STUN Server                    │       │
│   │               stun:stun.l.google.com:19302                 │       │
│   └─────────────────────────────┬──────────────────────────────┘       │
│                                 │                                      │
│                     3. Direkte P2P-Verbindung                          │
│             ◄═══════════════════════════════════════►                  │
│                   - Verschlüsselter Live-Bildschirm                    │
│                   - Touch- & Wisch-Einspeisung                         │
│                   - IME Tastatureingaben                               │
│                   - Hardware Telemetrie & Audio                        │
└────────────────────────────────────────────────────────────────────────┘
```

#### MongoDB Session-Datenmodell
```json
{
  "_id": "sess_8f3a9b1c",
  "pairingCode": "481923",
  "targetDeviceId": "dev_target_01",
  "adminDeviceId": "dev_admin_02",
  "status": "WAITING_FOR_ADMIN", // WAITING_FOR_ADMIN | OFFER_POSTED | ANSWER_POSTED | CONNECTED | EXPIRED
  "offerSdp": "v=0\r\no=...",
  "answerSdp": "v=0\r\no=...",
  "targetCandidates": [ { "sdpMid": "0", "sdpMLineIndex": 0, "candidate": "..." } ],
  "adminCandidates": [ { "sdpMid": "0", "sdpMLineIndex": 0, "candidate": "..." } ],
  "handshakeProof": "...",
  "createdAt": 1728912345000,
  "updatedAt": 1728912348000
}
```

#### Modul- und Bildschirm-Erweiterungen
1. **`MongoSignalingClient.kt`**: Implementiert das `SignalingClient`-Interface über die MongoDB Atlas REST Data API (Erstellen von Sessions, Abrufen von Updates, Posten von SDP/ICE).
2. **`QrCodeGenerator.kt`**: Erzeugt aus dem Session-Payload (`{"s":"<sessionId>","c":"<code hex>"}`) eine darstellbare Android-Bitmap.
3. **`QrScannerView.kt`**: Compose-Komponente mit CameraX-Vorschau und ZXing-ImageAnalyzer zur sofortigen QR-Erkennung.
4. **`PairingScreen.kt` Update**: Anzeige des QR-Codes auf dem Zielgerät und Scan-Button mit Live-Kamera-Sucher auf dem Admin-Gerät.
5. **`RemoteControlViewModel.kt`**: Nahtlose Integration des `MongoSignalingClient` in die bestehende WebRTC- und Krypto-Pipeline.

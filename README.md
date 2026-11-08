# 🛰️ ODU Manager — Cyfrowy Polsat / Plus

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Web%20%2F%20CLI%20%2F%20Desktop-orange.svg)](#)
[![Network](https://img.shields.io/badge/Network-Plus%20%2F%20Polsat%20Plus-10b981.svg)](#)

Niezależne, otwartoźródłowe oprogramowanie diagnostyczno-zarządcze dedykowane dla zestawów antenowo-routerowych **ODU-IDU** Grupy Polsat Plus / Cyfrowy Polsat (stworzonych z inicjatywy Zygmunta Solorza-Żaka, produkowanych przez InterPhone Service).

Program łączy się bezpośrednio z domowym routerem IDU i anteną ODU po lokalnej sieci, umożliwiając podgląd parametrów sygnału RF w czasie rzeczywistym, wymuszanie najszybszych pasm LTE Plusa, diagnostykę kabla koncentrycznego oraz automatyczny watchdog.

---

## 📡 Obsługiwane urządzenia

| Model | Technologia | Obsługiwane pasma LTE (MHz) | Agregacja (CA) |
| :--- | :--- | :--- | :--- |
| **ODU-IDU 300** | LTE-Advanced Cat. 6 (DL do 300 Mb/s, UL do 50 Mb/s) | FDD: **B3 (1800)**, **B7 (2600)**, **B8 (900)**, **B20 (800)** + TDD: **B38 (2600)** | **2CA** (B3+B7, B3+B8, B3+B20, B7+B20) |
| **ODU-IDU 200** | LTE Cat. 4 (DL do 150 Mb/s, UL do 50 Mb/s) | FDD: **B3 (1800)**, **B7 (2600)**, **B20 (800)** | Brak |
| **ODU-IDU 100** | LTE Cat. 3 (DL do 30 Mb/s) | FDD: **B3 (1800)** | Brak |

---

## 🚀 Główne funkcjonalności

### 1. Monitorowanie sygnału RF w czasie rzeczywistym (Live Telemetry)
* Ciągły odczyt kluczowych wskaźników radiowych z anteny ODU na dachu:
  * **RSRP** (Reference Signal Received Power) w dBm,
  * **RSRQ** (Reference Signal Received Quality) w dB,
  * **SINR** (Signal to Interference-plus-Noise Ratio) w dB,
  * **RSSI** (Received Signal Strength Indicator) w dBm.
* Graficzne wskaźniki jakości sygnału (z podziałem na zakresy: Doskonały, Dobry, Średni, Słaby).

### 2. Wymuszanie i blokowanie pasm LTE Plusa (Band Locking)
* Konfiguracja i blokowanie konkretnych pasm w modemie ODU:
  * Wymuszanie szybkich pasm pojemnościowych FDD: **B7 (2600 MHz)** + **B3 (1800 MHz)**,
  * Wymuszanie pasma TDD sieci Plus / Aero2: **B38 (2600 MHz TDD)** (w ODU-300),
  * Wykluczanie zapchanych pasm zasięgowych **B8 (900 MHz)** oraz **B20 (800 MHz)**.

### 3. Diagnostyka instalacji jednokablowej (ODU-IDU Coax)
* Kontrola zasilania modemu ODU przesyłanego kablem koncentrycznym TV-SAT z routera IDU.
* Wykrywanie zwarć, tłumienia kabla oraz zakłóceń z sumatorów telewizyjnych DVB-S/DVB-T.

### 4. Identyfikacja stacji bazowej i integracja z BTMap
* Odczyt identyfikatorów komórkowych: **eNodeB ID**, **Cell ID**, **PCI**, **TAC** oraz kod sieci `260 01` (Plus).
* Automatyczne dopasowywanie do bazy stacji bazowych (miejscowość, adres masztu, azymut sektora).
* Wskaźnik kompasu ułatwiający precyzyjne nakierowanie anteny ODU na właściwy maszt Plusa.

### 5. Watchdog i auto-restart
* Ciągłe sprawdzanie połączenia z internetem (ping do DNS / bramy).
* W razie utraty agregacji pasm lub zawieszenia modemu zewnętrznego – automatyczne wysłanie polecenia restartu do IDU bez konieczności odłączania wtyczki z gniazdka.

---

## 🛠️ Architektura i komunikacja z routerem

* **Adres bramy IDU**: domyślnie `http://192.168.0.1` (lub `http://192.168.1.1`).
* **Autoryzacja**: protokół HTTP POST / CGI Session Cookie (domyślny login: `admin`, hasło: `admin`).
* **Endpointy diagnostyczne ODU-IDU**:
  * Status sygnału i RF: `/cgi-bin/status_network.cgi` lub `/api/lte/status`
  * Parametry kabla i połączenia ODU: `/cgi-bin/status_odu.cgi`
  * Zarządzanie pasmami LTE: `/cgi-bin/network_settings.cgi`
  * Restart zestawu: `/cgi-bin/reboot.cgi`

---

## 💻 Sugerowany stos technologiczny
* **Frontend**: React + TypeScript + Vite + Tailwind CSS / Lucide Icons.
* **Backend / Klient API**: Node.js (Fastify / Express) lub lekki demon w Pythonie / Go.
* **Dystrybucja**: Aplikacja webowa lokalna (`localhost:4000`) lub aplikacja okienkowa (Electron / Tauri / PWA).

---

## 📄 Licencja
Projekt jest udostępniany na warunkach otwartoźródłowej licencji **MIT**. Szczegóły w pliku [LICENSE](LICENSE).

---
*Projekt społecznościowy, niezależny. Znaki towarowe Cyfrowy Polsat, Polsat Plus i Plus należą do ich odpowiednich właścicieli i zostały użyte wyłącznie w celach identyfikacji zgodności urządzeń.*

# DS4 RGB for Android

Android project for experimenting with DualShock 4 light-bar control.

## Current state
- Android Studio/Kotlin project structure.
- Bluetooth permission handling for Android 12+.
- RGB + brightness UI and live preview.
- DS4 USB output-report builder.
- DS4 Bluetooth output-report builder with CRC32.
- Transport abstraction so packet code is not tied to the UI.

## Important Android limitation
A normal Android application can receive a paired gamepad as an input device, but the public Android SDK does not provide a supported API for opening a Bluetooth HID-host connection and writing arbitrary HID output reports to that gamepad.

Therefore the current app does not pretend that a normal BluetoothSocket can control the DS4. AndroidPublicTransport reports this limitation.

The actual DS4 Bluetooth protocol is known: the main output report is report 0x11; the light-bar flag is in the common output section and the RGB bytes follow it, with a CRC32 trailer. Linux's hid-playstation driver implements this at the HID layer.

References:
- https://github.com/torvalds/linux/blob/master/drivers/hid/hid-playstation.c
- https://github.com/tongelberkay/DS4Lightbar

## Goal
The target is a no-root Android solution where possible. To actually transmit the Bluetooth report from an ordinary app, Android would need to expose a usable HID-host output-report API or the device/vendor would need to provide a privileged interface. Root/system privileges or an external HID bridge are alternatives, but are outside this app's current public-SDK implementation.

## Build
Open the repository in Android Studio and let Gradle sync. The project targets SDK 35 and min SDK 26.

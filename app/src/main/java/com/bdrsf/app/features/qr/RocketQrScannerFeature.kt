package com.bdrsf.app.features.qr

import android.app.Activity
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge

class RocketQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(
    activity,
    rootContainer,
    bridge,
    defaultMode = "rocket",
    defaultPromptText = "ফ্রেমের ভেতরে রকেট (Rocket) QR কোড রাখুন"
)

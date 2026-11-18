package com.bdrsf.app.features.qr

import android.app.Activity
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge

class NagadQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(
    activity,
    rootContainer,
    bridge,
    defaultMode = "nagad",
    defaultPromptText = "ফ্রেমের ভেতরে নগদ (Nagad) QR কোড রাখুন"
)

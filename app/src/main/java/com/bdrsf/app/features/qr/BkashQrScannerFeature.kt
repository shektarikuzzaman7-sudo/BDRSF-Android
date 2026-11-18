package com.bdrsf.app.features.qr

import android.app.Activity
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge

class BkashQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(
    activity,
    rootContainer,
    bridge,
    defaultMode = "bkash",
    defaultPromptText = "ফ্রেমের ভেতরে বিকাশ (bKash) QR কোড রাখুন"
)

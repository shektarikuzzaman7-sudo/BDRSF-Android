package com.bdrsf.app.features.qr

import android.app.Activity
import android.view.ViewGroup
import com.bdrsf.app.bridge.AndroidBridge

class WalletQrScannerFeature(
    activity: Activity,
    rootContainer: ViewGroup,
    bridge: AndroidBridge
) : BaseQrScannerFeature(
    activity,
    rootContainer,
    bridge,
    defaultMode = "wallet",
    defaultPromptText = "ফ্রেমের ভেতরে ওয়ালেট (Wallet) QR কোড রাখুন"
)

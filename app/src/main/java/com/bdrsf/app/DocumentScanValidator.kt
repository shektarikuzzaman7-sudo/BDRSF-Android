package com.bdrsf.app

object DocumentScanValidator {

    fun convertBanglaToEnglishDigits(input: String): String {
        if (input.isEmpty()) return ""
        val banglaDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val englishDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

        var result = input
        for (i in banglaDigits.indices) {
            result = result.replace(banglaDigits[i], englishDigits[i])
        }
        return result
    }

    // ১. ফ্রন্ট পার্ট থেকে ইংরেজি নাম, NID নম্বর ও জন্মতারিখ পড়া
    fun extractNidFrontData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val normalized = convertBanglaToEnglishDigits(rawText)
        val cleanDigitsOnly = normalized.replace("\\s+".toRegex(), "")

        // ১০, ১৩ বা ১৭ ডিজিটের এনআইডি নম্বর
        val nidMatch = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(cleanDigitsOnly)
        nidMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        // জন্ম তারিখ খোঁজা (যেমন: 21 Jan 2001 অথবা 02 Feb 1994)
        val dobMatch = Regex("(Date of Birth|Birth|জন্ম তারিখ)[:\\s]*([0-9]{1,2}[\\s/\\-.A-Za-z]{3,5}[0-9]{4})", RegexOption.IGNORE_CASE).find(rawText)
            ?: Regex("([0-9]{2}[\\s/\\-.A-Za-z]{3,5}[0-9]{4})").find(rawText)
            ?: Regex("([0-9]{2}[/-][0-9]{2}[/-][0-9]{4})").find(normalized)

        val foundDob = dobMatch?.groupValues?.getOrNull(2) ?: dobMatch?.value
        if (!foundDob.isNullOrBlank()) {
            result["dob"] = foundDob.trim()
        }

        // ইংরেজি নাম (Name এর পরের টেক্সট)
        val lines = rawText.lines().map { it.trim() }
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("Name", ignoreCase = true)) {
                val namePart = line.replace(Regex("(?i)^Name[:\\s]*"), "").trim()
                if (namePart.isNotEmpty()) {
                    result["name"] = namePart
                    break
                } else if (i + 1 < lines.size) {
                    result["name"] = lines[i + 1]
                    break
                }
            }
        }

        return result
    }

    // ২. পাসপোর্ট থেকে নাম ও পাসপোর্ট নম্বর এক্সট্র্যাক্ট করা
    fun extractPassportData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = rawText.lines().map { it.trim().replace(" ", "") }

        // MRZ লাইন: P<BGD...
        val mrzLine2 = lines.firstOrNull { it.length >= 35 && it.contains(Regex("[0-9]{6}[0-9MF<]")) }
        if (mrzLine2 != null) {
            val passNo = mrzLine2.substring(0, 9).replace("<", "").trim()
            val dobPart = if (mrzLine2.length >= 19) mrzLine2.substring(13, 19) else ""
            result["passportNo"] = passNo
            result["dob"] = dobPart
            result["isMrzValid"] = "true"
        }

        val passMatch = Regex("([A-Z][0-9]{8})").find(rawText)
        if (result["passportNo"].isNullOrEmpty()) {
            passMatch?.groupValues?.getOrNull(1)?.let {
                result["passportNo"] = it
            }
        }

        return result
    }

    // ৩. পেছনের বারকোড (PDF417 / 1D / QR) ডিকোড করা
    fun parseNidBarcodeData(barcodeData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (barcodeData.isBlank()) return result

        val normalized = convertBanglaToEnglishDigits(barcodeData)

        val pinMatch = Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("pin[:=]?\\s*([0-9]{10,17})", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(normalized)

        pinMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["name"] = it.trim() }

        Regex("<(nameBn|name_bn|banglaName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["name_bn"] = it.trim() }

        Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["dob"] = it.trim() }

        Regex("<(father|fname|fatherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["father"] = it.trim() }

        Regex("<(mother|mname|motherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["mother"] = it.trim() }

        return result
    }

    // ৪. ফ্রন্ট ও ব্যাক পার্ট ক্রস-ম্যাচিং (আসল কার্ড ভেরিফিকেশন)
    fun validateNidCrossMatch(frontData: Map<String, String>, backData: Map<String, String>, rawBarcode: String): Boolean {
        val frontNid = frontData["nid"]?.replace("\\D".toRegex(), "") ?: ""
        val backNid = backData["nid"]?.replace("\\D".toRegex(), "") ?: ""

        // ১. বারকোডের এনআইডি নম্বরের সাথে মিলানো
        if (frontNid.isNotEmpty() && backNid.isNotEmpty()) {
            val isNidMatched = frontNid == backNid ||
                    frontNid.endsWith(backNid) ||
                    backNid.endsWith(frontNid) ||
                    (frontNid.length == 17 && frontNid.substring(4) == backNid) ||
                    (backNid.length == 17 && backNid.substring(4) == frontNid)

            if (isNidMatched) return true
        }

        // ২. বারকোডের র-টেক্সটে যদি ফ্রন্টের এনআইডি নম্বরের অংশ থাকে
        if (frontNid.isNotEmpty() && rawBarcode.contains(frontNid)) {
            return true
        }

        // ৩. বারকোডের নামের সাথে ফ্রন্টের নামের মিল
        val frontName = frontData["name"]?.lowercase() ?: ""
        val backName = backData["name"]?.lowercase() ?: ""
        if (frontName.isNotEmpty() && backName.isNotEmpty()) {
            if (frontName.contains(backName) || backName.contains(frontName)) return true
        }

        return false
    }

    // ডকুমেন্ট আইসোলেশন ও লাইভ ক্যাপচার চেকার
    fun isNidFront(rawText: String): Boolean {
        if (rawText.contains("PASSPORT", true) || rawText.contains("P<", true)) return false
        return rawText.contains("Government", true) ||
                rawText.contains("Republic", true) ||
                rawText.contains("গণপ্রজাতন্ত্রী", true) ||
                rawText.contains("National ID", true) ||
                rawText.contains("জাতীয় পরিচয়", true)
    }

    fun isNidBack(rawText: String, hasBarcode: Boolean): Boolean {
        if (rawText.contains("PASSPORT", true) || rawText.contains("P<", true)) return false
        if (hasBarcode) return true
        return rawText.contains("ঠিকানা") || rawText.contains("ডাকঘর") || rawText.contains("গ্রাম") ||
                rawText.contains("Address", true) || rawText.contains("Blood", true)
    }

    fun isPassport(rawText: String): Boolean {
        return rawText.contains("PASSPORT", true) ||
                rawText.contains("P<BGD", true) ||
                (rawText.contains("P<", true) && rawText.contains("BANGLADESH", true))
    }

    fun isNidFrontClearForCapture(rawText: String?, lineCount: Int): Boolean = isNidFront(rawText ?: "") && lineCount >= 2
    fun isNidBackClearForCapture(rawText: String?, lineCount: Int, hasBarcode: Boolean): Boolean = isNidBack(rawText ?: "", hasBarcode) && (lineCount >= 2 || hasBarcode)
    fun isPassportClearForCapture(rawText: String?, lineCount: Int): Boolean = isPassport(rawText ?: "") && lineCount >= 2
}

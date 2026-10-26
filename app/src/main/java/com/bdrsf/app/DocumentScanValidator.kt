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

    // MainActivity.kt এর জন্য (ডিপ টেক্সট ভ্যালিডেশন)
    fun isValidNidFrontText(rawText: String?): Boolean {
        val text = rawText ?: return false
        val normalized = convertBanglaToEnglishDigits(text)
        val cleanDigitsOnly = normalized.replace("\\s+".toRegex(), "")

        val hasNidPattern = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").containsMatchIn(cleanDigitsOnly)
        val hasKeywords = text.contains("Name", true) ||
                text.contains("NID", true) ||
                text.contains("National", true) ||
                text.contains("Identity", true) ||
                text.contains("নাম", true) ||
                text.contains("গণপ্রজাতন্ত্রী", true) ||
                text.contains("Date of Birth", true) ||
                text.contains("জন্ম", true) ||
                text.contains("Birth", true)

        val isNotPassport = !text.contains("PASSPORT", true) && !text.contains("P<", true)

        return (hasNidPattern || hasKeywords) && isNotPassport
    }

    // MainActivity.kt এর জন্য (ডিপ টেক্সট ভ্যালিডেশন)
    fun isValidNidBackText(rawText: String?): Boolean {
        val text = rawText ?: return false
        val normalized = convertBanglaToEnglishDigits(text)

        val hasAddressKeyword = normalized.contains("ঠিকানা") ||
                normalized.contains("ডাকঘর") ||
                normalized.contains("Address", true) ||
                normalized.contains("Blood", true) ||
                normalized.contains("রক্তের") ||
                normalized.contains("বাসা") ||
                normalized.contains("গ্রাম") ||
                normalized.contains("Village", true) ||
                normalized.contains("প্রদানকারী") ||
                normalized.contains("Authority", true)

        val isNotPassport = !text.contains("P<", true) && !text.contains("PASSPORT", true)

        return hasAddressKeyword && isNotPassport
    }

    // MainActivity.kt এর জন্য (ডিপ টেক্সট ভ্যালিডেশন)
    fun isValidPassportText(rawText: String?): Boolean {
        val text = rawText ?: return false
        val cleanText = text.trim()

        val hasPassportTitle = cleanText.contains("PASSPORT", true) ||
                cleanText.contains("REPUBLIC OF BANGLADESH", true) ||
                cleanText.contains("PEOPLE'S REPUBLIC", true)

        val hasMRZ = cleanText.contains("P<BGD", true) || cleanText.contains("P<", true)

        val isNotNid = !cleanText.contains("National ID", true) && !cleanText.contains("জাতীয় পরিচয়পত্র", true)

        return (hasPassportTitle || hasMRZ) && isNotNid
    }

    // CameraScannerController এর লাইভ প্রিভিউ অটো-ক্যাপচারের জন্য
    fun isNidFrontClearForCapture(rawText: String?, lineCount: Int): Boolean {
        val text = rawText ?: return false
        val cleanDigitsOnly = convertBanglaToEnglishDigits(text).replace("\\s+".toRegex(), "")

        val hasGovHeader = text.contains("People's Republic", true) ||
                text.contains("Government", true) ||
                text.contains("National ID", true) ||
                text.contains("Identity Card", true) ||
                text.contains("গণপ্রজাতন্ত্রী", true) ||
                text.contains("বাংলাদেশ সরকার", true) ||
                text.contains("জাতীয় পরিচয়", true) ||
                text.contains("পরিচয়পত্র", true)

        val hasDataFields = text.contains("Name", true) ||
                text.contains("নাম", true) ||
                text.contains("Date of Birth", true) ||
                text.contains("জন্ম", true) ||
                text.contains("Father", true) ||
                text.contains("Mother", true) ||
                text.contains("পিতা", true) ||
                text.contains("মাতা", true) ||
                text.contains("NID", true)

        val hasNidNumber = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").containsMatchIn(cleanDigitsOnly)
        val isNotPassport = !text.contains("PASSPORT", true) && !text.contains("P<", true)

        return (hasGovHeader || (hasDataFields && hasNidNumber)) && lineCount >= 3 && isNotPassport
    }

    // CameraScannerController এর লাইভ প্রিভিউ ব্যাক-পার্ট অটো-ক্যাপচারের জন্য
    fun isNidBackClearForCapture(rawText: String?, lineCount: Int, hasAnyBarcode: Boolean): Boolean {
        if (hasAnyBarcode) return true

        val text = rawText ?: return false
        val normalized = convertBanglaToEnglishDigits(text)

        val hasAddress = normalized.contains("ঠিকানা") ||
                normalized.contains("ডাকঘর") ||
                normalized.contains("গ্রাম") ||
                normalized.contains("রাস্তা") ||
                normalized.contains("বাসা/হোল্ডিং") ||
                normalized.contains("উপজেলা") ||
                normalized.contains("জেলা") ||
                normalized.contains("Address", true) ||
                normalized.contains("Village", true) ||
                normalized.contains("Post Office", true)

        val hasAuthorityOrBlood = normalized.contains("প্রদানকারী") ||
                normalized.contains("কর্তৃপক্ষ") ||
                normalized.contains("রক্তের গ্রুপ") ||
                normalized.contains("Blood Group", true) ||
                normalized.contains("Authority", true)

        val isNotPassport = !text.contains("P<", true) && !text.contains("PASSPORT", true)

        return (hasAddress || hasAuthorityOrBlood) && lineCount >= 2 && isNotPassport
    }

    // CameraScannerController এর লাইভ প্রিভিউ পাসপোর্ট অটো-ক্যাপচারের জন্য
    fun isPassportClearForCapture(rawText: String?, lineCount: Int): Boolean {
        return isValidPassportText(rawText) && lineCount >= 3
    }

    // পেছনের বারকোড ডিকোড করা
    fun parseNidBarcodeData(barcodeData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (barcodeData.isBlank()) return result

        val normalized = convertBanglaToEnglishDigits(barcodeData)

        val nidMatch = Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?: Regex("pin[:=]?\\s*([0-9]{10,17})", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(normalized)

        nidMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

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

    fun validateNidMatch(frontNid: String, backNid: String): Boolean {
        val front = frontNid.replace("\\D".toRegex(), "")
        val back = backNid.replace("\\D".toRegex(), "")
        if (front.isEmpty() || back.isEmpty()) return false

        return front == back ||
                front.endsWith(back) ||
                back.endsWith(front) ||
                (front.length == 17 && front.substring(4) == back) ||
                (back.length == 17 && back.substring(4) == front)
    }
}

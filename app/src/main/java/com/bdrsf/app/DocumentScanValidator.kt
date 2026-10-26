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

    // ফ্রন্ট পার্ট থেকে গুরুত্বপূর্ণ তথ্যগুলো এক্সট্র্যাক্ট করা
    fun extractNidFrontData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val normalized = convertBanglaToEnglishDigits(rawText)
        val cleanDigitsOnly = normalized.replace("\\s+".toRegex(), "")

        // NID নম্বর
        val nidMatch = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(cleanDigitsOnly)
        nidMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        // জন্ম তারিখ (DD/MM/YYYY বা DD-MM-YYYY বা DD MMM YYYY)
        val dobMatch = Regex("(DOB|Date of Birth|জন্ম তারিখ|তারিখ)[:\\s]*([0-9]{1,2}[\\s/\\-.A-Za-z]{1,4}[0-9]{4})", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("([0-9]{2}[/-][0-9]{2}[/-][0-9]{4})").find(normalized)
        dobMatch?.groupValues?.getOrNull(2)?.let { result["dob"] = it.trim() }

        // নাম (Name / নাম)
        val nameMatch = Regex("(Name|নাম)[:\\s]*([^\\n\\r]+)", RegexOption.IGNORE_CASE).find(rawText)
        nameMatch?.groupValues?.getOrNull(2)?.let { result["name"] = it.trim() }

        return result
    }

    // পাসপোর্টের MRZ কোড ভ্যালিডেশন এবং ডেটা এক্সট্র্যাক্ট
    fun parsePassportMRZ(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = rawText.lines().map { it.trim().replace(" ", "") }
        
        // MRZ লাইন 1: P<BGD বা P<
        // MRZ লাইন 2: পাসপোর্ট নম্বর, দেশ, জন্মতারিখ
        val mrzLine1 = lines.firstOrNull { it.startsWith("P<") }
        val mrzLine2 = lines.firstOrNull { it.length >= 30 && it.contains(Regex("[0-9]{6}[0-9MF<]")) }

        if (mrzLine2 != null) {
            // পাসপোর্ট নম্বর সাধারণত প্রথম ৯ ক্যারেক্টার থাকে
            val passportNo = mrzLine2.substring(0, 9).replace("<", "").trim()
            val dobPart = if (mrzLine2.length >= 19) mrzLine2.substring(13, 19) else "" // YYMMDD
            
            result["passportNo"] = passportNo
            result["dob"] = dobPart
            result["isValid"] = "true"
        } else if (rawText.contains("PASSPORT", true) || rawText.contains("BANGLADESH", true)) {
            val passMatch = Regex("([A-Z]{1,2}[0-9]{7,8})").find(rawText)
            passMatch?.groupValues?.getOrNull(1)?.let {
                result["passportNo"] = it
                result["isValid"] = "true"
            }
        }
        return result
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

    // ফ্রন্ট এবং ব্যাক পার্টের কঠোর ক্রস-ম্যাচিং (কম্পিউটারে বানানো বা ফেক কার্ড ধরা)
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

    // ক্যামেরা অটো ক্যাপচারের লাইভ ফ্রন্ট চেক
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

    // ক্যামেরা অটো ক্যাপচারের লাইভ ব্যাক চেক
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

    fun isPassportClearForCapture(rawText: String?, lineCount: Int): Boolean {
        val text = rawText ?: return false
        val cleanText = text.trim()
        val hasPassportTitle = cleanText.contains("PASSPORT", true) ||
                cleanText.contains("REPUBLIC OF BANGLADESH", true) ||
                cleanText.contains("PEOPLE'S REPUBLIC", true)
        val hasMRZ = cleanText.contains("P<BGD", true) || cleanText.contains("P<", true)
        val isNotNid = !cleanText.contains("National ID", true) && !cleanText.contains("জাতীয় পরিচয়পত্র", true)

        return (hasPassportTitle || hasMRZ) && isNotNid && lineCount >= 3
    }

    fun isValidNidFrontText(rawText: String?): Boolean = isNidFrontClearForCapture(rawText, 3)
    fun isValidNidBackText(rawText: String?): Boolean = isNidBackClearForCapture(rawText, 2, false)
    fun isValidPassportText(rawText: String?): Boolean = isPassportClearForCapture(rawText, 3)
}

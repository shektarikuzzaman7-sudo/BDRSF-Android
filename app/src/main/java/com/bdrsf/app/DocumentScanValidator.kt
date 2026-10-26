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

    // ফ্রন্ট পার্ট থেকে নাম, পিতা-মাতা, NID নম্বর ও জন্মতারিখ পড়া
    fun extractNidFrontData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val normalized = convertBanglaToEnglishDigits(rawText)
        val cleanDigitsOnly = normalized.replace("\\s+".toRegex(), "")

        val nidMatch = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(cleanDigitsOnly)
        nidMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        val dobMatch = Regex("(Date of Birth|Birth|জন্ম তারিখ)[:\\s]*([0-9]{1,2}[\\s/\\-.A-Za-z]{3,6}[0-9]{4})", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("([0-9]{2}[\\s/\\-.A-Za-z]{3,6}[0-9]{4})").find(normalized)
            ?: Regex("([0-9]{2}[/-][0-9]{2}[/-][0-9]{4})").find(normalized)

        val foundDob = dobMatch?.groupValues?.lastOrNull() ?: dobMatch?.value
        if (!foundDob.isNullOrBlank()) {
            result["dob"] = foundDob.trim()
        }

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("Name", ignoreCase = true) || line.contains("নাম")) {
                val namePart = line.replace(Regex("(?i)^(Name|নাম)[:\\s]*"), "").trim()
                if (namePart.length > 2) {
                    result["name"] = namePart
                } else if (i + 1 < lines.size) {
                    result["name"] = lines[i + 1]
                }
            }
            if (line.contains("পিতা") || line.startsWith("Father", ignoreCase = true)) {
                val fPart = line.replace(Regex("(?i)^(Father's Name|Father|পিতা)[:\\s]*"), "").trim()
                if (fPart.length > 2) result["father"] = fPart
            }
            if (line.contains("মাতা") || line.startsWith("Mother", ignoreCase = true)) {
                val mPart = line.replace(Regex("(?i)^(Mother's Name|Mother|মাতা)[:\\s]*"), "").trim()
                if (mPart.length > 2) result["mother"] = mPart
            }
        }

        return result
    }

    // পেছনের অংশ থেকে ঠিকানা, রক্তের গ্রুপ ও পোস্ট কোড অফলাইনে বের করা
    fun extractNidBackAddressData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (rawText.isBlank()) return result

        val normalized = convertBanglaToEnglishDigits(rawText)
        result["rawAddress"] = rawText.replace("\n", ", ").trim()

        // ৪ সংখ্যার পোস্ট কোড
        Regex("\\b([1-9][0-9]{3})\\b").find(normalized)?.groupValues?.getOrNull(1)?.let {
            result["zipCode"] = it
        }

        // রক্তের গ্রুপ (A+, B+, O+, AB+, A-, B-, O-, AB-)
        Regex("(Blood Group|রক্তের গ্রুপ)[:\\s]*([ABO]{1,2}[+-])", RegexOption.IGNORE_CASE).find(normalized)?.groupValues?.getOrNull(2)?.let {
            result["bloodGroup"] = it.uppercase()
        } ?: Regex("\\b(AB|A|B|O)[+-]\\b").find(normalized)?.value?.let {
            result["bloodGroup"] = it.uppercase()
        }

        // প্রদানের তারিখ (Issue Date)
        Regex("(Issue Date|প্রদানের তারিখ)[:\\s]*([0-9]{1,2}[\\s/\\-.A-Za-z]{3,6}[0-9]{4})", RegexOption.IGNORE_CASE).find(normalized)?.groupValues?.getOrNull(2)?.let {
            result["issueDate"] = it.trim()
        }

        // বাংলা বা ইংরেজি কীওয়ার্ড দিয়ে ঠিকানার অংশগুলো বের করা
        Regex("(বাসা/হোল্ডিং|হোল্ডিং|Holding)[:\\s]*([^,\n]+)", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.getOrNull(2)?.let {
            result["holding"] = it.trim()
        }
        Regex("(গ্রাম/রাস্তা|গ্রাম|রাস্তা|Village|Road)[:\\s]*([^,\n]+)", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.getOrNull(2)?.let {
            result["village"] = it.trim()
        }
        Regex("(ডাকঘর|Post Office)[:\\s]*([^,\n-]+)", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.getOrNull(2)?.let {
            result["postOffice"] = it.trim()
        }
        Regex("(উপজেলা|থানা|Upazila|Thana)[:\\s]*([^,\n]+)", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.getOrNull(2)?.let {
            result["upazila"] = it.trim()
        }
        Regex("(জেলা|District)[:\\s]*([^,\n]+)", RegexOption.IGNORE_CASE).find(rawText)?.groupValues?.getOrNull(2)?.let {
            result["district"] = it.trim()
        }

        return result
    }

    // পাসপোর্ট থেকে নাম ও পাসপোর্ট নম্বর এক্সট্র্যাক্ট করা
    fun extractPassportData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val cleanLines = rawText.lines().map { it.trim().replace(" ", "") }

        // MRZ Line 1 থেকে নাম বের করা (P<BGDLASTNAME<<FIRSTNAME<<<)
        val mrzLine1 = cleanLines.firstOrNull { it.startsWith("P<", ignoreCase = true) && it.length >= 30 }
        if (mrzLine1 != null && mrzLine1.length > 5) {
            val rawNameSection = mrzLine1.substring(5)
            val nameParts = rawNameSection.split("<<")
            val surname = nameParts.getOrNull(0)?.replace("<", " ")?.trim() ?: ""
            val givenName = nameParts.getOrNull(1)?.replace("<", " ")?.trim() ?: ""
            val fullName = "$givenName $surname".trim()
            if (fullName.length > 2) {
                result["name"] = fullName
            }
        }

        val mrzLine2 = cleanLines.firstOrNull { it.length >= 35 && it.contains(Regex("[0-9]{6}[0-9MF<]")) }
        if (mrzLine2 != null) {
            val passNo = mrzLine2.substring(0, 9).replace("<", "").trim()
            val dobPart = if (mrzLine2.length >= 19) mrzLine2.substring(13, 19) else ""
            result["passportNo"] = passNo
            if (dobPart.length == 6 && dobPart.all { it.isDigit() }) {
                val yy = dobPart.substring(0, 2).toIntOrNull() ?: 0
                val yearPrefix = if (yy > 30) "19" else "20"
                result["dob"] = "${dobPart.substring(4, 6)}-${dobPart.substring(2, 4)}-$yearPrefix${dobPart.substring(0, 2)}"
            }
            result["isMrzValid"] = "true"
        }

        val passMatch = Regex("\\b([A-Z]{1,2}[0-9]{7,8})\\b").find(rawText)
        if (result["passportNo"].isNullOrEmpty()) {
            passMatch?.groupValues?.getOrNull(1)?.let {
                result["passportNo"] = it
            }
        }

        return result
    }

    // পেছনের PDF417 / QR বারকোড ডিকোড করা (পুরাতন XML ও নতুন স্মার্ট কার্ড ফরম্যাট)
    fun parseNidBarcodeData(barcodeData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (barcodeData.isBlank()) return result

        val normalized = convertBanglaToEnglishDigits(barcodeData)

        val pinMatch = Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("NW\\s*([0-9]{10,17})", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("pin[:=]?\\s*([0-9]{10,17})", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("\\b([0-9]{10}|[0-9]{13}|[0-9]{17})\\b").find(normalized)

        pinMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["name"] = it.trim() }
            ?: Regex("NM\\s*([^\n\r;]+)").find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["name"] = it.trim() }

        Regex("<(nameBn|name_bn|banglaName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["name_bn"] = it.trim() }

        Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["dob"] = it.trim() }
            ?: Regex("DB\\s*([0-9/-]{8,10})").find(normalized)?.groupValues?.getOrNull(1)?.let { result["dob"] = it.trim() }

        Regex("<(father|fname|fatherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["father"] = it.trim() }

        Regex("<(mother|mname|motherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["mother"] = it.trim() }

        Regex("<(blood|bloodGroup)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["bloodGroup"] = it.trim() }

        return result
    }

    // ফ্রন্ট ও ব্যাক পার্ট ক্রস-ম্যাচিং
    fun validateNidCrossMatch(frontData: Map<String, String>, backData: Map<String, String>, rawBarcode: String): Boolean {
        val frontNid = frontData["nid"]?.replace("\\D".toRegex(), "") ?: ""
        val backNid = backData["nid"]?.replace("\\D".toRegex(), "") ?: ""

        if (frontNid.isNotEmpty() && backNid.isNotEmpty()) {
            val isNidMatched = frontNid == backNid ||
                    frontNid.endsWith(backNid) ||
                    backNid.endsWith(frontNid) ||
                    (frontNid.length == 17 && frontNid.substring(4) == backNid) ||
                    (backNid.length == 17 && backNid.substring(4) == frontNid)

            if (isNidMatched) return true
        }

        if (frontNid.isNotEmpty() && convertBanglaToEnglishDigits(rawBarcode).contains(frontNid)) {
            return true
        }

        val frontName = frontData["name"]?.lowercase()?.trim() ?: ""
        val backName = backData["name"]?.lowercase()?.trim() ?: ""
        if (frontName.length > 3 && backName.length > 3) {
            if (frontName.contains(backName) || backName.contains(frontName)) return true
        }

        return false
    }

    fun validatePassportCrossMatch(passData: Map<String, String>, rawText: String): Boolean {
        val passNo = passData["passportNo"] ?: ""
        if (passNo.isEmpty()) return false
        val count = Regex(Regex.escape(passNo)).findAll(rawText).count()
        return count >= 1 && (rawText.contains("P<") || passData["isMrzValid"] == "true")
    }

    fun isNidFront(rawText: String): Boolean {
        if (rawText.contains("PASSPORT", true) || rawText.contains("P<", true)) return false
        return rawText.contains("Government", true) ||
                rawText.contains("Republic", true) ||
                rawText.contains("গণপ্রজাতন্ত্রী", true) ||
                rawText.contains("National ID", true) ||
                rawText.contains("জাতীয় পরিচয়", true) ||
                rawText.contains("Date of Birth", true)
    }

    fun isNidBack(rawText: String, hasBarcode: Boolean): Boolean {
        if (rawText.contains("PASSPORT", true) || rawText.contains("P<", true)) return false
        if (hasBarcode) return true
        return rawText.contains("ঠিকানা") || rawText.contains("ডাকঘর") || rawText.contains("গ্রাম") ||
                rawText.contains("Address", true) || rawText.contains("Blood", true) ||
                rawText.contains("Issue Date", true) || rawText.contains("উপজেলা")
    }

    fun isPassport(rawText: String): Boolean {
        return rawText.contains("PASSPORT", true) ||
                rawText.contains("P<BGD", true) ||
                (rawText.contains("P<", true) && rawText.contains("BANGLADESH", true))
    }

    fun isNidFrontClearForCapture(rawText: String?, lineCount: Int): Boolean =
        isNidFront(rawText ?: "") && lineCount >= 3

    fun isNidBackClearForCapture(rawText: String?, lineCount: Int, hasBarcode: Boolean): Boolean =
        isNidBack(rawText ?: "", hasBarcode) && (lineCount >= 2 || hasBarcode)

    fun isPassportClearForCapture(rawText: String?, lineCount: Int): Boolean =
        isPassport(rawText ?: "") && lineCount >= 3
}

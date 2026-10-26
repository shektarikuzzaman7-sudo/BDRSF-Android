package com.bdrsf.app

object DocumentScanValidator {

    fun convertBanglaToEnglishDigits(input: String): String {
        val banglaDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val englishDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

        var result = input
        for (i in banglaDigits.indices) {
            result = result.replace(banglaDigits[i], englishDigits[i])
        }
        return result
    }

    fun isValidNidFrontText(rawText: String): Boolean {
        val text = rawText ?: ""
        val normalized = convertBanglaToEnglishDigits(text)
        val cleanDigitsOnly = normalized.replace("\\s+".toRegex(), "")

        val hasNidPattern = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").containsMatchIn(cleanDigitsOnly)
        val hasKeywords = text.contains("Name", true) ||
            text.contains("NID", true) ||
            text.contains("National", true) ||
            text.contains("নাম") ||
            text.contains("গণপ্রজাতন্ত্রী") ||
            text.contains("Date of Birth", true) ||
            text.contains("জন্ম") ||
            text.contains("Birth", true)

        return hasNidPattern && hasKeywords
    }

    fun isValidNidBackText(rawText: String): Boolean {
        val text = rawText ?: ""
        val normalized = convertBanglaToEnglishDigits(text)

        val hasAddressKeyword = normalized.contains("ঠিকানা") ||
            normalized.contains("ডাকঘর") ||
            normalized.contains("Address", true) ||
            normalized.contains("Blood", true) ||
            normalized.contains("রক্তের") ||
            normalized.contains("বাসা") ||
            normalized.contains("গ্রাম") ||
            normalized.contains("Village", true)

        val hasBarcodeOrPattern = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").containsMatchIn(normalized)

        return hasAddressKeyword || hasBarcodeOrPattern
    }

    fun isValidPassportText(rawText: String): Boolean {
        val text = rawText ?: ""
        val normalized = convertBanglaToEnglishDigits(text)

        return normalized.contains("P<") ||
            normalized.contains("PASSPORT", true) ||
            normalized.contains("PEOPLE'S REPUBLIC", true) ||
            normalized.contains("BANGLADESH", true) ||
            normalized.contains("MRZ", true)
    }

    fun parseNidBarcodeData(barcodeData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (barcodeData.isEmpty()) return result

        val normalized = convertBanglaToEnglishDigits(barcodeData)

        val nidMatch = Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?: Regex("pin[:=]?\\s*([0-9]{10,17})", RegexOption.IGNORE_CASE)
                .find(normalized)
            ?: Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(normalized)

        nidMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE)
            .find(barcodeData)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { result["name"] = it.trim() }

        Regex("<(nameBn|name_bn|banglaName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { result["name_bn"] = it.trim() }

        Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE)
            .find(barcodeData)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { result["dob"] = it.trim() }

        Regex("<(father|fname|fatherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { result["father"] = it.trim() }

        Regex("<(mother|mname|motherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { result["mother"] = it.trim() }

        return result
    }

    fun validateNidMatch(frontNid: String, backNid: String): Boolean {
        val front = frontNid.replace("\\D".toRegex(), "")
        val back = backNid.replace("\\D".toRegex(), "")
        if (front.isEmpty() || back.isEmpty()) return false

        return front == back ||
            front.endsWith(back) ||
            back.endsWith(front)
    }
}

package com.ninetag.machum.external

import java.text.Normalizer

internal actual fun normalizeUnicodeNfc(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC)

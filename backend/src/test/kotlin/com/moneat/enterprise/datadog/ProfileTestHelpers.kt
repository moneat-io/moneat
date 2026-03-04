// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.datadog

import com.github.luben.zstd.Zstd

fun buildJfrLikePayload(): ByteArray {
    return byteArrayOf(
        'F'.code.toByte(),
        'L'.code.toByte(),
        'R'.code.toByte(),
        0x00,
        0x00,
        0x02,
        0x00,
        0x01,
    )
}

fun zstd(bytes: ByteArray): ByteArray = Zstd.compress(bytes)

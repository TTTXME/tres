// P665: the develop record's raw DEFLATE on the iPhone (Foundation's zlib: RFC 1951 data without header or checksum; DevelopRecord.hpp
// wraps it as Android's Deflater / Inflater streams). Objective-C++ only: included by ScamShot.mm and ScamDevelop.mm.
#pragma once

#import <Foundation/Foundation.h>

#include "post/DevelopRecord.hpp"

#include <cstring>
#include <stdexcept>
#include <vector>

static inline const scam_post::DevelopRecord::Codec &scamDevelopCodec() {
    static const scam_post::DevelopRecord::Codec codec = [] {
        scam_post::DevelopRecord::Codec c;
        c.deflateRaw = [](const uint8_t *p, size_t n) {
            @autoreleasepool {
                NSData *in = [NSData dataWithBytesNoCopy:const_cast<uint8_t *>(p) length:n freeWhenDone:NO];
                NSError *error = nil;
                NSData *out = [in compressedDataUsingAlgorithm:NSDataCompressionAlgorithmZlib error:&error];
                if (!out) throw std::runtime_error("the develop record's deflate failed");
                const uint8_t *b = static_cast<const uint8_t *>(out.bytes);
                return std::vector<uint8_t>(b, b + out.length);
            }
        };
        c.inflateRaw = [](const uint8_t *p, size_t n, uint8_t *out, size_t outLen) {
            @autoreleasepool {
                NSData *in = [NSData dataWithBytesNoCopy:const_cast<uint8_t *>(p) length:n freeWhenDone:NO];
                NSError *error = nil;
                NSData *plain = [in decompressedDataUsingAlgorithm:NSDataCompressionAlgorithmZlib error:&error];
                if (!plain || plain.length < outLen) return false;
                std::memcpy(out, plain.bytes, outLen);
                return true;
            }
        };
        return c;
    }();
    return codec;
}

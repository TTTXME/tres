import AVFoundation

/// Log lines describing what a lens and the photo output really offer (stage 1 asks for these from the phone).
enum DeviceProbe {
    static func describe(device: AVCaptureDevice) -> [String] {
        let format = device.activeFormat
        var lines: [String] = []
        lines.append("device \(device.localizedName) type=\(device.deviceType.rawValue) id=\(device.uniqueID) position=\(device.position.rawValue)")
        lines.append("format \(CMFormatDescriptionGetMediaSubType(format.formatDescription).fourCC) \(CMVideoFormatDescriptionGetDimensions(format.formatDescription).text)"
            + " fov=\(String(format: "%.2f", format.videoFieldOfView)) maxPhoto=[\(format.supportedMaxPhotoDimensions.map(\.text).joined(separator: ","))]"
            + " ISZ=[\(format.secondaryNativeResolutionZoomFactors.map { String(format: "%.2f", $0) }.joined(separator: ","))]")
        lines.append("exposure iso=\(format.minISO)...\(format.maxISO) duration=\(seconds(format.minExposureDuration))...\(seconds(format.maxExposureDuration))"
            + " aperture=f/\(device.lensAperture) zoom=\(device.videoZoomFactor) range=\(device.minAvailableVideoZoomFactor)...\(device.maxAvailableVideoZoomFactor)"
            + " minFocus=\(device.minimumFocusDistance)mm customLens=\(device.isLockingFocusWithCustomLensPositionSupported)")
        let fullRes = device.formats.filter { DeviceLookup.maxPhotoArea($0) >= 40_000_000 }
        lines.append("formats total=\(device.formats.count) with>=40MP=\(fullRes.count)")
        return lines
    }

    static func describe(output: AVCapturePhotoOutput) -> [String] {
        let raw = output.availableRawPhotoPixelFormatTypes.map { type -> String in
            let kind = AVCapturePhotoOutput.isBayerRAWPixelFormat(type) ? "bayer"
                : AVCapturePhotoOutput.isAppleProRAWPixelFormat(type) ? "proraw" : "other"
            return "\(type.fourCC):\(kind)"
        }
        var lines = ["photoOutput raw=[\(raw.joined(separator: ","))] proRAW supported=\(output.isAppleProRAWSupported) enabled=\(output.isAppleProRAWEnabled)"
            + " maxPhoto=\(output.maxPhotoDimensions.text) codecs=[\(output.availablePhotoCodecTypes.map(\.rawValue).joined(separator: ","))]"]
        lines.append("photoOutput bracketMax=\(output.maxBracketedCapturePhotoCount) zsl=\(output.isZeroShutterLagSupported)/\(output.isZeroShutterLagEnabled)"
            + " responsive=\(output.isResponsiveCaptureSupported)/\(output.isResponsiveCaptureEnabled)"
            + " fastPrioritization=\(output.isFastCapturePrioritizationSupported)/\(output.isFastCapturePrioritizationEnabled)"
            + " quality=\(output.maxPhotoQualityPrioritization.rawValue)")
        return lines
    }

    static func seconds(_ time: CMTime) -> String {
        let s = CMTimeGetSeconds(time)
        return s >= 0.1 ? String(format: "%.2fs", s) : String(format: "1/%.0f", 1 / max(s, 1e-6))
    }
}

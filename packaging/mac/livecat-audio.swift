// LiveCat's own sound on a Mac, for recordings and the livestream (the Mac twin of LoopbackCapture.cs).
//
//   livecat-audio <pid>            the sound of that process and its child processes (LiveCat: VLC, YouTube)
//   livecat-audio <pid> exclude    everything the Mac plays except that process (Audio Output sources)
//
// Same protocol as the Windows helper: the first line on stderr is "FORMAT f32le 48000 2" (or "ERROR <why>"), then
// the sound goes to stdout as raw stereo 32-bit float PCM at 48 kHz. Uses ScreenCaptureKit (macOS 13 or newer), which
// needs the Screen & System Audio Recording permission once; no extra audio driver (BlackHole) is needed.
// Built by .github/workflows/macos.yml and put inside LiveCat Multimedia.app next to the jar.

import CoreMedia
import Darwin
import Foundation
import ScreenCaptureKit

setvbuf(stdout, nil, _IOFBF, 1 << 16)

func say(_ line: String) {
    FileHandle.standardError.write((line + "\n").data(using: .utf8)!)
}

func fail(_ why: String) -> Never {
    say("ERROR " + why)
    exit(1)
}

let args = CommandLine.arguments
guard args.count >= 2, let target = pid_t(args[1]) else { fail("usage: livecat-audio <pid> [exclude]") }
let exclude = args.count >= 3 && args[2] == "exclude"

/** The parent process of pid, or 0. */
func parent(of pid: pid_t) -> pid_t {
    var info = kinfo_proc()
    var size = MemoryLayout<kinfo_proc>.stride
    var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, pid]
    if sysctl(&mib, 4, &info, &size, nil, 0) != 0 { return 0 }
    return info.kp_eproc.e_ppid
}

/** True for the target process and every process it started (YouTube's Chromium helpers). */
func belongs(_ pid: pid_t) -> Bool {
    var p = pid
    var steps = 0
    while p > 1 && steps < 64 {
        if p == target { return true }
        p = parent(of: p)
        steps += 1
    }
    return false
}

final class Capture: NSObject, SCStreamOutput, SCStreamDelegate {
    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio, sb.isValid else { return } // the (tiny) picture ScreenCaptureKit insists on is ignored
        let frames = CMSampleBufferGetNumSamples(sb)
        if frames <= 0 { return }
        var out = [Float](repeating: 0, count: frames * 2)
        do {
            try sb.withAudioBufferList { list, _ in
                if list.count >= 2, let l = list[0].mData, let r = list[1].mData {
                    // non-interleaved: one buffer per channel
                    let lp = l.assumingMemoryBound(to: Float.self)
                    let rp = r.assumingMemoryBound(to: Float.self)
                    for i in 0..<frames {
                        out[2 * i] = lp[i]
                        out[2 * i + 1] = rp[i]
                    }
                } else if list.count == 1, let d = list[0].mData {
                    let ch = max(1, Int(list[0].mNumberChannels))
                    let p = d.assumingMemoryBound(to: Float.self)
                    for i in 0..<frames {
                        out[2 * i] = p[i * ch]
                        out[2 * i + 1] = p[i * ch + (ch > 1 ? 1 : 0)]
                    }
                }
            }
        } catch {
            return
        }
        let written = out.withUnsafeBytes { raw in fwrite(raw.baseAddress, 1, raw.count, stdout) }
        if written < frames * 8 { exit(0) } // LiveCat stopped reading
        fflush(stdout)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        fail("sound capture stopped: \(error.localizedDescription)")
    }
}

let capture = Capture()
var running: SCStream?

Task {
    do {
        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
        guard let display = content.displays.first else { fail("no display found") }
        let ours = content.applications.filter { belongs($0.processID) }
        let filter: SCContentFilter
        if exclude {
            filter = SCContentFilter(display: display, excludingApplications: ours, exceptingWindows: [])
        } else {
            if ours.isEmpty { fail("LiveCat is not visible to macOS sound capture") }
            filter = SCContentFilter(display: display, including: ours, exceptingWindows: [])
        }
        let config = SCStreamConfiguration()
        config.capturesAudio = true
        config.sampleRate = 48000
        config.channelCount = 2
        config.excludesCurrentProcessAudio = true
        // ScreenCaptureKit always captures a picture too: the smallest, once a second
        config.width = 2
        config.height = 2
        config.minimumFrameInterval = CMTime(value: 1, timescale: 1)
        config.queueDepth = 3
        let stream = SCStream(filter: filter, configuration: config, delegate: capture)
        try stream.addStreamOutput(capture, type: .audio, sampleHandlerQueue: DispatchQueue(label: "livecat.audio"))
        try stream.addStreamOutput(capture, type: .screen, sampleHandlerQueue: DispatchQueue(label: "livecat.picture"))
        try await stream.startCapture()
        running = stream
        say("FORMAT f32le 48000 2")
    } catch {
        fail("macOS did not allow recording LiveCat's sound. Open System Settings → Privacy & Security → "
            + "Screen & System Audio Recording, turn on LiveCat Multimedia, then restart LiveCat. (\(error.localizedDescription))")
    }
}

// ends together with LiveCat
Thread {
    while true {
        sleep(1)
        if getppid() == 1 { exit(0) }
    }
}.start()

dispatchMain()

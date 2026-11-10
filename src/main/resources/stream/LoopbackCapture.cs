// jamc-loopback: captures only what one process and its children play (Windows process loopback, Windows 10
// version 2004 or newer) and writes it to stdout as raw PCM, so nothing else playing on the PC is recorded.
// Silence is written while nothing plays, so the stream follows the clock.
// Usage: jamc-loopback.exe <process id> [exclude]
//   <process id>          what that process and its children play (this app's sound, or another program's: App Audio)
//   <process id> exclude  everything the PC plays EXCEPT that process and its children (Audio Output / desktop sound)
// stderr, first line: "FORMAT f32le <rate> <channels>"; then the PCM on stdout.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

[Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient {
    int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
    int GetBufferSize(out uint frames);
    int GetStreamLatency(out long latency);
    int GetCurrentPadding(out uint frames);
    int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    int GetMixFormat(out IntPtr format);
    int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
    int Start();
    int Stop();
    int Reset();
    int SetEventHandle(IntPtr handle);
    int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
}

[Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient {
    int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
    int ReleaseBuffer(uint frames);
    int GetNextPacketSize(out uint frames);
}

[ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IActivateAudioInterfaceAsyncOperation {
    int GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
}

[ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IActivateAudioInterfaceCompletionHandler {
    int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
}

// says the callback may arrive on any thread, which Windows requires of this handler
[ComImport, Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAgileObject { }

class Activated : IActivateAudioInterfaceCompletionHandler, IAgileObject {
    public readonly ManualResetEvent Done = new ManualResetEvent(false);
    public int Result;
    public object Client;

    public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation) {
        operation.GetActivateResult(out Result, out Client);
        Done.Set();
        return 0;
    }
}

static class Loopback {
    const int RATE = 48000, CHANNELS = 2, BLOCK_ALIGN = 8, WAVE_FORMAT_IEEE_FLOAT = 3;
    const int SILENT = 0x2;
    // AUDCLNT_STREAMFLAGS: LOOPBACK | EVENTCALLBACK | AUTOCONVERTPCM
    const int STREAM_FLAGS = 0x00020000 | 0x00040000 | unchecked((int) 0x80000000);
    const string PROCESS_LOOPBACK = "VAD\\Process_Loopback";
    const int ACTIVATION_TYPE_PROCESS_LOOPBACK = 1, INCLUDE_TARGET_PROCESS_TREE = 0, EXCLUDE_TARGET_PROCESS_TREE = 1;

    [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = true)]
    static extern int ActivateAudioInterfaceAsync([MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath, ref Guid riid,
        IntPtr activationParams, IActivateAudioInterfaceCompletionHandler handler, out IActivateAudioInterfaceAsyncOperation operation);

    static void Check(int hr, string what) {
        if (hr != 0) throw new Exception(what + " failed (0x" + hr.ToString("X8") + ")");
    }

    /** AUDIOCLIENT_ACTIVATION_PARAMS (type, process id, mode) in a PROPVARIANT of type VT_BLOB. */
    static IntPtr ActivationParams(int processId, bool exclude) {
        IntPtr blob = Marshal.AllocHGlobal(12);
        Marshal.WriteInt32(blob, 0, ACTIVATION_TYPE_PROCESS_LOOPBACK);
        Marshal.WriteInt32(blob, 4, processId);
        Marshal.WriteInt32(blob, 8, exclude ? EXCLUDE_TARGET_PROCESS_TREE : INCLUDE_TARGET_PROCESS_TREE);
        IntPtr variant = Marshal.AllocHGlobal(24);
        for (int i = 0; i < 24; i++) Marshal.WriteByte(variant, i, 0);
        Marshal.WriteInt16(variant, 0, 65); // VT_BLOB
        Marshal.WriteInt32(variant, 8, 12);
        Marshal.WriteIntPtr(variant, 8 + IntPtr.Size, blob);
        return variant;
    }

    static IntPtr WaveFormat() {
        IntPtr f = Marshal.AllocHGlobal(18);
        Marshal.WriteInt16(f, 0, WAVE_FORMAT_IEEE_FLOAT);
        Marshal.WriteInt16(f, 2, CHANNELS);
        Marshal.WriteInt32(f, 4, RATE);
        Marshal.WriteInt32(f, 8, RATE * BLOCK_ALIGN);
        Marshal.WriteInt16(f, 12, BLOCK_ALIGN);
        Marshal.WriteInt16(f, 14, 32);
        Marshal.WriteInt16(f, 16, 0);
        return f;
    }

    static IAudioClient Activate(int processId, bool exclude) {
        var activated = new Activated();
        Guid audioClientId = typeof(IAudioClient).GUID;
        IActivateAudioInterfaceAsyncOperation operation;
        Check(ActivateAudioInterfaceAsync(PROCESS_LOOPBACK, ref audioClientId, ActivationParams(processId, exclude), activated, out operation),
            "ActivateAudioInterfaceAsync");
        if (!activated.Done.WaitOne(10000)) throw new Exception("Windows did not answer");
        Check(activated.Result, "Process audio capture");
        return (IAudioClient) activated.Client;
    }

    [MTAThread]
    static int Main(string[] args) {
        try {
            int processId = int.Parse(args[0]);
            bool exclude = args.Length > 1 && args[1] == "exclude";
            IAudioClient client;
            try {
                client = Activate(processId, exclude);
                Check(client.Initialize(0, STREAM_FLAGS, 2000000, 0, WaveFormat(), IntPtr.Zero), "Initialize");
            } catch (Exception e) {
                throw new Exception(e.Message + ". Recording only this app's sound needs Windows 10 version 2004 or newer.");
            }
            var ready = new AutoResetEvent(false);
            Check(client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()), "SetEventHandle");
            Guid captureId = typeof(IAudioCaptureClient).GUID;
            object c;
            Check(client.GetService(ref captureId, out c), "GetService");
            var capture = (IAudioCaptureClient) c;
            Console.Error.WriteLine("FORMAT f32le " + RATE + " " + CHANNELS);
            Console.Error.Flush();

            Stream output = Console.OpenStandardOutput();
            byte[] buffer = new byte[BLOCK_ALIGN * RATE]; // one second at most per write
            Check(client.Start(), "Start");
            var clock = Stopwatch.StartNew();
            long written = 0;
            while (true) {
                ready.WaitOne(10);
                uint packet;
                Check(capture.GetNextPacketSize(out packet), "GetNextPacketSize");
                bool any = false;
                while (packet > 0) {
                    IntPtr data; uint frames, flags; ulong devicePosition, qpc;
                    Check(capture.GetBuffer(out data, out frames, out flags, out devicePosition, out qpc), "GetBuffer");
                    // silence first for the quiet time before this packet, so every sound stays at its own time
                    long at = clock.ElapsedMilliseconds * RATE / 1000 - frames;
                    while (at - written > RATE / 50) {
                        int gap = (int) Math.Min(at - written, RATE);
                        Array.Clear(buffer, 0, gap * BLOCK_ALIGN);
                        output.Write(buffer, 0, gap * BLOCK_ALIGN);
                        written += gap;
                    }
                    int bytes = (int) Math.Min(frames * (uint) BLOCK_ALIGN, (uint) buffer.Length);
                    if ((flags & SILENT) != 0) Array.Clear(buffer, 0, bytes);
                    else Marshal.Copy(data, buffer, 0, bytes);
                    output.Write(buffer, 0, bytes);
                    written += frames;
                    capture.ReleaseBuffer(frames);
                    capture.GetNextPacketSize(out packet);
                    any = true;
                }
                // nothing is playing (Windows sends no packets): keep the stream on time with silence
                long due = clock.ElapsedMilliseconds * RATE / 1000;
                if (!any && due - written > RATE / 20) {
                    int frames = (int) Math.Min(due - written, RATE);
                    Array.Clear(buffer, 0, frames * BLOCK_ALIGN);
                    output.Write(buffer, 0, frames * BLOCK_ALIGN);
                    written += frames;
                }
                if (any) output.Flush();
            }
        } catch (IOException) {
            return 0; // the reader closed the pipe: recording / streaming ended
        } catch (Exception e) {
            Console.Error.WriteLine("ERROR " + e.Message);
            return 2;
        }
    }
}

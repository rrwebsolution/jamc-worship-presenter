// jamc-loopback: captures what a Windows output device plays (WASAPI loopback, like OBS "Desktop Audio")
// and writes it to stdout as raw PCM. Silence is written while nothing plays, so the stream follows the clock.
// Usage: jamc-loopback.exe ["part of the output device name"]   (no argument = the Windows default output)
// stderr, first line: "FORMAT f32le|s16le <rate> <channels> <device name>"; then the PCM on stdout.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
class MMDeviceEnumeratorCo { }

[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
}

[Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceCollection {
    int GetCount(out int count);
    int Item(int index, out IMMDevice device);
}

[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
    int OpenPropertyStore(int access, out IPropertyStore store);
}

[Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IPropertyStore {
    int GetCount(out int count);
    int GetAt(int index, out PropertyKey key);
    int GetValue(ref PropertyKey key, out PropVariant value);
}

[StructLayout(LayoutKind.Sequential)]
struct PropertyKey { public Guid fmtid; public int pid; }

[StructLayout(LayoutKind.Explicit, Size = 24)]
struct PropVariant { [FieldOffset(0)] public short vt; [FieldOffset(8)] public IntPtr pointer; }

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

static class Loopback {
    const int RENDER = 0, ACTIVE = 1, CLSCTX_ALL = 23, LOOPBACK = 0x00020000, SILENT = 0x2;

    static string FriendlyName(IMMDevice d) {
        IPropertyStore store;
        if (d.OpenPropertyStore(0, out store) != 0) return "";
        var key = new PropertyKey { fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), pid = 14 };
        PropVariant v;
        if (store.GetValue(ref key, out v) != 0 || v.vt != 31) return "";
        return Marshal.PtrToStringUni(v.pointer);
    }

    static void Check(int hr, string what) {
        if (hr != 0) throw new Exception(what + " failed (0x" + hr.ToString("X8") + ")");
    }

    static int Main(string[] args) {
        try {
            var enumerator = (IMMDeviceEnumerator) new MMDeviceEnumeratorCo();
            IMMDevice device = null;
            string wanted = args.Length > 0 ? args[0].Trim() : "";
            if (wanted.Length > 0) {
                IMMDeviceCollection all;
                Check(enumerator.EnumAudioEndpoints(RENDER, ACTIVE, out all), "EnumAudioEndpoints");
                int n;
                all.GetCount(out n);
                for (int i = 0; i < n && device == null; i++) {
                    IMMDevice d;
                    all.Item(i, out d);
                    string name = FriendlyName(d);
                    if (name.IndexOf(wanted, StringComparison.OrdinalIgnoreCase) >= 0
                            || wanted.IndexOf(name, StringComparison.OrdinalIgnoreCase) >= 0 && name.Length > 0) {
                        device = d;
                    }
                }
            }
            if (device == null) {
                Check(enumerator.GetDefaultAudioEndpoint(RENDER, 0, out device), "GetDefaultAudioEndpoint");
            }
            Guid audioClientId = typeof(IAudioClient).GUID;
            object o;
            Check(device.Activate(ref audioClientId, CLSCTX_ALL, IntPtr.Zero, out o), "Activate");
            var client = (IAudioClient) o;
            IntPtr format;
            Check(client.GetMixFormat(out format), "GetMixFormat");
            short tag = Marshal.ReadInt16(format, 0);
            int channels = Marshal.ReadInt16(format, 2);
            int rate = Marshal.ReadInt32(format, 4);
            int blockAlign = Marshal.ReadInt16(format, 12);
            int bits = Marshal.ReadInt16(format, 14);
            bool isFloat = tag == 3 || (tag == unchecked((short) 0xFFFE) && Marshal.ReadInt32(format, 24) == 3);
            string sample = isFloat && bits == 32 ? "f32le" : bits == 16 ? "s16le" : bits == 32 ? "s32le" : "s24le";
            Check(client.Initialize(0, LOOPBACK, 10000000, 0, format, IntPtr.Zero), "Initialize");
            Guid captureId = typeof(IAudioCaptureClient).GUID;
            object c;
            Check(client.GetService(ref captureId, out c), "GetService");
            var capture = (IAudioCaptureClient) c;
            Console.Error.WriteLine("FORMAT " + sample + " " + rate + " " + channels + " " + FriendlyName(device));
            Console.Error.Flush();

            Stream output = Console.OpenStandardOutput();
            byte[] buffer = new byte[blockAlign * rate]; // one second at most per write
            Check(client.Start(), "Start");
            var clock = Stopwatch.StartNew();
            // the time each packet was captured (100 ns units, the same clock WASAPI stamps packets with)
            long origin = (long) (Stopwatch.GetTimestamp() * (10000000.0 / Stopwatch.Frequency));
            long written = 0;
            while (true) {
                uint packet;
                Check(capture.GetNextPacketSize(out packet), "GetNextPacketSize");
                bool any = false;
                while (packet > 0) {
                    IntPtr data; uint frames, flags; ulong devicePosition, qpc;
                    Check(capture.GetBuffer(out data, out frames, out flags, out devicePosition, out qpc), "GetBuffer");
                    // silence first for the quiet time before this packet, so every sound stays at its own time
                    // (also when the reader was slow for a moment)
                    long at = ((long) qpc - origin) * rate / 10000000;
                    while (at - written > rate / 50) {
                        int gap = (int) Math.Min(at - written, rate);
                        Array.Clear(buffer, 0, gap * blockAlign);
                        output.Write(buffer, 0, gap * blockAlign);
                        written += gap;
                    }
                    int bytes = (int) Math.Min(frames * (uint) blockAlign, (uint) buffer.Length);
                    if ((flags & SILENT) != 0) Array.Clear(buffer, 0, bytes);
                    else Marshal.Copy(data, buffer, 0, bytes);
                    output.Write(buffer, 0, bytes);
                    written += frames;
                    capture.ReleaseBuffer(frames);
                    capture.GetNextPacketSize(out packet);
                    any = true;
                }
                // nothing is playing (Windows sends no packets): keep the stream on time with silence
                long due = clock.ElapsedMilliseconds * rate / 1000;
                if (!any && due - written > rate / 20) {
                    int frames = (int) Math.Min(due - written, rate);
                    Array.Clear(buffer, 0, frames * blockAlign);
                    output.Write(buffer, 0, frames * blockAlign);
                    written += frames;
                }
                if (any) output.Flush();
                Thread.Sleep(10);
            }
        } catch (IOException) {
            return 0; // the reader closed the pipe: recording / streaming ended
        } catch (Exception e) {
            Console.Error.WriteLine("ERROR " + e.Message);
            return 2;
        }
    }
}

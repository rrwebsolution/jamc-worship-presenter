package com.jamc.worship.ui;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;
import java.awt.Component;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real Windows file and folder dialogs (Explorer style: Quick access, OneDrive, This PC, search) for every
 * "Choose file / Choose folder / Import" in the app. A drop-in for {@code chooser.showOpenDialog(parent)}: the
 * {@link JFileChooser} still carries the title, filters, start folder and multi-selection, and receives the
 * chosen file(s), so the code around it does not change.
 *
 * <p>The dialog runs on its own COM thread while the event thread keeps dispatching, so Preview, Program and the
 * projector keep playing while it is open. A picker for "a file or a folder" first asks which one (Windows has
 * no dialog for both). Anywhere the Windows dialog is not available, the Swing chooser is shown instead.
 */
public final class NativeFileDialogs {

    private static final Logger log = LoggerFactory.getLogger(NativeFileDialogs.class);

    private static final Guid.GUID CLSID_OPEN = new Guid.GUID("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}");
    private static final Guid.GUID IID_OPEN = new Guid.GUID("{d57c7288-d4ad-4768-be02-9d969532d960}");
    private static final Guid.GUID CLSID_SAVE = new Guid.GUID("{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}");
    private static final Guid.GUID IID_SAVE = new Guid.GUID("{84bccd23-5fde-4cdb-aea4-af64b83d78ab}");
    private static final Guid.GUID IID_SHELL_ITEM = new Guid.GUID("{43826d1e-e718-42ee-bc55-a1e261c37bfe}");

    // IFileDialog options
    private static final int FOS_OVERWRITEPROMPT = 0x2;
    private static final int FOS_PICKFOLDERS = 0x20;
    private static final int FOS_FORCEFILESYSTEM = 0x40;
    private static final int FOS_ALLOWMULTISELECT = 0x200;
    private static final int FOS_PATHMUSTEXIST = 0x800;
    private static final int FOS_FILEMUSTEXIST = 0x1000;
    private static final int SIGDN_FILESYSPATH = 0x80058000;
    private static final int ERROR_CANCELLED = 0x800704C7;
    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final int COINIT_APARTMENTTHREADED = 0x2;

    // vtable slots: IUnknown 0-2, IModalWindow::Show 3, IFileDialog 4-26, IFileOpenDialog 27-28
    private static final int RELEASE = 2;
    private static final int SHOW = 3;
    private static final int SET_FILE_TYPES = 4;
    private static final int SET_FILE_TYPE_INDEX = 5;
    private static final int GET_FILE_TYPE_INDEX = 6;
    private static final int SET_OPTIONS = 9;
    private static final int GET_OPTIONS = 10;
    private static final int SET_FOLDER = 12;
    private static final int SET_FILE_NAME = 15;
    private static final int SET_TITLE = 17;
    private static final int SET_OK_BUTTON_LABEL = 18;
    private static final int GET_RESULT = 20;
    private static final int SET_DEFAULT_EXTENSION = 22;
    private static final int GET_RESULTS = 27;
    // IShellItem::GetDisplayName 5; IShellItemArray::GetCount 7, GetItemAt 8
    private static final int ITEM_GET_DISPLAY_NAME = 5;
    private static final int ARRAY_GET_COUNT = 7;
    private static final int ARRAY_GET_ITEM_AT = 8;

    private NativeFileDialogs() {
    }

    /** Same as {@code chooser.showOpenDialog(parent)}, with the Windows dialog. */
    public static int showOpenDialog(JFileChooser chooser, Component parent) {
        return show(chooser, parent, false, null);
    }

    /** Same as {@code chooser.showSaveDialog(parent)}, with the Windows dialog. */
    public static int showSaveDialog(JFileChooser chooser, Component parent) {
        return show(chooser, parent, true, null);
    }

    /** Same as {@code chooser.showDialog(parent, approveText)} (an open dialog with its own button text). */
    public static int showDialog(JFileChooser chooser, Component parent, String approveText) {
        return show(chooser, parent, false, approveText);
    }

    private static int show(JFileChooser chooser, Component parent, boolean save, String approveText) {
        if (!isWindows()) {
            return swing(chooser, parent, save, approveText);
        }
        int mode = chooser.getFileSelectionMode();
        boolean folders = mode == JFileChooser.DIRECTORIES_ONLY;
        if (mode == JFileChooser.FILES_AND_DIRECTORIES && !save) {
            // Windows has no dialog for "a file or a folder": ask which one first
            Object[] options = {"FILE…", "FOLDER…", "Cancel"};
            String title = chooser.getDialogTitle() == null ? "Open" : chooser.getDialogTitle();
            int which = JOptionPane.showOptionDialog(parent, "Choose a file, or a whole folder?", title,
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (which != 0 && which != 1) {
                return JFileChooser.CANCEL_OPTION;
            }
            folders = which == 1;
        }
        List<Spec> specs = new ArrayList<>();
        int selected = 0;
        if (!folders) {
            FileFilter current = chooser.getFileFilter();
            FileFilter all = chooser.isAcceptAllFileFilterUsed() ? chooser.getAcceptAllFileFilter() : null;
            for (FileFilter f : chooser.getChoosableFileFilters()) {
                if (f == all) {
                    continue; // "All files" goes last, as in every Windows dialog
                }
                if (!(f instanceof FileNameExtensionFilter ext)) {
                    return swing(chooser, parent, save, approveText); // a filter Windows cannot express
                }
                if (f == current) {
                    selected = specs.size();
                }
                specs.add(Spec.of(ext));
            }
            if (all != null) {
                if (current == all) {
                    selected = specs.size();
                }
                specs.add(new Spec("All Files (*.*)", "*.*", null, all));
            }
        }
        Request req = new Request(save, folders, chooser.isMultiSelectionEnabled() && !save && !folders, title(chooser, save, folders),
                approveText, startFolder(chooser), chooser.getSelectedFile() == null || !save ? null : chooser.getSelectedFile().getName(),
                specs, selected, owner(parent));
        Result result;
        try {
            result = onComThread(req);
        } catch (Throwable e) {
            log.warn("The Windows file dialog could not be shown; using the built-in one", e);
            return swing(chooser, parent, save, approveText);
        }
        if (result == null || result.files.isEmpty()) {
            return JFileChooser.CANCEL_OPTION;
        }
        if (result.typeIndex >= 0 && result.typeIndex < specs.size()) {
            chooser.setFileFilter(specs.get(result.typeIndex).filter);
        }
        if (chooser.isMultiSelectionEnabled()) {
            // also for one folder: code for a multi-selection chooser reads getSelectedFiles()
            chooser.setSelectedFiles(result.files.toArray(File[]::new));
        }
        chooser.setSelectedFile(result.files.getFirst());
        return JFileChooser.APPROVE_OPTION;
    }

    private static int swing(JFileChooser chooser, Component parent, boolean save, String approveText) {
        if (approveText != null) {
            return chooser.showDialog(parent, approveText);
        }
        return save ? chooser.showSaveDialog(parent) : chooser.showOpenDialog(parent);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String title(JFileChooser chooser, boolean save, boolean folders) {
        String t = chooser.getDialogTitle();
        return t != null && !t.isBlank() ? t : folders ? "Select Folder" : save ? "Save" : "Open";
    }

    /** The folder to open in, or null to let Windows open the folder used last time. */
    private static File startFolder(JFileChooser chooser) {
        File selected = chooser.getSelectedFile();
        if (selected != null && selected.getParentFile() != null && selected.getParentFile().isDirectory()) {
            return selected.getParentFile();
        }
        File dir = chooser.getCurrentDirectory();
        File standard = FileSystemView.getFileSystemView().getDefaultDirectory();
        return dir == null || !dir.isDirectory() || dir.equals(standard) ? null : dir;
    }

    private static Pointer owner(Component parent) {
        Window w = parent == null ? null : parent instanceof Window win ? win : SwingUtilities.getWindowAncestor(parent);
        try {
            return w != null && w.isDisplayable() ? Native.getWindowPointer(w) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** One file type: "PowerPoint presentations (*.pptx, *.ppt)" → "*.pptx;*.ppt". */
    private record Spec(String name, String pattern, String defaultExtension, FileFilter filter) {
        static Spec of(FileNameExtensionFilter f) {
            List<String> parts = new ArrayList<>();
            for (String e : f.getExtensions()) {
                parts.add("*." + e);
            }
            String name = f.getDescription();
            if (!name.contains("*.")) {
                name = name + " (" + String.join(", ", parts) + ")";
            }
            String[] exts = f.getExtensions();
            return new Spec(name, String.join(";", parts), exts.length == 0 ? null : exts[0], f);
        }
    }

    private record Request(boolean save, boolean folders, boolean multi, String title, String okLabel, File folder,
                           String fileName, List<Spec> specs, int selectedType, Pointer owner) {
    }

    private record Result(List<File> files, int typeIndex) {
    }

    /**
     * Shows the dialog on a new COM (STA) thread. On the event thread a secondary loop keeps Swing painting and
     * dispatching (Program and the projector keep playing) until the dialog closes.
     */
    private static Result onComThread(Request req) throws Throwable {
        AtomicReference<Result> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        boolean edt = SwingUtilities.isEventDispatchThread();
        SecondaryLoop loop = edt ? Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop() : null;
        Thread t = new Thread(() -> {
            try {
                out.set(runDialog(req));
            } catch (Throwable e) {
                err.set(e);
            } finally {
                if (loop != null) {
                    loop.exit();
                }
            }
        }, "windows-file-dialog");
        t.setDaemon(true);
        t.start();
        if (loop != null) {
            loop.enter();
        }
        t.join();
        if (err.get() != null) {
            throw err.get();
        }
        return out.get();
    }

    private static Result runDialog(Request req) {
        Ole32 ole = Ole32.INSTANCE;
        ole.CoInitializeEx(null, COINIT_APARTMENTTHREADED);
        List<Memory> keep = new ArrayList<>(); // native strings must live until the dialog is gone
        Pointer dialog = null;
        try {
            PointerByReference ref = new PointerByReference();
            check(ole.CoCreateInstance(req.save ? CLSID_SAVE : CLSID_OPEN, null, CLSCTX_INPROC_SERVER,
                    req.save ? IID_SAVE : IID_OPEN, ref).intValue(), "CoCreateInstance");
            dialog = ref.getValue();
            IntByReference opts = new IntByReference();
            check(call(dialog, GET_OPTIONS, opts), "GetOptions");
            int o = opts.getValue() | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST;
            if (req.folders) {
                o |= FOS_PICKFOLDERS;
            } else if (req.save) {
                o |= FOS_OVERWRITEPROMPT;
            } else {
                o |= FOS_FILEMUSTEXIST;
            }
            if (req.multi) {
                o |= FOS_ALLOWMULTISELECT;
            }
            check(call(dialog, SET_OPTIONS, o), "SetOptions");
            if (!req.specs.isEmpty()) {
                Memory table = new Memory((long) req.specs.size() * 2 * Native.POINTER_SIZE);
                keep.add(table);
                for (int i = 0; i < req.specs.size(); i++) {
                    table.setPointer((long) (2 * i) * Native.POINTER_SIZE, wide(req.specs.get(i).name, keep));
                    table.setPointer((long) (2 * i + 1) * Native.POINTER_SIZE, wide(req.specs.get(i).pattern, keep));
                }
                check(call(dialog, SET_FILE_TYPES, req.specs.size(), table), "SetFileTypes");
                check(call(dialog, SET_FILE_TYPE_INDEX, req.selectedType + 1), "SetFileTypeIndex");
                String ext = req.specs.get(req.selectedType).defaultExtension;
                if (req.save && ext != null) {
                    call(dialog, SET_DEFAULT_EXTENSION, new WString(ext));
                }
            }
            call(dialog, SET_TITLE, new WString(req.title));
            if (req.okLabel != null) {
                call(dialog, SET_OK_BUTTON_LABEL, new WString(req.okLabel));
            }
            if (req.fileName != null) {
                call(dialog, SET_FILE_NAME, new WString(req.fileName));
            }
            if (req.folder != null) {
                Pointer folder = shellItem(req.folder);
                if (folder != null) {
                    call(dialog, SET_FOLDER, folder);
                    call(folder, RELEASE);
                }
            }
            int hr = call(dialog, SHOW, req.owner);
            if (hr == ERROR_CANCELLED) {
                return null;
            }
            check(hr, "Show");
            List<File> files = new ArrayList<>();
            if (req.multi) {
                PointerByReference arr = new PointerByReference();
                check(call(dialog, GET_RESULTS, arr), "GetResults");
                Pointer items = arr.getValue();
                IntByReference n = new IntByReference();
                call(items, ARRAY_GET_COUNT, n);
                for (int i = 0; i < n.getValue(); i++) {
                    PointerByReference item = new PointerByReference();
                    if (call(items, ARRAY_GET_ITEM_AT, i, item) >= 0) {
                        addPath(item.getValue(), files);
                    }
                }
                call(items, RELEASE);
            } else {
                PointerByReference item = new PointerByReference();
                check(call(dialog, GET_RESULT, item), "GetResult");
                addPath(item.getValue(), files);
            }
            int type = -1;
            if (!req.specs.isEmpty()) {
                IntByReference idx = new IntByReference();
                if (call(dialog, GET_FILE_TYPE_INDEX, idx) >= 0) {
                    type = idx.getValue() - 1;
                }
            }
            return new Result(files, type);
        } finally {
            if (dialog != null) {
                call(dialog, RELEASE);
            }
            ole.CoUninitialize();
        }
    }

    /**
     * Checks that the Windows dialog can be built here (COM, options, file types, title, start folder) without
     * showing it; for tests and diagnostics.
     */
    static boolean selfTest(File folder) {
        if (!isWindows()) {
            return false;
        }
        Ole32 ole = Ole32.INSTANCE;
        ole.CoInitializeEx(null, COINIT_APARTMENTTHREADED);
        List<Memory> keep = new ArrayList<>();
        try {
            for (boolean save : new boolean[]{false, true}) {
                PointerByReference ref = new PointerByReference();
                check(ole.CoCreateInstance(save ? CLSID_SAVE : CLSID_OPEN, null, CLSCTX_INPROC_SERVER,
                        save ? IID_SAVE : IID_OPEN, ref).intValue(), "CoCreateInstance");
                Pointer dialog = ref.getValue();
                try {
                    IntByReference opts = new IntByReference();
                    check(call(dialog, GET_OPTIONS, opts), "GetOptions");
                    check(call(dialog, SET_OPTIONS, opts.getValue() | FOS_FORCEFILESYSTEM | (save ? 0 : FOS_PICKFOLDERS)), "SetOptions");
                    Memory table = new Memory(2L * Native.POINTER_SIZE);
                    keep.add(table);
                    table.setPointer(0, wide("Videos (*.mp4)", keep));
                    table.setPointer(Native.POINTER_SIZE, wide("*.mp4", keep));
                    if (save) {
                        check(call(dialog, SET_FILE_TYPES, 1, table), "SetFileTypes");
                        check(call(dialog, SET_FILE_TYPE_INDEX, 1), "SetFileTypeIndex");
                        IntByReference idx = new IntByReference();
                        check(call(dialog, GET_FILE_TYPE_INDEX, idx), "GetFileTypeIndex");
                        check(call(dialog, SET_DEFAULT_EXTENSION, new WString("mp4")), "SetDefaultExtension");
                        check(call(dialog, SET_FILE_NAME, new WString("test.mp4")), "SetFileName");
                    }
                    check(call(dialog, SET_TITLE, new WString("Test")), "SetTitle");
                    check(call(dialog, SET_OK_BUTTON_LABEL, new WString("Use")), "SetOkButtonLabel");
                    Pointer start = shellItem(folder);
                    if (start == null) {
                        return false;
                    }
                    check(call(dialog, SET_FOLDER, start), "SetFolder");
                    List<File> back = new ArrayList<>();
                    addPath(start, back); // the start folder reads back as the same path (and is released)
                    // the same folder, though Windows may spell it differently (long name for an 8.3 short name)
                    if (back.isEmpty() || !java.nio.file.Files.isSameFile(back.getFirst().toPath(), folder.toPath())) {
                        return false;
                    }
                } finally {
                    call(dialog, RELEASE);
                }
            }
            return true;
        } catch (java.io.IOException e) {
            return false;
        } finally {
            ole.CoUninitialize();
        }
    }

    private static void addPath(Pointer item, List<File> into) {
        PointerByReference name = new PointerByReference();
        if (call(item, ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, name) >= 0 && name.getValue() != null) {
            into.add(new File(name.getValue().getWideString(0)));
            Ole32.INSTANCE.CoTaskMemFree(name.getValue());
        }
        call(item, RELEASE);
    }

    private static Pointer shellItem(File folder) {
        PointerByReference item = new PointerByReference();
        Function f = NativeLibrary.getInstance("shell32").getFunction("SHCreateItemFromParsingName");
        int hr = f.invokeInt(new Object[]{new WString(folder.getAbsolutePath()), null, IID_SHELL_ITEM, item});
        return hr >= 0 ? item.getValue() : null;
    }

    private static Pointer wide(String s, List<Memory> keep) {
        Memory m = new Memory((s.length() + 1L) * Native.WCHAR_SIZE);
        m.setWideString(0, s);
        keep.add(m);
        return m;
    }

    /** Calls method {@code index} of a COM object's vtable; returns the HRESULT. */
    private static int call(Pointer object, int index, Object... args) {
        Pointer vtable = object.getPointer(0);
        Function f = Function.getFunction(vtable.getPointer((long) index * Native.POINTER_SIZE), Function.ALT_CONVENTION);
        Object[] all = new Object[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invokeInt(all);
    }

    private static void check(int hr, String what) {
        if (hr < 0) {
            throw new IllegalStateException(what + " failed: 0x" + Integer.toHexString(hr));
        }
    }
}

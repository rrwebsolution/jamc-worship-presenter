package com.jamc.worship.ui.dialogs;

import com.jamc.worship.media.MediaProbe;
import com.jamc.worship.model.MediaCategory;
import com.jamc.worship.model.MediaItem;
import com.jamc.worship.model.MediaKind;
import com.jamc.worship.model.Presentation;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.service.PresentationService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.MediaChoosers;
import com.jamc.worship.ui.NativeFileDialogs;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * UPLOAD FILE… in the library pickers (Select Image / Video / Audio / Presentation, Add Images): the operator picks
 * files on the computer and they are saved in the Library by themselves (copied, so they keep working if the
 * originals move), then chosen in the picker at once. No trip to the Library first.
 */
public final class LibraryUploads {

    private LibraryUploads() {
    }

    /** The folder the last upload opened in (this session). */
    private static File lastDir;

    /** Images, videos or audio into Library → Images / Videos / Audio. Videos are checked with VLC first. */
    public static LibraryPickerDialog.Uploader<MediaItem> media(MediaService media, MediaKind kind) {
        return (parent, many, done) -> {
            JFileChooser chooser = new JFileChooser(lastDir);
            chooser.setMultiSelectionEnabled(many);
            chooser.setDialogTitle(kind == MediaKind.VIDEO ? "Upload Video" : kind == MediaKind.AUDIO ? "Upload Audio" : "Upload Image");
            if (kind == MediaKind.VIDEO) {
                MediaChoosers.videoFilters(chooser);
            } else if (kind == MediaKind.IMAGE) {
                MediaChoosers.imageFilters(chooser);
            } else {
                FileNameExtensionFilter audio = new FileNameExtensionFilter("Audio Files", MediaKind.AUDIO.extensions().toArray(String[]::new));
                chooser.addChoosableFileFilter(audio);
                chooser.setFileFilter(audio);
            }
            List<Path> files = choose(chooser, parent, many);
            if (files.isEmpty()) {
                done.accept(List.of()); // cancelled
                return;
            }
            BackgroundTask.run("Upload media", () -> {
                List<MediaItem> added = new ArrayList<>();
                List<String> failed = new ArrayList<>();
                for (Path f : files) {
                    try {
                        java.awt.Dimension size = null;
                        if (kind == MediaKind.VIDEO) {
                            MediaProbe.Result probe = MediaProbe.probeVideo(f);
                            if (probe.status() == MediaProbe.Status.UNPLAYABLE) {
                                failed.add(f.getFileName() + ": " + probe.message().replace("\n", " "));
                                continue;
                            }
                            size = new java.awt.Dimension(probe.width(), probe.height());
                        }
                        added.add(media.importFile(f, true, MediaCategory.forKind(kind), kind, size));
                    } catch (Exception e) {
                        failed.add(f.getFileName() + ": " + e.getMessage());
                    }
                }
                return new Object[]{added, failed};
            }, r -> {
                @SuppressWarnings("unchecked") List<MediaItem> added = (List<MediaItem>) r[0];
                @SuppressWarnings("unchecked") List<String> failed = (List<String>) r[1];
                if (!failed.isEmpty()) {
                    Dialogs.showInfo(parent, "Some files were not uploaded", String.join("\n", failed)
                            + (kind == MediaKind.VIDEO ? "\n\n" + MediaProbe.RECOMMENDED : ""));
                }
                done.accept(added);
            }, err -> {
                Dialogs.showError(parent, "Could not upload the file.", err);
                done.accept(List.of());
            });
        };
    }

    /** PowerPoint files into Library → Presentations (its slides are rendered, which can take a moment). */
    public static LibraryPickerDialog.Uploader<Presentation> presentations(PresentationService service) {
        return (parent, many, done) -> {
            JFileChooser chooser = new JFileChooser(lastDir);
            chooser.setMultiSelectionEnabled(many);
            chooser.setDialogTitle("Upload PowerPoint");
            chooser.setFileFilter(new FileNameExtensionFilter("PowerPoint presentations (*.pptx, *.ppt)",
                    "pptx", "ppt", "pptm", "ppsx", "pps"));
            List<Path> files = choose(chooser, parent, many);
            if (files.isEmpty()) {
                done.accept(List.of()); // cancelled
                return;
            }
            BackgroundTask.run("Upload presentation", () -> {
                List<Presentation> added = new ArrayList<>();
                List<String> failed = new ArrayList<>();
                for (Path f : files) {
                    if (!service.canImport(f)) {
                        failed.add(f.getFileName() + ": not a PowerPoint file (.pptx or .ppt).");
                        continue;
                    }
                    try {
                        // already in the library: that one is used (Library → Presentations → Refresh updates it)
                        Presentation existing = service.findBySource(f).orElse(null);
                        added.add(existing != null ? existing : service.importFile(f, new PresentationService.Progress() {
                            @Override
                            public void started(String name, int slideCount, String rendererName) {
                            }

                            @Override
                            public void slideReady(PresentationService.PresentationSlide slide) {
                            }
                        }));
                    } catch (Exception e) {
                        failed.add(f.getFileName() + ": " + e.getMessage());
                    }
                }
                return new Object[]{added, failed};
            }, r -> {
                @SuppressWarnings("unchecked") List<Presentation> added = (List<Presentation>) r[0];
                @SuppressWarnings("unchecked") List<String> failed = (List<String>) r[1];
                if (!failed.isEmpty()) {
                    Dialogs.showInfo(parent, "Some files were not uploaded", String.join("\n", failed));
                }
                done.accept(added);
            }, err -> {
                Dialogs.showError(parent, "Could not upload the presentation.", err);
                done.accept(List.of());
            });
        };
    }

    private static List<Path> choose(JFileChooser chooser, Component parent, boolean many) {
        if (NativeFileDialogs.showOpenDialog(chooser, parent) != JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        if (many) {
            for (File f : chooser.getSelectedFiles()) {
                out.add(f.toPath());
            }
        } else if (chooser.getSelectedFile() != null) {
            out.add(chooser.getSelectedFile().toPath());
        }
        if (!out.isEmpty()) {
            lastDir = out.getFirst().toFile().getParentFile();
        }
        return out;
    }
}

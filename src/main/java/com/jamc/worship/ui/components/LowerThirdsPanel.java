package com.jamc.worship.ui.components;

import com.jamc.worship.model.LowerThird;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.service.SceneService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Lower-third list for the library column. Editing and SHOW/HIDE happen in {@link LowerThirdEditorPanel}. */
public class LowerThirdsPanel extends JPanel {

    private final SceneService scenes;
    private final DefaultListModel<LowerThird> model = new DefaultListModel<>();
    private final JList<LowerThird> list = new JList<>(model);
    private Consumer<LowerThird> selectionListener = l -> { };
    private Consumer<ServiceItem> addToServiceHandler = i -> { };
    private Consumer<String> statusHandler = s -> { };

    public LowerThirdsPanel(SceneService scenes) {
        super(new BorderLayout());
        this.scenes = scenes;
        setOpaque(false);
        JButton add = Ui.toolButton("plus", "New lower third");
        add.addActionListener(e -> create());
        add(Ui.header(Ui.label("Lower Thirds", Palette::text, "h3"), Ui.small("Names and titles"), add), BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(46);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                LowerThird lt = (LowerThird) v;
                JLabel lbl = (JLabel) super.getListCellRendererComponent(l, "<html><b>" + esc(lt.getTitle()) + "</b><br><small>"
                        + esc(lt.getSubtitle() == null ? "" : lt.getSubtitle()) + "</small></html>", i, sel, false);
                lbl.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 8));
                return lbl;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectionListener.accept(list.getSelectedValue());
            }
        });
        list.setDragEnabled(true);
        list.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected java.awt.datatransfer.Transferable createTransferable(JComponent c) {
                LowerThird lt = list.getSelectedValue();
                return lt == null ? null : new ServiceItemTransfer(serviceItem(lt));
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(Ui.line(true, false, false, false));
        add(sp, BorderLayout.CENTER);

        JButton dup = Ui.toolButton("copy", "Duplicate");
        dup.addActionListener(e -> duplicate());
        JButton del = Ui.toolButton("trash", "Delete");
        del.addActionListener(e -> delete());
        JButton addToService = Ui.primaryButton("Add to Service", "list-plus");
        addToService.addActionListener(e -> {
            LowerThird lt = list.getSelectedValue();
            if (lt != null) addToServiceHandler.accept(serviceItem(lt));
        });
        JPanel left = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(dup);
        left.add(del);
        JPanel bar = Ui.transparent(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, false, false, false),
                BorderFactory.createEmptyBorder(6, 6, 6, 8)));
        bar.add(left, BorderLayout.WEST);
        bar.add(addToService, BorderLayout.EAST);
        add(bar, BorderLayout.SOUTH);
    }

    public void setSelectionListener(Consumer<LowerThird> l) {
        selectionListener = l;
    }

    public void setAddToServiceHandler(Consumer<ServiceItem> h) {
        addToServiceHandler = h;
    }

    public void setStatusHandler(Consumer<String> h) {
        statusHandler = h;
    }

    public List<LowerThird> items() {
        java.util.ArrayList<LowerThird> out = new java.util.ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            out.add(model.get(i));
        }
        return out;
    }

    public static ServiceItem serviceItem(LowerThird lt) {
        return new ServiceItem(ServiceItem.Type.LOWER_THIRD, lt.getTitle()
                + (lt.getSubtitle() == null || lt.getSubtitle().isBlank() ? "" : " — " + lt.getSubtitle()), lt.getId());
    }

    public void refresh(Long selectId) {
        Long keep = selectId != null ? selectId : (list.getSelectedValue() == null ? null : list.getSelectedValue().getId());
        BackgroundTask.run("Load lower thirds", scenes::lowerThirds, items -> {
            model.clear();
            model.addAll(items);
            for (int i = 0; i < items.size(); i++) {
                if (Objects.equals(items.get(i).getId(), keep)) {
                    list.setSelectedIndex(i);
                }
            }
            if (list.getSelectedIndex() < 0 && !items.isEmpty()) {
                list.setSelectedIndex(0);
            }
        }, err -> Dialogs.showError(this, "Could not load lower thirds.", err));
    }

    public void select(long id) {
        refresh(id);
    }

    /** Called by the editor after a save, to show the new title in the list. */
    public void updated(LowerThird lt) {
        for (int i = 0; i < model.size(); i++) {
            if (Objects.equals(model.get(i).getId(), lt.getId())) {
                model.set(i, lt);
            }
        }
    }

    private void create() {
        LowerThird lt = new LowerThird();
        lt.setName("New lower third");
        lt.setTitle("Name");
        lt.setSubtitle("Title or role");
        BackgroundTask.run("Create lower third", () -> scenes.saveLowerThird(lt), saved -> {
            statusHandler.accept("Created a lower third");
            refresh(saved.getId());
        }, err -> Dialogs.showError(this, "Could not create the lower third.", err));
    }

    private void duplicate() {
        LowerThird lt = list.getSelectedValue();
        if (lt == null) {
            return;
        }
        LowerThird copy = lt.copy();
        copy.setId(null);
        copy.setName(lt.getName() + " (Copy)");
        BackgroundTask.run("Duplicate lower third", () -> scenes.saveLowerThird(copy), saved -> refresh(saved.getId()),
                err -> Dialogs.showError(this, "Could not duplicate.", err));
    }

    private void delete() {
        LowerThird lt = list.getSelectedValue();
        if (lt == null || !Dialogs.confirmDestructive(this, "Delete lower third",
                "Delete the lower third \"" + lt.getTitle() + "\"?", "Delete")) {
            return;
        }
        BackgroundTask.run("Delete lower third", () -> {
            scenes.deleteLowerThird(lt.getId());
            return true;
        }, ok -> refresh(null), err -> Dialogs.showError(this, "Could not delete.", err));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }
}

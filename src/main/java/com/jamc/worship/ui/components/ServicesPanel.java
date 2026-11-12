package com.jamc.worship.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.jamc.worship.model.Service;
import com.jamc.worship.service.ServicePlanService;
import com.jamc.worship.ui.BackgroundTask;
import com.jamc.worship.ui.Dialogs;
import com.jamc.worship.ui.Ui;
import com.jamc.worship.ui.theme.Palette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Service page: open previous services, start new ones from playlists or templates, manage templates. */
public class ServicesPanel extends JPanel {

    private static final String ALL = "All playlists";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault());

    private final ServicePlanService plans;
    private final DefaultListModel<Service> services = new DefaultListModel<>();
    private final DefaultListModel<Service> templates = new DefaultListModel<>();
    private final JList<Service> serviceList = new EmptyServiceList(services,
            "No services yet", "Create a service to build your first rundown.");
    private final JList<Service> templateList = new EmptyServiceList(templates,
            "No templates yet", "Save a service as a template to reuse its order.");
    private final JComboBox<String> playlistFilter = new JComboBox<>();
    private Consumer<Service> openHandler = s -> { };
    private Consumer<String> statusHandler = s -> { };

    public ServicesPanel(ServicePlanService plans) {
        super(new BorderLayout());
        this.plans = plans;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(20, 28, 20, 28));

        JPanel head = Ui.transparent(new BorderLayout());
        head.add(Ui.label("Services", Palette::text, "h2"), BorderLayout.WEST);
        JButton newButton = Ui.primaryButton("New Service", "plus");
        newButton.addActionListener(e -> newService());
        JPanel headRight = Ui.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headRight.add(newButton);
        head.add(headRight, BorderLayout.EAST);
        head.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));
        add(head, BorderLayout.NORTH);

        playlistFilter.addItem(ALL);
        Sidebar.PLAYLISTS.forEach(playlistFilter::addItem);
        playlistFilter.addActionListener(e -> refresh());

        JPanel grid = Ui.transparent(new GridLayout(1, 2, 16, 0));
        grid.add(column("Services", "Open a previous service, or duplicate it for next week.", serviceList, playlistFilter,
                new Action("Open", "folder", () -> open(serviceList.getSelectedValue())),
                new Action("Duplicate", "copy", () -> duplicate(serviceList.getSelectedValue())),
                new Action("Delete", "trash", () -> delete(serviceList.getSelectedValue()))));
        grid.add(column("Templates", "Reusable rundowns. Save one with the schedule's template button.", templateList, null,
                new Action("New Service from Template", "plus", () -> fromTemplate(templateList.getSelectedValue())),
                new Action("Rename", "edit", () -> rename(templateList.getSelectedValue())),
                new Action("Delete", "trash", () -> delete(templateList.getSelectedValue()))));
        add(grid, BorderLayout.CENTER);

        for (JList<Service> l : List.of(serviceList, templateList)) {
            l.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            l.setCellRenderer(new ServiceRenderer());
            l.setFixedCellHeight(50);
        }
        serviceList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && serviceList.getSelectedValue() != null) {
                    open(serviceList.getSelectedValue());
                }
            }
        });
    }

    public void setOpenHandler(Consumer<Service> openHandler) {
        this.openHandler = openHandler;
    }

    public void setStatusHandler(Consumer<String> statusHandler) {
        this.statusHandler = statusHandler;
    }

    public void refresh() {
        String filter = (String) playlistFilter.getSelectedItem();
        String playlist = ALL.equals(filter) ? null : filter;
        BackgroundTask.run("Load services", () -> new Object[]{plans.services(playlist), plans.templates()}, r -> {
            @SuppressWarnings("unchecked")
            List<Service> s = (List<Service>) r[0];
            @SuppressWarnings("unchecked")
            List<Service> t = (List<Service>) r[1];
            services.clear();
            services.addAll(s);
            templates.clear();
            templates.addAll(t);
        }, err -> Dialogs.showError(this, "Could not load services.", err));
    }

    private record Action(String text, String icon, Runnable run) {
    }

    private JComponent column(String title, String subtitle, JList<Service> list, JComponent filter, Action... actions) {
        JPanel p = Ui.panel(new BorderLayout(0, 10), Palette::secondaryBackground);
        p.setBorder(BorderFactory.createCompoundBorder(Ui.line(true, true, true, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        JPanel head = Ui.transparent(new BorderLayout(8, 2));
        head.add(Ui.label(title, Palette::text, "h4"), BorderLayout.NORTH);
        head.add(Ui.small(subtitle), BorderLayout.CENTER);
        if (filter != null) {
            head.add(filter, BorderLayout.EAST);
        }
        p.add(head, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        p.add(scroll, BorderLayout.CENTER);
        JPanel buttons = Ui.transparent(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (Action a : actions) {
            JButton b = Ui.button(a.text(), a.icon());
            b.addActionListener(e -> a.run().run());
            buttons.add(b);
        }
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private void newService() {
        List<String> options = new ArrayList<>(Sidebar.PLAYLISTS);
        Object choice = JOptionPane.showInputDialog(this, "Playlist for the new service:", "New Service",
                JOptionPane.PLAIN_MESSAGE, null, options.toArray(), options.getFirst());
        if (choice == null) {
            return;
        }
        BackgroundTask.run("Create service", () -> plans.create(choice.toString()), s -> {
            statusHandler.accept("Created \"" + s.getName() + "\"");
            openHandler.accept(s);
            refresh();
        }, err -> Dialogs.showError(this, "Could not create the service.", err));
    }

    private void open(Service s) {
        if (s == null) {
            return;
        }
        BackgroundTask.run("Open service", () -> plans.load(s.getId()).orElseThrow(), loaded -> {
            openHandler.accept(loaded);
            statusHandler.accept("Opened \"" + loaded.getName() + "\"");
        }, err -> Dialogs.showError(this, "Could not open the service.", err));
    }

    private void duplicate(Service s) {
        if (s == null) {
            return;
        }
        BackgroundTask.run("Duplicate service", () -> plans.duplicate(s.getId()), copy -> {
            statusHandler.accept("Duplicated as \"" + copy.getName() + "\"");
            refresh();
        }, err -> Dialogs.showError(this, "Could not duplicate the service.", err));
    }

    private void fromTemplate(Service t) {
        if (t == null) {
            return;
        }
        BackgroundTask.run("New from template", () -> plans.createFromTemplate(t.getId()), s -> {
            statusHandler.accept("Created \"" + s.getName() + "\" from template");
            openHandler.accept(s);
            refresh();
        }, err -> Dialogs.showError(this, "Could not create the service.", err));
    }

    private void rename(Service s) {
        if (s == null) {
            return;
        }
        Object name = JOptionPane.showInputDialog(this, "Name:", "Rename", JOptionPane.PLAIN_MESSAGE, null, null, s.getName());
        if (name == null || name.toString().isBlank()) {
            return;
        }
        BackgroundTask.run("Rename", () -> {
            Service full = plans.load(s.getId()).orElseThrow();
            full.setName(name.toString().strip());
            return plans.save(full);
        }, saved -> refresh(), err -> Dialogs.showError(this, "Could not rename.", err));
    }

    private void delete(Service s) {
        if (s == null || !Dialogs.confirmDestructive(this, "Delete", "Delete \"" + s.getName() + "\"?\n\n"
                + "Songs and media stay in the library. This cannot be undone.", "Delete")) {
            return;
        }
        BackgroundTask.run("Delete service", () -> plans.delete(s.getId()), ok -> {
            statusHandler.accept("Deleted \"" + s.getName() + "\"");
            refresh();
        }, err -> Dialogs.showError(this, "Could not delete.", err));
    }

    private static final class ServiceRenderer extends JPanel implements ListCellRenderer<Service> {
        private final JLabel name = new JLabel();
        private final JLabel info = new JLabel();

        ServiceRenderer() {
            super(new BorderLayout(0, 2));
            setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
            name.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold");
            info.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            add(name, BorderLayout.NORTH);
            add(info, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Service> l, Service s, int index, boolean selected,
                                                      boolean focused) {
            name.setText(s.getName());
            String when = s.getUpdatedAt() == null ? "" : "  ·  edited " + WHEN.format(s.getUpdatedAt());
            info.setText((s.getPlaylist() == null ? "" : s.getPlaylist() + "  ·  ") + s.getItemCount()
                    + (s.getItemCount() == 1 ? " item" : " items") + when);
            name.setForeground(selected ? l.getSelectionForeground() : Palette.text());
            info.setForeground(selected ? new Color(255, 255, 255, 200) : Palette.secondaryText());
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            setOpaque(selected);
            return this;
        }
    }

    /** Keeps an empty management page informative instead of presenting a large unexplained rectangle. */
    private static final class EmptyServiceList extends JList<Service> {
        private final String heading;
        private final String message;

        EmptyServiceList(DefaultListModel<Service> model, String heading, String message) {
            super(model);
            this.heading = heading;
            this.message = message;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getModel().getSize() != 0) {
                return;
            }
            int centerY = Math.max(70, getHeight() / 2 - 12);
            Font titleFont = getFont().deriveFont(Font.BOLD, getFont().getSize2D() + 1f);
            g.setFont(titleFont);
            FontMetrics titleMetrics = g.getFontMetrics();
            g.setColor(Palette.text());
            g.drawString(heading, Math.max(16, (getWidth() - titleMetrics.stringWidth(heading)) / 2), centerY);

            g.setFont(getFont());
            FontMetrics messageMetrics = g.getFontMetrics();
            g.setColor(Palette.secondaryText());
            g.drawString(message, Math.max(16, (getWidth() - messageMetrics.stringWidth(message)) / 2),
                    centerY + messageMetrics.getHeight() + 4);
        }
    }
}

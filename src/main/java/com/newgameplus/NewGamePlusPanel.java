package com.newgameplus;

import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.ListCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

class NewGamePlusPanel extends PluginPanel {
    private final NewGamePlusPlugin plugin;
    private final ItemManager itemManager;
    private final Client client;

    private final DefaultListModel<UnlockEntry> model = new DefaultListModel<>();
    private final JList<UnlockEntry> list = new JList<>(model);

    // No direct text input; we open a chatbox search in-game for adding items

    NewGamePlusPanel(NewGamePlusPlugin plugin, ItemManager itemManager, Client client) {
        super(false); // no scrolling container, we add our own
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.client = client;

        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(300, 0));

        // Header
        JLabel header = new JLabel("Unlocked Items");
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(header, BorderLayout.NORTH);

        // Center list with custom renderer
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new UnlockRenderer());
        JScrollPane scroll = new JScrollPane(list);
        add(scroll, BorderLayout.CENTER);

        // Footer controls
        JPanel controls = new JPanel(new GridBagLayout());
        controls.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 2, 2, 2);

        JButton addBtn = new JButton("Add");
        gc.gridy = 0;
        gc.gridx = 0;
        gc.gridwidth = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        controls.add(addBtn, gc);

        JButton removeBtn = new JButton("Remove Selected");
        gc.gridy = 1;
        gc.gridx = 0;
        gc.gridwidth = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        controls.add(removeBtn, gc);

        JButton clearBtn = new JButton("Delete All Unlocks");
        gc.gridy = 2;
        gc.gridx = 0;
        gc.gridwidth = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        controls.add(clearBtn, gc);

        add(controls, BorderLayout.SOUTH);

        // Actions
        addBtn.addActionListener(e -> onAdd());
        removeBtn.addActionListener(e -> onRemove());
        clearBtn.addActionListener(e -> onClearAll());

        refreshFromPlugin();
    }

    void refreshFromPlugin() {
        // Ask plugin to build entries on the client thread, then callback into setEntries()
        plugin.refreshPanelAsync(this);
    }

    // Called by plugin on the Swing thread to update entries
    void setEntries(Map<Integer, String> idToName) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setEntries(idToName));
            return;
        }
        model.clear();
        List<UnlockEntry> entries = new ArrayList<>();
        for (Map.Entry<Integer, String> e : idToName.entrySet()) {
            entries.add(new UnlockEntry(e.getKey(), e.getValue()));
        }
        entries.sort(Comparator.comparing(a -> a.name.toLowerCase()));
        for (UnlockEntry e : entries) {
            model.addElement(e);
        }
    }

    private void onAdd() {
        // Open in-game chatbox search input; selection will add and refresh
        plugin.openAddItemSearch();
    }

    private void onRemove() {
        List<UnlockEntry> selected = list.getSelectedValuesList();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        for (UnlockEntry e : selected) {
            plugin.removeUnlock(e.id);
        }
        refreshFromPlugin();
    }

    private void onClearAll() {
        int res = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to delete all unlocks?",
                "Confirm Delete All",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            plugin.clearAllUnlocks();
        }
    }

    private final class UnlockRenderer implements ListCellRenderer<UnlockEntry> {
        @Override
        public Component getListCellRendererComponent(JList<? extends UnlockEntry> jList, UnlockEntry value, int index, boolean isSelected, boolean cellHasFocus) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            row.setOpaque(true);
            if (isSelected) {
                row.setBackground(jList.getSelectionBackground());
                row.setForeground(jList.getSelectionForeground());
            } else {
                row.setBackground(jList.getBackground());
                row.setForeground(jList.getForeground());
            }

            BufferedImage icon = itemManager.getImage(value.id, 1, false);
            JLabel iconLabel = new JLabel(icon != null ? new javax.swing.ImageIcon(icon) : null);
            JLabel textLabel = new JLabel(value.name);
            row.add(iconLabel);
            row.add(textLabel);
            return row;
        }
    }

    private static final class UnlockEntry {
        final int id;
        final String name;

        UnlockEntry(int id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}

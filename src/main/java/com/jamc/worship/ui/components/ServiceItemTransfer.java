package com.jamc.worship.ui.components;

import com.jamc.worship.model.ServiceItem;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/** Drag payload for dropping library items (songs, slide decks, ...) onto the service schedule. */
public final class ServiceItemTransfer implements Transferable {

    /** JVM-local flavor: the item is handed over as-is, no serialization involved. */
    public static final DataFlavor FLAVOR = createFlavor();

    private final ServiceItem item;

    public ServiceItemTransfer(ServiceItem item) {
        this.item = item;
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[]{FLAVOR, DataFlavor.stringFlavor};
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return FLAVOR.equals(flavor) || DataFlavor.stringFlavor.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
        if (FLAVOR.equals(flavor)) {
            return new ServiceItem(item.getType(), item.getTitle(), item.getRefId());
        }
        if (DataFlavor.stringFlavor.equals(flavor)) {
            return item.getTitle();
        }
        throw new UnsupportedFlavorException(flavor);
    }

    private static DataFlavor createFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + ServiceItem.class.getName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}

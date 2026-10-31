package com.jamc.worship.repository;

import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.model.Service;
import com.jamc.worship.model.ServiceItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite persistence for service rundowns, templates and their items. */
public class ServiceRepository {

    private final DatabaseManager db;

    public ServiceRepository(DatabaseManager db) {
        this.db = db;
    }

    /** Summaries (no items), newest first. {@code templates} selects templates or regular services. */
    public List<Service> findAll(boolean templates, String playlist) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.name, s.playlist, s.service_date, s.is_template, s.notes, s.updated_at,
                       (SELECT COUNT(*) FROM service_items i WHERE i.service_id = s.id) AS item_count
                FROM services s WHERE s.is_template = ?""");
        if (playlist != null) {
            sql.append(" AND s.playlist = ?");
        }
        sql.append(" ORDER BY s.updated_at DESC, s.id DESC");
        return db.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                ps.setInt(1, templates ? 1 : 0);
                if (playlist != null) {
                    ps.setString(2, playlist);
                }
                List<Service> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Service s = map(rs);
                        s.setItemCount(rs.getInt("item_count"));
                        out.add(s);
                    }
                }
                return out;
            }
        });
    }

    public Optional<Service> findById(long id) {
        return db.query(conn -> {
            Service service;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, name, playlist, service_date, is_template, notes, updated_at FROM services WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    service = map(rs);
                }
            }
            service.setItems(loadItems(conn, id));
            service.setItemCount(service.getItems().size());
            return Optional.of(service);
        });
    }

    /** Inserts or updates the service and replaces all of its items. */
    public Service save(Service service) {
        String now = Instant.now().toString();
        long id = db.inTransaction(conn -> {
            long sid;
            if (service.getId() == null) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO services(name, playlist, service_date, is_template, notes, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, service);
                    ps.setString(6, now);
                    ps.setString(7, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        sid = keys.getLong(1);
                    }
                }
            } else {
                sid = service.getId();
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE services SET name = ?, playlist = ?, service_date = ?, is_template = ?, notes = ?, updated_at = ?
                        WHERE id = ?""")) {
                    bind(ps, service);
                    ps.setString(6, now);
                    ps.setLong(7, sid);
                    if (ps.executeUpdate() == 0) {
                        throw new SQLException("Service " + sid + " no longer exists");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM service_items WHERE service_id = ?")) {
                    ps.setLong(1, sid);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO service_items(service_id, item_type, title, ref_id, payload, sort_order, tag) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                List<ServiceItem> items = service.getItems();
                for (int i = 0; i < items.size(); i++) {
                    ServiceItem it = items.get(i);
                    ps.setLong(1, sid);
                    ps.setString(2, it.getType().name());
                    ps.setString(3, it.getTitle() == null || it.getTitle().isBlank() ? it.getType().displayName() : it.getTitle());
                    if (it.getRefId() == null) {
                        ps.setNull(4, Types.INTEGER);
                    } else {
                        ps.setLong(4, it.getRefId());
                    }
                    ps.setString(5, it.getPayload());
                    ps.setInt(6, i);
                    ps.setString(7, it.getTag());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return sid;
        });
        return findById(id).orElseThrow();
    }

    public boolean delete(long id) {
        return db.inTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM services WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private static List<ServiceItem> loadItems(Connection conn, long serviceId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, item_type, title, ref_id, payload, tag FROM service_items WHERE service_id = ? ORDER BY sort_order, id")) {
            ps.setLong(1, serviceId);
            List<ServiceItem> items = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ref = rs.getLong("ref_id");
                    ServiceItem item = new ServiceItem(ServiceItem.Type.parse(rs.getString("item_type")),
                            rs.getString("title"), rs.wasNull() ? null : ref);
                    item.setId(rs.getLong("id"));
                    item.setPayload(rs.getString("payload"));
                    item.setTag(rs.getString("tag"));
                    items.add(item);
                }
            }
            return items;
        }
    }

    private static void bind(PreparedStatement ps, Service s) throws SQLException {
        ps.setString(1, s.getName().strip());
        ps.setString(2, s.getPlaylist());
        ps.setString(3, s.getDate() == null ? null : s.getDate().toString());
        ps.setInt(4, s.isTemplate() ? 1 : 0);
        ps.setString(5, s.getNotes());
    }

    private static Service map(ResultSet rs) throws SQLException {
        Service s = new Service();
        s.setId(rs.getLong("id"));
        s.setName(rs.getString("name"));
        s.setPlaylist(rs.getString("playlist"));
        String date = rs.getString("service_date");
        try {
            s.setDate(date == null ? null : LocalDate.parse(date));
        } catch (DateTimeParseException e) {
            s.setDate(null);
        }
        s.setTemplate(rs.getInt("is_template") != 0);
        s.setNotes(rs.getString("notes"));
        try {
            String u = rs.getString("updated_at");
            s.setUpdatedAt(u == null ? null : Instant.parse(u));
        } catch (DateTimeParseException e) {
            s.setUpdatedAt(null);
        }
        return s;
    }
}

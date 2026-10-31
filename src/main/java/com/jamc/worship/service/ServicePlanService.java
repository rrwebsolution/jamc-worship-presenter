package com.jamc.worship.service;

import com.jamc.worship.model.Service;
import com.jamc.worship.model.ServiceItem;
import com.jamc.worship.repository.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Service rundown use cases: create, open, save, templates. Blocking: call from background workers.
 *
 * <p>The open service is saved continuously (autosave), so a crash or power cut never loses the
 * rundown; at startup the last open service is reopened.
 */
public class ServicePlanService {

    private static final Logger log = LoggerFactory.getLogger(ServicePlanService.class);
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private final ServiceRepository repository;

    public ServicePlanService(ServiceRepository repository) {
        this.repository = repository;
    }

    public List<Service> services(String playlist) {
        return repository.findAll(false, playlist);
    }

    public List<Service> templates() {
        return repository.findAll(true, null);
    }

    public Optional<Service> load(long id) {
        return repository.findById(id);
    }

    public Service save(Service service) {
        if (service.getName() == null || service.getName().isBlank()) {
            service.setName(defaultName(service.getPlaylist(), service.getDate()));
        }
        return repository.save(service);
    }

    /** A new, saved, empty service for a playlist (e.g. "Sunday Service — Nov 2, 2026"). */
    public Service create(String playlist) {
        Service s = new Service();
        s.setPlaylist(playlist);
        s.setDate(LocalDate.now());
        s.setName(defaultName(playlist, s.getDate()));
        Service saved = repository.save(s);
        log.info("Created service #{} '{}'", saved.getId(), saved.getName());
        return saved;
    }

    /** A new service copied from a template, dated today. */
    public Service createFromTemplate(long templateId) {
        Service template = repository.findById(templateId)
                .orElseThrow(() -> new ValidationException("That template no longer exists."));
        Service s = template.copyWithoutId();
        s.setTemplate(false);
        s.setDate(LocalDate.now());
        s.setName(defaultName(template.getPlaylist() != null ? template.getPlaylist() : template.getName(), s.getDate()));
        return repository.save(s);
    }

    public Service saveAsTemplate(Service service, String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Please enter a template name.");
        }
        Service t = service.copyWithoutId();
        t.setTemplate(true);
        t.setDate(null);
        t.setName(name.strip());
        Service saved = repository.save(t);
        log.info("Saved template #{} '{}'", saved.getId(), saved.getName());
        return saved;
    }

    public Service duplicate(long id) {
        Service original = repository.findById(id).orElseThrow(() -> new ValidationException("That service no longer exists."));
        Service copy = original.copyWithoutId();
        copy.setName(original.getName() + " (Copy)");
        return repository.save(copy);
    }

    public boolean delete(long id) {
        return repository.delete(id);
    }

    /** The newest service in a playlist, if any. */
    public Optional<Service> latest(String playlist) {
        return repository.findAll(false, playlist).stream().findFirst().flatMap(s -> repository.findById(s.getId()));
    }

    public static String defaultName(String playlist, LocalDate date) {
        String base = playlist == null || playlist.isBlank() ? "Service" : playlist;
        return date == null ? base : base + " — " + TITLE_DATE.format(date);
    }

    /** Item for an announcement or sermon with its slide text (blank lines separate slides). */
    public static ServiceItem textItem(ServiceItem.Type type, String title, String text) {
        ServiceItem item = new ServiceItem(type, title, null);
        item.setPayload(text);
        return item;
    }
}

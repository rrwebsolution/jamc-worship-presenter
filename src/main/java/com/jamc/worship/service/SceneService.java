package com.jamc.worship.service;

import com.jamc.worship.model.LowerThird;
import com.jamc.worship.model.Scene;
import com.jamc.worship.model.Source;
import com.jamc.worship.repository.LowerThirdRepository;
import com.jamc.worship.repository.SceneRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/** Scenes, sources and lower thirds. Blocking: call from background workers. */
public class SceneService {

    private static final Logger log = LoggerFactory.getLogger(SceneService.class);

    private final SceneRepository scenes;
    private final LowerThirdRepository lowerThirds;

    public SceneService(SceneRepository scenes, LowerThirdRepository lowerThirds) {
        this.scenes = scenes;
        this.lowerThirds = lowerThirds;
    }

    public List<Scene> scenes() {
        return scenes.findAll();
    }

    public Optional<Scene> scene(long id) {
        return scenes.findById(id);
    }

    public Scene create(String name) {
        Scene s = new Scene(null, validName(name));
        s.getSources().add(new Source("Lyrics", com.jamc.worship.model.SourceType.LYRICS));
        s.getSources().add(new Source("Background", com.jamc.worship.model.SourceType.BACKGROUND));
        Scene saved = scenes.save(s, scenes.count());
        log.info("Created scene #{} '{}'", saved.getId(), saved.getName());
        return saved;
    }

    public Scene save(Scene scene) {
        scene.setName(validName(scene.getName()));
        return scenes.save(scene, scenes.count());
    }

    public Scene duplicate(long id) {
        Scene original = scenes.findById(id).orElseThrow(() -> new ValidationException("That scene no longer exists."));
        Scene copy = original.copy();
        copy.setId(null);
        copy.setName(original.getName() + " (Copy)");
        copy.setBuiltin(false);
        copy.getSources().forEach(s -> s.setId(null));
        return scenes.save(copy, scenes.count());
    }

    public void delete(long id) {
        if (scenes.findById(id).isEmpty()) {
            throw new ValidationException("That scene no longer exists.");
        }
        // the standard scenes can be deleted too; only the last scene is kept
        if (scenes.count() <= 1) {
            throw new ValidationException("At least one scene is needed.");
        }
        scenes.delete(id);
    }

    public void reorder(List<Long> ids) {
        scenes.reorder(ids);
    }

    public List<LowerThird> lowerThirds() {
        return lowerThirds.findAll();
    }

    public LowerThird saveLowerThird(LowerThird lt) {
        if (lt.getName().isBlank()) {
            lt.setName(lt.getTitle().isBlank() ? "Lower third" : lt.getTitle());
        }
        if (lt.getTitle().isBlank()) {
            throw new ValidationException("Please enter the name or title to show.");
        }
        return lowerThirds.save(lt);
    }

    public void deleteLowerThird(long id) {
        lowerThirds.delete(id);
    }

    public Optional<LowerThird> lowerThird(long id) {
        return lowerThirds.findById(id);
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Please enter a scene name.");
        }
        return name.strip();
    }
}

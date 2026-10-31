package com.jamc.worship.model;

/**
 * An imported PowerPoint (or slide image set) in the presentation library.
 *
 * <p>The original file stays where the user keeps it and is never modified or deleted. Its slides
 * are rendered once into a cache folder ({@code data/presentations/<folder>/}) so they show
 * instantly on every start; {@code sourceSize} and {@code sourceModified} record which version of
 * the file the cache was made from, so a changed file can be detected and refreshed.
 *
 * @param folder         cache folder name under the presentations directory
 * @param renderer       name of the renderer that produced the cached slides
 * @param warningCount   slides features that could not be reproduced exactly (see presentation.json)
 */
public record Presentation(Long id, String name, String sourcePath, String folder, int slideCount,
                           String renderer, long sourceSize, long sourceModified, int warningCount,
                           String importedAt, String updatedAt) {

    public Presentation withId(long newId) {
        return new Presentation(newId, name, sourcePath, folder, slideCount, renderer, sourceSize, sourceModified,
                warningCount, importedAt, updatedAt);
    }

    public Presentation withName(String newName) {
        return new Presentation(id, newName, sourcePath, folder, slideCount, renderer, sourceSize, sourceModified,
                warningCount, importedAt, updatedAt);
    }

    /** File name of the original, e.g. "Sunday Sermon.pptx". */
    public String sourceFileName() {
        if (sourcePath == null) {
            return "";
        }
        String p = sourcePath.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    public String slideCountText() {
        return slideCount + (slideCount == 1 ? " slide" : " slides");
    }

    @Override
    public String toString() {
        return name;
    }
}

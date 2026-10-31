package com.jamc.worship.app;

import com.jamc.worship.config.AppConfig;
import com.jamc.worship.config.AppPaths;
import com.jamc.worship.database.DatabaseManager;
import com.jamc.worship.service.BibleService;
import com.jamc.worship.service.MediaService;
import com.jamc.worship.service.PresentationService;
import com.jamc.worship.service.SceneService;
import com.jamc.worship.service.ServicePlanService;
import com.jamc.worship.service.SlideDeckService;
import com.jamc.worship.service.SongService;
import com.jamc.worship.service.ThemeService;

/** Application-wide services, created once at startup and handed to the UI. */
public record AppContext(AppPaths paths, AppConfig config, DatabaseManager database, SongService songService,
                         ThemeService themeService, SlideDeckService slideDeckService, ServicePlanService servicePlans,
                         MediaService mediaService, BibleService bibleService, SceneService sceneService,
                         PresentationService presentationService) {
}

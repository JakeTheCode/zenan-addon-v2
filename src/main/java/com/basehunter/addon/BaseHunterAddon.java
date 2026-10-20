package com.basehunter.addon;

import com.basehunter.addon.modules.BaseHunter;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class BaseHunterAddon extends MeteorAddon {
    public static final Category CATEGORY = new Category("Zenan Addon");

    @Override
    public void onInitialize() {
        Modules.get().add(new BaseHunter("base-hunter", "Flags suspicious chunks."));
        Modules.get().add(new BaseHunter("stash-finder", "Scans for stashes."));
        Modules.get().add(new BaseHunter("farm-finder", "Locates farms."));
        Modules.get().add(new BaseHunter("block-esp", "Highlights blocks."));
        Modules.get().add(new BaseHunter("entrance-finder", "Finds shafts."));
        Modules.get().add(new BaseHunter("highway-scanner", "Scans highways."));
    }

    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    public String getPackage() {
        return "com.basehunter.addon";
    }

    public String getName() {
        return "Zenan Addon";
    }

    public String getAuthors() {
        return "jake";
    }
}

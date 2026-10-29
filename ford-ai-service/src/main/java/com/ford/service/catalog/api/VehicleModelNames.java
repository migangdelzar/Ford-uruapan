package com.ford.service.catalog.api;

import java.util.List;

/** Ford vehicle names shared by catalog grounding and conversation fact extraction. */
public final class VehicleModelNames {
    private static final List<String> VALUES = List.of(
            "Mustang Mach-E", "Bronco Sport", "Ranger Raptor", "Lobo Raptor", "F-150 Lightning",
            "E-Transit", "Expedition", "Explorer", "Territory", "Maverick", "Mustang", "Bronco",
            "Ranger", "Lobo", "Transit", "F-150");

    private VehicleModelNames() { }

    public static List<String> all() { return VALUES; }
}

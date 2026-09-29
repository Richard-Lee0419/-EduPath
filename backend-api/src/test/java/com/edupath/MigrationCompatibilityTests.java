package com.edupath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MigrationCompatibilityTests {

    private final ClassLoader classLoader = getClass().getClassLoader();

    @Test
    void keepsTheDeployedV6LineageAndAppendsNewMigrations() {
        assertNotNull(classLoader.getResource("db/migration/V6__demo_class_readiness.sql"));
        assertNull(classLoader.getResource("db/migration/V6__expand_course_knowledge_points.sql"));
        assertNotNull(classLoader.getResource("db/migration/V7__expand_course_knowledge_points.sql"));
        assertNotNull(classLoader.getResource("db/migration/V16__demo_scenario_runs.sql"));
    }
}

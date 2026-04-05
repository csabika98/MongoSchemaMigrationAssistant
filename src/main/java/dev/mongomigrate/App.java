package dev.mongomigrate;

import dev.mongomigrate.tui.TuiManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {

    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        logger.info("Starting MongoDB Migration Assistant...");
        try {
            TuiManager tui = new TuiManager();
            tui.start();
        } catch (Exception e) {
            logger.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
